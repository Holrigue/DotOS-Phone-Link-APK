package com.holrigue.argdos.dashboard

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Streams a .gpx route to the watch's GPX-in characteristic (a2470004 under the
 * ANS service) so a route shared from Gaia GPS lands in /gpx on the watch.
 *
 * Wire protocol (matches firmware src/ans.cpp):
 *   [0x01][nameLen][name…]   BEGIN
 *   [0x02][bytes…]           DATA  (in order; write-with-response guarantees it)
 *   [0x03]                   END
 *
 * Connects by MAC (no scan — allowed in the background), negotiates a larger MTU
 * so each DATA frame carries a useful chunk, then writes the frames one at a time,
 * each awaiting its onCharacteristicWrite before sending the next.
 */
@SuppressLint("MissingPermission")
object GpxPush {
    private val ANS_UUID: UUID = UUID.fromString("00001811-0000-1000-8000-00805f9b34fb")
    private val SERVICE_UUID: UUID = UUID.fromString("a2470001-5a4b-4d55-9a3e-1c2d3e4f5a6b")
    private val GPX_UUID: UUID = UUID.fromString("a2470004-5a4b-4d55-9a3e-1c2d3e4f5a6b")

    private const val REQ_MTU = 247
    private const val DEFAULT_MTU = 23
    private const val OP_BEGIN = 0x01
    private const val OP_DATA = 0x02
    private const val OP_END = 0x03

    /** @return true if the whole file was written and acknowledged. */
    suspend fun push(
        context: Context,
        address: String,
        name: String,
        bytes: ByteArray,
        timeoutMs: Long = 60_000,
    ): Boolean {
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return false
        val adapter = mgr.adapter ?: return false
        if (adapter.isEnabled != true) return false
        val device = try { adapter.getRemoteDevice(address) } catch (e: IllegalArgumentException) { return false }
        return withTimeoutOrNull(timeoutMs) { pushGatt(context, device, name, bytes) } ?: false
    }

    private suspend fun pushGatt(
        context: Context,
        device: android.bluetooth.BluetoothDevice,
        name: String,
        bytes: ByteArray,
    ): Boolean = suspendCancellableCoroutine { cont ->
        val done = AtomicBoolean(false)
        val frames = ArrayDeque<ByteArray>()
        var ch: BluetoothGattCharacteristic? = null
        var mtu = DEFAULT_MTU

        fun finish(ok: Boolean, g: BluetoothGatt?) {
            if (done.compareAndSet(false, true)) {
                try { g?.disconnect(); g?.close() } catch (_: Exception) {}
                if (cont.isActive) cont.resume(ok)
            }
        }

        fun buildFrames() {
            frames.clear()
            // BEGIN: opcode + name length + name bytes (cap the name to stay in one frame).
            val nameBytes = name.toByteArray(Charsets.UTF_8).let { if (it.size > 40) it.copyOfRange(0, 40) else it }
            val begin = ByteArray(2 + nameBytes.size)
            begin[0] = OP_BEGIN.toByte()
            begin[1] = nameBytes.size.toByte()
            System.arraycopy(nameBytes, 0, begin, 2, nameBytes.size)
            frames.addLast(begin)
            // DATA: chunk to (mtu - 3 ATT header - 1 opcode).
            val chunk = (mtu - 4).coerceIn(20, 512)
            var off = 0
            while (off < bytes.size) {
                val n = minOf(chunk, bytes.size - off)
                val f = ByteArray(1 + n)
                f[0] = OP_DATA.toByte()
                System.arraycopy(bytes, off, f, 1, n)
                frames.addLast(f)
                off += n
            }
            frames.addLast(byteArrayOf(OP_END.toByte()))
        }

        fun writeNext(g: BluetoothGatt) {
            val f = frames.pollFirst()
            if (f == null) { finish(true, g); return }   // all frames acknowledged
            val c = ch ?: run { finish(false, g); return }
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(c, f, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                    BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION") run {
                    c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    c.value = f
                    g.writeCharacteristic(c)
                }
            }
            if (!ok) finish(false, g)
        }

        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    try { g.javaClass.getMethod("refresh").invoke(g) } catch (_: Exception) {}
                    g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    finish(false, g)
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                ch = g.getService(ANS_UUID)?.getCharacteristic(GPX_UUID)
                    ?: g.getService(SERVICE_UUID)?.getCharacteristic(GPX_UUID)
                if (ch == null) { finish(false, g); return }
                if (!g.requestMtu(REQ_MTU)) { mtu = DEFAULT_MTU; buildFrames(); writeNext(g) }
            }

            override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
                mtu = if (status == BluetoothGatt.GATT_SUCCESS && newMtu >= DEFAULT_MTU) newMtu else DEFAULT_MTU
                buildFrames()
                writeNext(g)
            }

            @Suppress("DEPRECATION")
            override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) { finish(false, g); return }
                writeNext(g)
            }
        }

        val gatt = device.connectGatt(context, false, cb, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
        if (gatt == null) { if (cont.isActive) cont.resume(false); return@suspendCancellableCoroutine }
        cont.invokeOnCancellation { finish(false, gatt) }
    }
}
