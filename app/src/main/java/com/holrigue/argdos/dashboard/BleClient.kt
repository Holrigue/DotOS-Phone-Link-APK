package com.holrigue.argdos.dashboard

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.UUID

/**
 * Minimal BLE client for the watch's vendor health-input service.
 *
 * Scans for nearby devices (the watch advertises as "InfiniTime"), connects to
 * one, discovers the health service, and writes packets to the write
 * characteristic. All state is delivered on the main thread via [listener].
 *
 * Permissions (BLUETOOTH_SCAN / BLUETOOTH_CONNECT on API 31+, or the legacy
 * Bluetooth + fine-location set below) are the caller's responsibility; this
 * class assumes they are already granted, hence the MissingPermission suppress.
 */
@SuppressLint("MissingPermission")
class BleClient(private val context: Context) {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("a2470001-5a4b-4d55-9a3e-1c2d3e4f5a6b")
        val CHAR_UUID: UUID = UUID.fromString("a2470002-5a4b-4d55-9a3e-1c2d3e4f5a6b")
        // The watch advertises the standard Alert Notification Service (0x1811)
        // while its Android/Notify mode is up. We use that to recognise it even
        // when Android reports its name as "(unknown)".
        private val ANS_UUID: UUID = UUID.fromString("00001811-0000-1000-8000-00805f9b34fb")
        private const val SCAN_MS = 12_000L
    }

    interface Listener {
        fun onScanResult(devices: List<Entry>)
        fun onStatus(status: String, connected: Boolean)
        fun onWriteResult(ok: Boolean)
    }

    data class Entry(
        val name: String,
        val address: String,
        val device: BluetoothDevice,
        val isWatch: Boolean = false,
    )

    var listener: Listener? = null

    private val main = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private val found = LinkedHashMap<String, Entry>()
    private var scanning = false

    fun isBluetoothOn(): Boolean = adapter?.isEnabled == true

    // ---- Scan ----------------------------------------------------------------
    fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: run {
            post { listener?.onStatus("Bluetooth unavailable", false) }
            return
        }
        found.clear()
        scanning = true
        post { listener?.onStatus("Scanning...", false) }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        // No hard filter: we scan everything and flag the watch ourselves, so a
        // watch whose service UUID sits only in the scan response is never missed.
        scanner.startScan(null, settings, scanCallback)
        main.postDelayed({ stopScan() }, SCAN_MS)
    }

    fun stopScan() {
        if (!scanning) return
        scanning = false
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        post { listener?.onStatus("Scan done (${found.size} found)", gatt != null) }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device ?: return
            val uuids = result.scanRecord?.serviceUuids
            val isWatch = uuids?.any { it == ParcelUuid(ANS_UUID) } == true
            val rawName = dev.name ?: result.scanRecord?.deviceName
            val name = when {
                isWatch -> "ARGUS Watch" + (rawName?.let { " ($it)" } ?: "")
                rawName != null -> rawName
                else -> "(unknown)"
            }
            found[dev.address] = Entry(name, dev.address, dev, isWatch)
            // Watches first, so the T-Watch is easy to spot among many devices.
            val sorted = found.values.sortedByDescending { it.isWatch }
            post { listener?.onScanResult(sorted) }
        }
    }

    // ---- Connect -------------------------------------------------------------
    fun connect(device: BluetoothDevice) {
        stopScan()
        post { listener?.onStatus("Connecting to ${device.address}...", false) }
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        writeChar = null
        post { listener?.onStatus("Disconnected", false) }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                post { listener?.onStatus("Connected, discovering...", false) }
                // A firmware update can change the watch's GATT table (the health
                // characteristic moved onto the ANS service). Android caches the
                // old table for a bonded device and would otherwise never see the
                // new characteristic, so clear the cache before discovering.
                refreshGattCache(g)
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                writeChar = null
                post { listener?.onStatus("Disconnected", false) }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            // The health-input characteristic now lives ON the Alert Notification
            // Service (0x1811); a separate 3rd GATT service did not register
            // reliably on the watch's BLE stack. Look for it under ANS first, then
            // fall back to the old standalone service for older firmware.
            val ch = g.getService(ANS_UUID)?.getCharacteristic(CHAR_UUID)
                ?: g.getService(SERVICE_UUID)?.getCharacteristic(CHAR_UUID)
            if (ch == null) {
                post { listener?.onStatus("Health characteristic not found on this device", true) }
                return
            }
            writeChar = ch
            post { listener?.onStatus("Ready - health characteristic found", true) }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            post { listener?.onWriteResult(status == BluetoothGatt.GATT_SUCCESS) }
        }
    }

    // ---- Write ---------------------------------------------------------------
    @Suppress("DEPRECATION")
    fun write(packet: ByteArray): Boolean {
        val g = gatt ?: return false
        val ch = writeChar ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(
                ch, packet, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ) == BluetoothGatt.GATT_SUCCESS
        } else {
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ch.value = packet
            g.writeCharacteristic(ch)
        }
    }

    // Clear Android's cached GATT service table via the hidden BluetoothGatt
    // .refresh() method (reflection). There is no public API for this; it is the
    // standard way to force re-discovery after a peripheral's services change.
    private fun refreshGattCache(g: BluetoothGatt) {
        try {
            @Suppress("DiscouragedPrivateApi")
            val refresh = g.javaClass.getMethod("refresh")
            refresh.invoke(g)
        } catch (_: Exception) {
            // Method unavailable on this platform; discovery still runs, and
            // forgetting the device in Bluetooth settings is the manual fallback.
        }
    }

    private fun post(block: () -> Unit) = main.post(block)
}
