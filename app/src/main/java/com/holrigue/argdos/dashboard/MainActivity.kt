package com.holrigue.argdos.dashboard

import android.Manifest
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.content.Context
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Scan and connect to the watch, then either push hand-set test values or sync
 * real metrics from Health Connect (steps, heart rate, computed sleep score),
 * writing them to the watch's health characteristic. Stress is left out - Health
 * Connect has no stress type.
 */
class MainActivity : ComponentActivity() {

    private lateinit var ble: BleClient
    private lateinit var hc: HealthConnectSource

    private val ui = UiState()
    private val handler = Handler(Looper.getMainLooper())

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ble = BleClient(this)
        hc = HealthConnectSource(this)
        ui.hcAvailable = hc.isAvailable()
        ui.autoSyncOn = Prefs.autoSyncEnabled(this)
        ui.intervalMin = Prefs.intervalMinutes(this)
        ui.hasWatchAddr = Prefs.watchAddress(this) != null
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
                        onSend = { ble.write(it) },
                        onSyncHealthConnect = { startHealthConnectSync() },
                        onToggleAutoSync = { toggleAutoSync(it) },
                        onSetInterval = { setSyncInterval(it) },
                        onRingWatch = { ringWatch() },
                        onStopRing = { stopPhoneRing() },
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
    private var ringtone: Ringtone? = null
    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }
    private val stopRingRunnable = Runnable { stopPhoneRing() }

    // Phone -> watch: ask the watch to ring.
    private fun ringWatch() {
        if (!ble.canFind()) {
            ui.status = "Connect to the watch first (Find needs the live link)"
            return
        }
        val ok = ble.ringWatch(true)
        ui.status = if (ok) "Ringing the watch..." else "Couldn't reach the watch"
    }

    // Watch -> phone: ring this phone (alarm tone + vibrate) until stopped or a
    // safety timeout, so a call from the watch can't leave it ringing forever.
    private fun startPhoneRing() {
        if (ui.phoneRinging) return
        try {
            val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(this, uri)?.apply {
                streamType = AudioManager.STREAM_ALARM
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
                play()
            }
        } catch (_: Exception) {}
        try {
            val pattern = longArrayOf(0, 600, 400)
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } catch (_: Exception) {}
        ui.phoneRinging = true
        ui.status = "Watch is ringing your phone"
        handler.removeCallbacks(stopRingRunnable)
        handler.postDelayed(stopRingRunnable, 60_000)   // safety auto-stop
    }

    private fun stopPhoneRing() {
        handler.removeCallbacks(stopRingRunnable)
        try { ringtone?.stop() } catch (_: Exception) {}
        ringtone = null
        try { vibrator?.cancel() } catch (_: Exception) {}
        ui.phoneRinging = false
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
            ui.hcDetail = snap.detail
            val packet = HealthPacket.build(
                sleepScore = snap.sleepScore,
                steps = snap.steps,
                stress = null,          // Health Connect has no stress type
                hrLow = snap.hrLow,
                hrHigh = snap.hrHigh,
            )
            val ok = ble.write(packet)
            ui.status = if (ok) "Sent to watch ✓  (${snap.detail})" else "Write failed"
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
    var hcDetail by mutableStateOf("")
    val devices = mutableStateListOf<BleClient.Entry>()
    // Auto-sync
    var autoSyncOn by mutableStateOf(false)
    var intervalMin by mutableIntStateOf(Prefs.DEFAULT_INTERVAL_MIN)
    var hasWatchAddr by mutableStateOf(false)
    // Find
    var phoneRinging by mutableStateOf(false)
}

@Composable
private fun DashboardScreen(
    ui: UiState,
    onScan: () -> Unit,
    onConnect: (BleClient.Entry) -> Unit,
    onReconnectSaved: () -> Unit,
    onDisconnect: () -> Unit,
    onSend: (ByteArray) -> Unit,
    onSyncHealthConnect: () -> Unit,
    onToggleAutoSync: (Boolean) -> Unit,
    onSetInterval: (Int) -> Unit,
    onRingWatch: () -> Unit,
    onStopRing: () -> Unit,
) {
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // DotOS wordmark: a red dot + "DotOS", with the app role as a spaced-out
        // grey caption underneath — the watch's charter, on the phone.
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
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
        Text("HEALTH DASHBOARD", style = DotCaption, color = DotGrey)
        Spacer(Modifier.height(4.dp))
        Text("Status: ${ui.status}", style = MaterialTheme.typography.bodyMedium, color = DotGrey)

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
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text("Devices", fontWeight = FontWeight.Bold, color = DotWhite)
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
        }

        Divider()
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Health Connect", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (ui.hcAvailable) "Reads steps, heart rate and a sleep score, then sends them."
                    else "Not available on this phone (install/enable Health Connect).",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (ui.hcDetail.isNotEmpty()) {
                    Text("Last read: ${ui.hcDetail}", style = MaterialTheme.typography.bodySmall)
                }
                Button(
                    onClick = onSyncHealthConnect,
                    enabled = ui.connected && ui.hcAvailable,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) { Text("Sync from Health Connect") }
            }
        }

        Divider()
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Find", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Ring the watch from here; the watch can also ring this phone.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = onRingWatch,
                    enabled = ui.connected,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) { Text("Ring watch") }
                if (ui.phoneRinging) {
                    Button(
                        onClick = onStopRing,
                        colors = ButtonDefaults.buttonColors(containerColor = DotRed),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) { Text("Stop ringing") }
                }
            }
        }

        Divider()
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text("Auto-sync (background)", style = MaterialTheme.typography.titleMedium)
                    Switch(checked = ui.autoSyncOn, onCheckedChange = onToggleAutoSync)
                }
                Text(
                    "Periodically reads Health Connect and pushes to the watch on its own. " +
                        "Reconnects by the saved address, so connect once first.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (!ui.hasWatchAddr) {
                    Text(
                        "No watch paired yet - Scan and connect once to enable this.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    "Interval",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
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
        }

        Divider()
        Text("Test values", style = MaterialTheme.typography.titleMedium)

        val sleep = remember { MetricState(true, 82, 0, 100) }
        val steps = remember { MetricState(true, 7500, 0, 30000) }
        val stress = remember { MetricState(true, 35, 0, 100) }
        val heart = remember { MetricState(true, 68, 0, 220) }

        MetricRow("Sleep score", sleep)
        MetricRow("Steps", steps)
        MetricRow("Stress", stress)
        MetricRow("Heart (bpm)", heart)

        Button(
            onClick = {
                val packet = HealthPacket.build(
                    sleepScore = if (sleep.on) sleep.value else null,
                    steps = if (steps.on) steps.value else null,
                    stress = if (stress.on) stress.value else null,
                    // Test path sends the one slider value as both low and high.
                    hrLow = if (heart.on) heart.value else null,
                    hrHigh = if (heart.on) heart.value else null,
                )
                onSend(packet)
            },
            enabled = ui.connected,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Send to watch") }

        Spacer(Modifier.height(24.dp))
    }
}

private class MetricState(on: Boolean, value: Int, val min: Int, val max: Int) {
    var on by mutableStateOf(on)
    var value by mutableIntStateOf(value)
}

@Composable
private fun MetricRow(label: String, state: MetricState) {
    Column {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(checked = state.on, onCheckedChange = { state.on = it })
            Text("$label: ${state.value}")
        }
        Slider(
            value = state.value.toFloat(),
            onValueChange = { state.value = it.toInt() },
            valueRange = state.min.toFloat()..state.max.toFloat(),
            enabled = state.on,
        )
    }
}
