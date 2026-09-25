package com.holrigue.argdos.dashboard

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * One-shot, fire-and-forget BLE write used by the background auto-sync: connect
 * to a known watch by its MAC (no scan - scanning is restricted in the
 * background, but a direct connect by address is allowed), refresh the cached
 * GATT table, find the health characteristic under the ANS service (with the
 * legacy standalone service as a fallback), write one packet, and disconnect.
 *
 * It is a single suspend call so the WorkManager worker can await the result and
 * report success/failure. Permissions (BLUETOOTH_CONNECT) are the caller's
 * responsibility.
 */
@SuppressLint("MissingPermission")
object BlePush {
    private val ANS_UUID: UUID = UUID.fromString("00001811-0000-1000-8000-00805f9b34fb")
    private val SERVICE_UUID: UUID = UUID.fromString("a2470001-5a4b-4d55-9a3e-1c2d3e4f5a6b")
    private val CHAR_UUID: UUID = UUID.fromString("a2470002-5a4b-4d55-9a3e-1c2d3e4f5a6b")

    suspend fun push(
        context: Context,
        address: String,
        packet: ByteArray,
        timeoutMs: Long = 20_000,
    ): Boolean {
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return false
        val adapter = mgr.adapter ?: return false
        if (adapter.isEnabled != true) return false
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            return false
        }
        return withTimeoutOrNull(timeoutMs) { pushGatt(context, device, packet) } ?: false
    }

    private suspend fun pushGatt(
        context: Context,
        device: android.bluetooth.BluetoothDevice,
        packet: ByteArray,
    ): Boolean = suspendCancellableCoroutine { cont ->
        val done = AtomicBoolean(false)
        var gattRef: BluetoothGatt? = null

        fun finish(ok: Boolean, g: BluetoothGatt?) {
            if (done.compareAndSet(false, true)) {
                try { g?.disconnect(); g?.close() } catch (_: Exception) {}
                if (cont.isActive) cont.resume(ok)
            }
        }

        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    refreshCache(g)
                    g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    finish(false, g)   // no-op if we already resumed
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val ch = g.getService(ANS_UUID)?.getCharacteristic(CHAR_UUID)
                    ?: g.getService(SERVICE_UUID)?.getCharacteristic(CHAR_UUID)
                if (ch == null || !writeChar(g, ch, packet)) finish(false, g)
            }

            @Suppress("DEPRECATION")
            override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
                finish(status == BluetoothGatt.GATT_SUCCESS, g)
            }
        }

        gattRef = device.connectGatt(context, false, cb, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
        if (gattRef == null) { if (cont.isActive) cont.resume(false); return@suspendCancellableCoroutine }
        cont.invokeOnCancellation { finish(false, gattRef) }
    }

    @Suppress("DEPRECATION")
    private fun writeChar(g: BluetoothGatt, ch: BluetoothGattCharacteristic, packet: ByteArray): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, packet, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothGatt.GATT_SUCCESS
        } else {
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ch.value = packet
            g.writeCharacteristic(ch)
        }
    }

    // Clear Android's cached GATT table via the hidden refresh() method, so a
    // firmware change to the services is picked up (same as the interactive path).
    private fun refreshCache(g: BluetoothGatt) {
        try {
            @Suppress("DiscouragedPrivateApi")
            g.javaClass.getMethod("refresh").invoke(g)
        } catch (_: Exception) {
        }
    }
}
