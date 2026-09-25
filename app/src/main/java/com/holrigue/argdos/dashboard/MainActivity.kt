package com.holrigue.argdos.dashboard

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
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
        }

        setContent {
            DotTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DashboardScreen(
                        ui = ui,
                        onScan = { ensurePermissionsThenScan() },
                        onConnect = { ble.connect(it.device) },
                        onDisconnect = { ble.disconnect() },
                        onSend = { ble.write(it) },
                        onSyncHealthConnect = { startHealthConnectSync() },
                        onToggleAutoSync = { toggleAutoSync(it) },
                        onSetInterval = { setSyncInterval(it) },
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
}

@Composable
private fun DashboardScreen(
    ui: UiState,
    onScan: () -> Unit,
    onConnect: (BleClient.Entry) -> Unit,
    onDisconnect: () -> Unit,
    onSend: (ByteArray) -> Unit,
    onSyncHealthConnect: () -> Unit,
    onToggleAutoSync: (Boolean) -> Unit,
    onSetInterval: (Int) -> Unit,
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

        if (ui.devices.isNotEmpty() && !ui.connected) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text("Devices", fontWeight = FontWeight.Bold)
                    ui.devices.forEach { e ->
                        if (e.isWatch) {
                            Button(
                                onClick = { onConnect(e) },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("${e.name}  -  ${e.address}") }
                        } else {
                            OutlinedButton(
                                onClick = { onConnect(e) },
                                modifier = Modifier.fillMaxWidth(),
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
