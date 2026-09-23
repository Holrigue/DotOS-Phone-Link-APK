package com.holrigue.argdos.dashboard

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
 * V1.0 - a foreground test harness. Scan, connect to the watch, and push
 * hand-set health values so the whole app -> watch BLE path (and the two-central
 * question, while Gadgetbridge is connected) can be validated before any
 * Gadgetbridge reading is wired in (V1.1).
 */
class MainActivity : ComponentActivity() {

    private lateinit var ble: BleClient

    private val ui = UiState()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val ok = grants.values.all { it }
            ui.status = if (ok) "Permissions granted" else "Permissions denied - BLE needs them"
            if (ok) ble.startScan()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ble = BleClient(this)
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

    override fun onDestroy() {
        super.onDestroy()
        ble.disconnect()
    }
}

/** Observable UI state (Compose-friendly). */
class UiState {
    var status by mutableStateOf("Idle")
    var connected by mutableStateOf(false)
    val devices = mutableStateListOf<BleClient.Entry>()
}

@Composable
private fun DashboardScreen(
    ui: UiState,
    onScan: () -> Unit,
    onConnect: (BleClient.Entry) -> Unit,
    onDisconnect: () -> Unit,
    onSend: (ByteArray) -> Unit,
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
