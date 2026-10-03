package com.holrigue.argdos.dashboard

import android.Manifest
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.format.DateUtils
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The companion's single screen: connect to the watch, sync real metrics from
 * Health Connect (steps, heart rate, computed sleep score) to its health
 * characteristic, forward phone notifications (with a per-app filter), ring the
 * watch / let it ring the phone, and send GPX routes. Stress is left out - Health
 * Connect has no stress type.
 */
class MainActivity : ComponentActivity() {

    private lateinit var ble: BleClient
    private lateinit var hc: HealthConnectSource

    private val ui = UiState()

    // Best-effort POST_NOTIFICATIONS grant so the background Find ring can post.
    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val ok = grants.values.all { it }
            ui.status = if (ok) "Permissions granted" else "Permissions denied - BLE needs them"
            if (ok) ble.startScan()
        }

    private val hcPermissionLauncher =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
            if (granted.containsAll(hc.permissions)) {
                syncFromHealthConnect()
            } else {
                ui.status = "Health Connect permissions denied"
            }
        }

    // Separate launcher for the auto-sync (background) grant: it only re-applies
    // the schedule and never kicks off a foreground write.
    private val hcBackgroundLauncher =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
            val bg = granted.contains(hc.backgroundPermission)
            ui.status = if (bg) "Auto-sync ready (background access granted)"
                        else "Auto-sync on - grant background access for it to run while closed"
            SyncScheduler.apply(this)
        }

    // "Send a GPX route": pick a file, then hand it to GpxShareActivity, which
    // already knows how to read it and stream it to the watch's /gpx.
    private val gpxPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                startActivity(
                    Intent(this, GpxShareActivity::class.java)
                        .setAction(Intent.ACTION_VIEW)
                        .setData(uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                )
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ble = BleClient(this)
        hc = HealthConnectSource(this)
        ui.hcAvailable = hc.isAvailable()
        ui.autoSyncOn = Prefs.autoSyncEnabled(this)
        ui.intervalMin = Prefs.intervalMinutes(this)
        ui.hasWatchAddr = Prefs.watchAddress(this) != null
        ui.versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (_: Exception) { "" }
        refreshPrefsState()
        ble.listener = object : BleClient.Listener {
            override fun onScanResult(devices: List<BleClient.Entry>) {
                ui.devices.clear(); ui.devices.addAll(devices)
            }
            override fun onStatus(status: String, connected: Boolean) {
                ui.status = status; ui.connected = connected
                // A successful connect saves the watch's address (see BleClient).
                ui.hasWatchAddr = Prefs.watchAddress(this@MainActivity) != null
            }
            override fun onWriteResult(ok: Boolean) {
                ui.status = if (ok) "Packet sent ✓" else "Write failed"
            }
            override fun onFindRing(active: Boolean) {
                if (active) startPhoneRing() else stopPhoneRing()
            }
        }

        setContent {
            DotTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DashboardScreen(
                        ui = ui,
                        onScan = { ensurePermissionsThenScan() },
                        onConnect = { ble.connect(it.device) },
                        onReconnectSaved = { reconnectSaved() },
                        onDisconnect = { ble.disconnect() },
                        onSyncHealthConnect = { startHealthConnectSync() },
                        onToggleAutoSync = { toggleAutoSync(it) },
                        onSetInterval = { setSyncInterval(it) },
                        onRingWatch = { ringWatch() },
                        onStopRing = { stopPhoneRing() },
                        onToggleFindBackground = { setFindBackground(it) },
                        onToggleNotifRelay = { setNotifRelay(it) },
                        onOpenNotifAccess = { openNotifAccessSettings() },
                        onOpenNotifApps = { startActivity(Intent(this@MainActivity, NotifAppsActivity::class.java)) },
                        onPickGpx = { gpxPicker.launch(arrayOf("*/*")) },
                    )
                }
            }
        }
    }

    // ---- Auto-sync -----------------------------------------------------------
    private fun toggleAutoSync(on: Boolean) {
        if (on && Prefs.watchAddress(this) == null) {
            ui.status = "Connect to the watch once first, then enable auto-sync"
            ui.autoSyncOn = false
            return
        }
        Prefs.setAutoSyncEnabled(this, on)
        ui.autoSyncOn = on
        SyncScheduler.apply(this)
        if (on) {
            ui.status = "Auto-sync on (every ${ui.intervalMin} min)"
            // Ask for background Health Connect access so the worker can read
            // while the app is closed (Android 14+). Best-effort: on older
            // Android it is a no-op, and if denied the worker just skips a run.
            if (hc.isAvailable()) {
                lifecycleScope.launch {
                    val granted = try { hc.grantedPermissions() } catch (e: Exception) { emptySet() }
                    if (!granted.contains(hc.backgroundPermission)) {
                        hcBackgroundLauncher.launch(hc.permissionsWithBackground)
                    }
                }
            }
        } else {
            ui.status = "Auto-sync off"
        }
    }

    private fun setSyncInterval(min: Int) {
        Prefs.setIntervalMinutes(this, min)
        ui.intervalMin = Prefs.intervalMinutes(this)
        if (ui.autoSyncOn) {
            SyncScheduler.apply(this)
            ui.status = "Auto-sync every ${ui.intervalMin} min"
        }
    }

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        }

    private fun ensurePermissionsThenScan() {
        if (!ble.isBluetoothOn()) {
            ui.status = "Turn Bluetooth on first"
            return
        }
        permissionLauncher.launch(requiredPermissions())
    }

    // Reconnect straight to the saved watch by MAC, no scan. Used when the scan
    // no longer surfaces the watch (e.g. it is already OS-connected, or advertises
    // without the ANS label after a re-flash).
    private fun reconnectSaved() {
        if (!ble.isBluetoothOn()) {
            ui.status = "Turn Bluetooth on first"
            return
        }
        val addr = Prefs.watchAddress(this)
        if (addr == null) {
            ui.status = "No saved watch yet - Scan and connect once first"
            return
        }
        // BLUETOOTH_CONNECT is enough for a direct connect (no scan permission).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(requiredPermissions())
            return
        }
        ui.status = "Reconnecting to saved watch ($addr)..."
        ble.connectByAddress(addr)
    }

    // ---- Find --------------------------------------------------------------
    // Phone -> watch: ask the watch to ring.
    private fun ringWatch() {
        if (!ble.canFind()) {
            ui.status = "Connect to the watch first (Find needs the live link)"
            return
        }
        val ok = ble.ringWatch(true)
        ui.status = if (ok) "Ringing the watch..." else "Couldn't reach the watch"
    }

    // Watch -> phone: ring this phone at max alarm volume (shared PhoneRinger),
    // used while the app is open; the same ringer runs from FindService when it
    // is closed.
    private fun startPhoneRing() {
        PhoneRinger.start(this)
        ui.phoneRinging = true
        ui.status = "Watch is ringing your phone"
    }

    private fun stopPhoneRing() {
        PhoneRinger.stop(this)
        ui.phoneRinging = false
    }

    // Enable/disable the background Find link. Enabling requests the notification
    // permission (Android 13+) so the ring can post; the service itself starts on
    // the next onPause hand-off (and immediately here so it takes effect at once).
    private fun setFindBackground(on: Boolean) {
        if (on && Prefs.watchAddress(this) == null) {
            ui.status = "Connect to the watch once first, then enable background Find"
            ui.findBackgroundOn = false
            return
        }
        Prefs.setFindBackgroundEnabled(this, on)
        ui.findBackgroundOn = on
        if (on) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            ui.status = "Background Find on - your phone can ring with the app closed"
        } else {
            FindService.stop(this)
            ui.status = "Background Find off"
        }
    }

    // ---- Notification relay --------------------------------------------------
    // Whether the user has granted this app notification access (required for the
    // NotificationListenerService to see other apps' notifications).
    private fun isNotifAccessGranted(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        val me = ComponentName(this, NotificationRelayService::class.java)
        return enabled.split(":").any {
            val c = ComponentName.unflattenFromString(it)
            c != null && c.packageName == me.packageName
        }
    }

    private fun openNotifAccessSettings() {
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            ui.status = "Couldn't open notification-access settings"
        }
    }

    private fun setNotifRelay(on: Boolean) {
        if (on && Prefs.watchAddress(this) == null) {
            ui.status = "Connect to the watch once first, then enable notifications"
            ui.notifRelayOn = false
            return
        }
        Prefs.setNotifRelayEnabled(this, on)
        ui.notifRelayOn = on
        if (on) {
            if (!isNotifAccessGranted()) {
                ui.status = "Grant notification access so the watch gets your alerts"
                openNotifAccessSettings()
            } else {
                ui.status = "Notifications will now show on the watch"
            }
        } else {
            ui.status = "Notification relay off"
        }
        ui.notifAccessGranted = isNotifAccessGranted()
    }

    // ---- Health Connect ------------------------------------------------------
    private fun startHealthConnectSync() {
        if (!ui.connected) {
            ui.status = "Connect to the watch first"
            return
        }
        if (!hc.isAvailable()) {
            ui.status = "Health Connect not available on this phone"
            return
        }
        lifecycleScope.launch {
            if (hc.hasAllPermissions()) {
                syncFromHealthConnect()
            } else {
                hcPermissionLauncher.launch(hc.permissions)
            }
        }
    }

    private fun syncFromHealthConnect() {
        lifecycleScope.launch {
            ui.status = "Reading Health Connect..."
            val snap = try {
                hc.read()
            } catch (e: Exception) {
                ui.status = "Health Connect read failed: ${e.message}"
                return@launch
            }
            val packet = HealthPacket.build(
                sleepScore = snap.sleepScore,
                steps = snap.steps,
                stress = null,          // Health Connect has no stress type
                hrLow = snap.hrLow,
                hrHigh = snap.hrHigh,
            )
            val ok = ble.write(packet)
            if (ok) Prefs.setLastSync(this@MainActivity, System.currentTimeMillis(), snap.detail)
            refreshPrefsState()
            ui.status = if (ok) "Sent to watch ✓  (${snap.detail})" else "Write failed"
        }
    }

    // Pull everything the screen shows from persisted state. Called at start, on
    // resume (the user may have changed notification access / muted apps in a
    // sub-screen or system settings) and after a sync.
    private fun refreshPrefsState() {
        ui.autoSyncOn = Prefs.autoSyncEnabled(this)
        ui.intervalMin = Prefs.intervalMinutes(this)
        ui.hasWatchAddr = Prefs.watchAddress(this) != null
        ui.findBackgroundOn = Prefs.findBackgroundEnabled(this)
        ui.notifRelayOn = Prefs.notifRelayEnabled(this)
        ui.notifAccessGranted = isNotifAccessGranted()
        ui.mutedApps = Prefs.mutedApps(this).size
        val last = Prefs.lastSyncMs(this)
        ui.lastSync = if (last > 0L) relativeTime(last) else ""
        ui.lastSyncDetail = Prefs.lastSyncDetail(this)
    }

    private fun relativeTime(whenMs: Long): String {
        val now = System.currentTimeMillis()
        if (now - whenMs < DateUtils.MINUTE_IN_MILLIS) return "just now"
        return DateUtils.getRelativeTimeSpanString(whenMs, now, DateUtils.MINUTE_IN_MILLIS).toString()
    }

    override fun onResume() {
        super.onResume()
        // App is visible: the in-app link owns Find, so stop the background one.
        FindService.stop(this)
        // Returning from the system notification-access screen or the "Choose
        // apps" screen: refresh everything the dashboard shows.
        refreshPrefsState()
    }

    override fun onPause() {
        super.onPause()
        // Leaving the foreground: hand off to the background link if enabled, so
        // the watch can still ring the phone. Guarded - some OEMs restrict
        // starting a foreground service at this point.
        if (Prefs.findBackgroundEnabled(this) && Prefs.watchAddress(this) != null) {
            try { FindService.start(this) } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPhoneRing()
        ble.disconnect()
    }
}

/** Observable UI state (Compose-friendly). */
class UiState {
    var status by mutableStateOf("Idle")
    var connected by mutableStateOf(false)
    var hcAvailable by mutableStateOf(false)
    val devices = mutableStateListOf<BleClient.Entry>()
    // Health sync
    var autoSyncOn by mutableStateOf(false)
    var intervalMin by mutableIntStateOf(Prefs.DEFAULT_INTERVAL_MIN)
    var hasWatchAddr by mutableStateOf(false)
    var lastSync by mutableStateOf("")          // "5 minutes ago"; empty = never synced
    var lastSyncDetail by mutableStateOf("")    // what that sync carried
    // Find
    var phoneRinging by mutableStateOf(false)
    var findBackgroundOn by mutableStateOf(false)
    // Notifications relay
    var notifRelayOn by mutableStateOf(false)
    var notifAccessGranted by mutableStateOf(false)
    var mutedApps by mutableIntStateOf(0)
    // Footer
    var versionName by mutableStateOf("")
}

// ---- Building blocks -----------------------------------------------------------

/** A titled card; each feature of the app gets one so the screen reads in sections. */
@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = DotWhite)
            content()
        }
    }
}

/** Secondary explanatory text (grey), or a problem message (red) when [problem]. */
@Composable
private fun Hint(text: String, problem: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (problem) MaterialTheme.colorScheme.error else DotGrey,
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = DotWhite,
            modifier = Modifier.weight(1f).padding(end = 12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun DashboardScreen(
    ui: UiState,
    onScan: () -> Unit,
    onConnect: (BleClient.Entry) -> Unit,
    onReconnectSaved: () -> Unit,
    onDisconnect: () -> Unit,
    onSyncHealthConnect: () -> Unit,
    onToggleAutoSync: (Boolean) -> Unit,
    onSetInterval: (Int) -> Unit,
    onRingWatch: () -> Unit,
    onStopRing: () -> Unit,
    onToggleFindBackground: (Boolean) -> Unit,
    onToggleNotifRelay: (Boolean) -> Unit,
    onOpenNotifAccess: () -> Unit,
    onOpenNotifApps: () -> Unit,
    onPickGpx: () -> Unit,
) {
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // DotOS wordmark: a red dot + "DotOS", with the app role as a spaced-out
        // grey caption underneath — the watch's charter, on the phone.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(DotRed),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "DotOS",
                style = MaterialTheme.typography.headlineSmall,
                color = DotWhite,
            )
        }
        Text("COMPANION", style = DotCaption, color = DotGrey)

        // ---- Watch connection ----------------------------------------------------
        SectionCard("Watch") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (ui.connected) DotRed else DotGrey.copy(alpha = 0.5f)),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (ui.connected) "Connected" else "Not connected",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DotWhite,
                )
            }
            Hint(ui.status)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onScan) { Text("Scan") }
                OutlinedButton(onClick = onDisconnect, enabled = ui.connected) { Text("Disconnect") }
            }

            // Direct reconnect by the saved MAC — no scan needed. Only useful once a
            // watch has been paired, and pointless while already connected.
            if (ui.hasWatchAddr && !ui.connected) {
                OutlinedButton(
                    onClick = onReconnectSaved,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = DotWhite),
                    border = BorderStroke(1.dp, DotRed),
                ) { Text("Reconnect saved watch") }
            }

            if (ui.devices.isNotEmpty() && !ui.connected) {
                Text("Devices found", style = MaterialTheme.typography.bodySmall, color = DotGrey)
                ui.devices.forEach { e ->
                    if (e.isWatch) {
                        // The watch: filled red, so the accent is reserved for
                        // the one device that matters.
                        Button(
                            onClick = { onConnect(e) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("${e.name}  -  ${e.address}") }
                    } else {
                        // Everything else: white text on a grey outline, not red.
                        OutlinedButton(
                            onClick = { onConnect(e) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = DotWhite),
                            border = BorderStroke(1.dp, DotGrey.copy(alpha = 0.4f)),
                        ) { Text("${e.name}  -  ${e.address}") }
                    }
                }
            }
        }

        // ---- Health ------------------------------------------------------------------
        SectionCard("Health") {
            Hint(
                if (ui.hcAvailable) "Steps, heart rate and a sleep score from Health Connect, sent to the watch."
                else "Health Connect is not available on this phone (install or enable it).",
            )
            if (ui.lastSync.isNotEmpty()) {
                Text("Last sync: ${ui.lastSync}", style = MaterialTheme.typography.bodyMedium, color = DotWhite)
                if (ui.lastSyncDetail.isNotEmpty()) Hint(ui.lastSyncDetail)
            } else {
                Hint("Not synced yet.")
            }
            Button(
                onClick = onSyncHealthConnect,
                enabled = ui.connected && ui.hcAvailable,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Sync now") }

            SwitchRow("Auto-sync in the background", ui.autoSyncOn, onToggleAutoSync)
            Hint("Reads Health Connect on its own and pushes to the watch, reconnecting by the saved address.")
            if (!ui.hasWatchAddr) {
                Hint("No watch paired yet - Scan and connect once to enable this.", problem = true)
            }
            Text("Interval", style = MaterialTheme.typography.bodySmall, color = DotGrey)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15, 30, 60).forEach { m ->
                    if (m == ui.intervalMin) {
                        Button(onClick = { onSetInterval(m) }) { Text("$m min") }
                    } else {
                        OutlinedButton(onClick = { onSetInterval(m) }) { Text("$m min") }
                    }
                }
            }
        }

        // ---- Notifications -------------------------------------------------------------
        SectionCard("Notifications") {
            SwitchRow("Phone notifications on watch", ui.notifRelayOn, onToggleNotifRelay)
            Hint(
                "Forwards your phone's notifications (calls, messages, apps) to the watch as " +
                    "they arrive. Needs notification access, granted once.",
            )
            if (!ui.hasWatchAddr) {
                Hint("No watch paired yet - Scan and connect once to enable this.", problem = true)
            }
            if (ui.notifRelayOn && !ui.notifAccessGranted) {
                Hint("Notification access not granted yet.", problem = true)
                Button(
                    onClick = onOpenNotifAccess,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Grant notification access") }
            } else if (ui.notifRelayOn && ui.notifAccessGranted) {
                Hint("Access granted - notifications are being forwarded.")
            }
            OutlinedButton(
                onClick = onOpenNotifApps,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = DotWhite),
                border = BorderStroke(1.dp, DotGrey.copy(alpha = 0.4f)),
            ) {
                Text(if (ui.mutedApps == 0) "Choose apps" else "Choose apps (${ui.mutedApps} muted)")
            }
        }

        // ---- Find ----------------------------------------------------------------------
        SectionCard("Find") {
            Hint("Ring the watch from here; the watch can also ring this phone.")
            Button(
                onClick = onRingWatch,
                enabled = ui.connected,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Ring watch") }
            if (ui.phoneRinging) {
                Button(
                    onClick = onStopRing,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Stop ringing") }
            }
            SwitchRow("Ring even when app is closed", ui.findBackgroundOn, onToggleFindBackground)
            if (ui.findBackgroundOn) {
                Hint(
                    "Keeps a background link (a persistent notification) so the watch " +
                        "can ring this phone at full volume with the app closed.",
                )
            }
        }

        // ---- Routes --------------------------------------------------------------------
        SectionCard("Routes") {
            Hint(
                "Send a GPX route (for example exported from Gaia GPS) to the watch, then " +
                    "follow it from Apps > GPX Track. You can also share a .gpx from any app " +
                    "straight to this one.",
            )
            Button(
                onClick = onPickGpx,
                enabled = ui.hasWatchAddr,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Send a GPX route") }
            if (!ui.hasWatchAddr) {
                Hint("No watch paired yet - Scan and connect once first.", problem = true)
            }
        }

        if (ui.versionName.isNotEmpty()) {
            Text("DotOS Dashboard  v${ui.versionName}", style = DotCaption, color = DotGrey)
        }
        Spacer(Modifier.height(16.dp))
    }
}
