package com.holrigue.argdos.dashboard

import android.annotation.SuppressLint
import android.app.Notification
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.ArrayDeque
import java.util.UUID

/**
 * Forwards the phone's notifications to the watch.
 *
 * The watch already runs an Alert Notification Service (ANS, 0x1811) with the
 * standard "New Alert" write characteristic (0x2A46) — the same service the
 * health packet is written to. Health worked because the app wrote it; phone
 * notifications did not, because nothing captured them. This service closes that
 * gap: it is a NotificationListenerService (the only way Android lets an app read
 * other apps' notifications), and it holds its OWN background BLE link to the
 * saved watch so each notification is pushed the moment it is posted.
 *
 * The link is independent of FindService (that one carries the Find channel):
 * multiple GATT clients from one app share a single physical connection, so this
 * costs nothing extra on air. autoConnect = true lets Android silently
 * re-establish it whenever the watch is back in range.
 *
 * Enable/disable is the [Prefs.notifRelayEnabled] flag; the user must also grant
 * notification access in system settings (MainActivity deep-links there).
 */
@SuppressLint("MissingPermission")
class NotificationRelayService : NotificationListenerService() {

    companion object {
        private val ANS_UUID: UUID = UUID.fromString("00001811-0000-1000-8000-00805f9b34fb")
        private val SERVICE_UUID: UUID = UUID.fromString("a2470001-5a4b-4d55-9a3e-1c2d3e4f5a6b")
        private val NEW_ALERT_UUID: UUID = UUID.fromString("00002a46-0000-1000-8000-00805f9b34fb")

        // Requested ATT MTU. The default 23 leaves only 20 payload bytes, far too
        // little for a title + body; 185 covers a full notification line in one
        // write. We cap each packet at the value the watch actually grants.
        private const val REQ_MTU = 185
        private const val DEFAULT_MTU = 23
        private const val MAX_QUEUE = 16   // drop the oldest if notifications burst
    }

    private var gatt: BluetoothGatt? = null
    private var newAlert: BluetoothGattCharacteristic? = null
    private var mtu = DEFAULT_MTU
    private var ready = false
    private var writeInFlight = false
    private val queue = ArrayDeque<ByteArray>()
    // GATT callbacks and onNotificationPosted arrive on different threads; this
    // guards the queue + write flags shared between them.
    private val lock = Any()

    override fun onListenerConnected() {
        // OS bound us (access granted). Open the link now if the feature is on.
        if (Prefs.notifRelayEnabled(this)) ensureConnected()
    }

    override fun onListenerDisconnected() {
        closeLink()
    }

    override fun onDestroy() {
        closeLink()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!Prefs.notifRelayEnabled(this)) return
        if (!shouldRelay(sbn)) return

        val n = sbn.notification ?: return
        val extras = n.extras ?: return
        val title = (extras.getCharSequence(Notification.EXTRA_TITLE) ?: "").toString().trim()
        var text = (extras.getCharSequence(Notification.EXTRA_TEXT) ?: "").toString().trim()
        if (text.isEmpty()) {
            text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: "").toString().trim()
        }
        // Nothing readable (e.g. a pure media/progress notification): skip it.
        if (title.isEmpty() && text.isEmpty()) return

        val packet = NotifPacket.build(categoryFor(sbn), title, text, mtu)
        enqueue(packet)
    }

    // ---- Filtering -----------------------------------------------------------
    private fun shouldRelay(sbn: StatusBarNotification): Boolean {
        // Never relay our own notifications (the Find link / ring), or the OS
        // would echo them back to the watch.
        if (sbn.packageName == packageName) return false
        val n = sbn.notification ?: return false

        // Skip ongoing/foreground-service chrome (music transport bars, "app is
        // running", downloads) and group summaries (the children carry the text).
        if (!sbn.isClearable) return false
        val flags = n.flags
        if (flags and Notification.FLAG_ONGOING_EVENT != 0) return false
        if (flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return false
        return true
    }

    // Map the Android notification category to an ANS CategoryID the firmware
    // understands. Falls back to a generic alert when the app sets none.
    private fun categoryFor(sbn: StatusBarNotification): Int {
        return when (sbn.notification?.category) {
            Notification.CATEGORY_CALL -> NotifPacket.CAT_CALL
            Notification.CATEGORY_MISSED_CALL -> NotifPacket.CAT_MISSED_CALL
            Notification.CATEGORY_EMAIL -> NotifPacket.CAT_EMAIL
            Notification.CATEGORY_MESSAGE -> NotifPacket.CAT_SMS
            Notification.CATEGORY_SOCIAL -> NotifPacket.CAT_IM
            Notification.CATEGORY_EVENT, Notification.CATEGORY_REMINDER -> NotifPacket.CAT_SCHEDULE
            Notification.CATEGORY_NEWS, Notification.CATEGORY_PROMO -> NotifPacket.CAT_NEWS
            else -> NotifPacket.CAT_SIMPLE
        }
    }

    // ---- BLE link ------------------------------------------------------------
    private fun ensureConnected() {
        if (gatt != null) return
        val addr = Prefs.watchAddress(this) ?: return
        val mgr = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return
        val adapter = mgr.adapter ?: return
        if (adapter.isEnabled != true) return
        val device = try { adapter.getRemoteDevice(addr) } catch (e: Exception) { return }
        ready = false
        mtu = DEFAULT_MTU
        // autoConnect = true: Android reconnects transparently when back in range.
        gatt = device.connectGatt(this, true, cb, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
    }

    private fun closeLink() {
        synchronized(lock) {
            ready = false
            writeInFlight = false
            newAlert = null
            queue.clear()
        }
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
        gatt = null
    }

    private fun enqueue(packet: ByteArray) {
        synchronized(lock) {
            if (queue.size >= MAX_QUEUE) queue.pollFirst()   // shed the oldest on a burst
            queue.addLast(packet)
        }
        ensureConnected()
        flush()
    }

    private fun flush() {
        synchronized(lock) {
            if (!ready || writeInFlight) return
            val ch = newAlert ?: return
            val packet = queue.pollFirst() ?: return
            writeInFlight = true
            val ok = writeChar(ch, packet)
            if (!ok) {
                // Write couldn't be issued; put it back and wait for the next trigger.
                writeInFlight = false
                queue.addFirst(packet)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun writeChar(ch: BluetoothGattCharacteristic, packet: ByteArray): Boolean {
        val g = gatt ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, packet, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothGatt.GATT_SUCCESS
        } else {
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ch.value = packet
            g.writeCharacteristic(ch)
        }
    }

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                try { g.javaClass.getMethod("refresh").invoke(g) } catch (_: Exception) {}
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                // Keep the GATT object so autoConnect re-establishes the link; just
                // mark it not-ready so we don't write into the void meanwhile.
                synchronized(lock) {
                    ready = false
                    writeInFlight = false
                    newAlert = null
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val ch = g.getService(ANS_UUID)?.getCharacteristic(NEW_ALERT_UUID)
                ?: g.getService(SERVICE_UUID)?.getCharacteristic(NEW_ALERT_UUID)
            synchronized(lock) { newAlert = ch }
            if (ch == null) return
            // Grow the MTU before writing; onMtuChanged flips us ready + flushes.
            if (!g.requestMtu(REQ_MTU)) {
                // Some stacks refuse the request; proceed at the default MTU.
                synchronized(lock) { mtu = DEFAULT_MTU; ready = true }
                flush()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            synchronized(lock) {
                mtu = if (status == BluetoothGatt.GATT_SUCCESS && newMtu >= DEFAULT_MTU) newMtu else DEFAULT_MTU
                ready = true
            }
            flush()
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            synchronized(lock) { writeInFlight = false }
            flush()   // send the next queued alert, if any
        }
    }
}
