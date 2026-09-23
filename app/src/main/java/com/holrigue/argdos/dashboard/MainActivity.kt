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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ble = BleClient(this)
        hc = HealthConnectSource(this)
        ui.hcAvailable = hc.isAvailable()
        ble.listener = object : BleClient.Listener {
            override fun onScanResult(devices: List<BleClient.Entry>) {
                ui.devices.clear(); ui.devices.addAll(devices)
            }
            override fun onStatus(status: String, connected: Boolean) {
                ui.status = status; ui.connected = connected
            }
            override fun onWriteResult(ok: Boolean) {
                ui.status = if (ok) "Packet sent ✓" else "Write failed"
            }
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DashboardScreen(
                        ui = ui,
                        onScan = { ensurePermissionsThenScan() },
                        onConnect = { ble.connect(it.device) },
                        onDisconnect = { ble.disconnect() },
                        onSend = { ble.write(it) },
                        onSyncHealthConnect = { startHealthConnectSync() },
                    )
                }
            }
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
                hrBpm = snap.hrBpm,
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
}

@Composable
private fun DashboardScreen(
    ui: UiState,
    onScan: () -> Unit,
    onConnect: (BleClient.Entry) -> Unit,
    onDisconnect: () -> Unit,
    onSend: (ByteArray) -> Unit,
    onSyncHealthConnect: () -> Unit,
) {
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("ARGD-OS Dashboard", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Status: ${ui.status}", style = MaterialTheme.typography.bodyMedium)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onScan) { Text("Scan") }
            OutlinedButton(onClick = onDisconnect, enabled = ui.connected) { Text("Disconnect") }
        }

        if (ui.devices.isNotEmpty() && !ui.connected) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text("Devices", fontWeight = FontWeight.Bold)
                    ui.devices.forEach { e ->
                        OutlinedButton(
                            onClick = { onConnect(e) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("${e.name}  -  ${e.address}") }
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
                    hrBpm = if (heart.on) heart.value else null,
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
