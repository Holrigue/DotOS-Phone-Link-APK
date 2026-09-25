package com.holrigue.argdos.dashboard

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import java.util.UUID

/**
 * Keeps a background BLE link to the saved watch so the watch can ring this
 * phone (Find) even when the app UI is closed. It runs as a foreground service
 * (Android requires one to hold a connection and post an alarm while in the
 * background) and hands off with the in-app path: MainActivity stops this while
 * it is visible and (re)starts it when it leaves, so only one owns the link.
 */
@SuppressLint("MissingPermission")
class FindService : Service() {

    companion object {
        const val ACTION_STOP_RING = "com.holrigue.argdos.dashboard.STOP_RING"
        private const val CH_LINK = "watch_link"
        private const val CH_RING = "watch_ring"
        private const val NID_LINK = 1
        private const val NID_RING = 2

        private val ANS_UUID: UUID = UUID.fromString("00001811-0000-1000-8000-00805f9b34fb")
        private val SERVICE_UUID: UUID = UUID.fromString("a2470001-5a4b-4d55-9a3e-1c2d3e4f5a6b")
        private val FIND_UUID: UUID = UUID.fromString("a2470003-5a4b-4d55-9a3e-1c2d3e4f5a6b")
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        fun start(context: Context) {
            val i = Intent(context, FindService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }
        fun stop(context: Context) {
            context.stopService(Intent(context, FindService::class.java))
        }
    }

    private var gatt: BluetoothGatt? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        startForegroundLink()
        connect()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_RING) {
            PhoneRinger.stop(this)
            nm().cancel(NID_RING)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
        gatt = null
        PhoneRinger.stop(this)
        nm().cancel(NID_RING)
        super.onDestroy()
    }

    // ---- BLE -----------------------------------------------------------------
    private fun connect() {
        val addr = Prefs.watchAddress(this) ?: run { stopSelf(); return }
        val mgr = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = mgr?.adapter ?: run { stopSelf(); return }
        val device = try { adapter.getRemoteDevice(addr) } catch (e: Exception) { stopSelf(); return }
        // autoConnect = true: Android transparently reconnects whenever the watch
        // comes back in range, which is exactly what a background link wants.
        gatt = device.connectGatt(this, true, cb, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
    }

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                try { g.javaClass.getMethod("refresh").invoke(g) } catch (_: Exception) {}
                g.discoverServices()
            }
            // On disconnect we rely on autoConnect=true to re-establish the link.
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val fc = g.getService(ANS_UUID)?.getCharacteristic(FIND_UUID)
                ?: g.getService(SERVICE_UUID)?.getCharacteristic(FIND_UUID) ?: return
            try {
                g.setCharacteristicNotification(fc, true)
                val cccd = fc.getDescriptor(CCCD_UUID) ?: return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION") run {
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        g.writeDescriptor(cccd)
                    }
                }
            } catch (_: Exception) {}
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (ch.uuid != FIND_UUID) return
            val op = ch.value?.firstOrNull()?.toInt() ?: return
            if (op != 0) { PhoneRinger.start(this@FindService); postRingNotification() }
            else         { PhoneRinger.stop(this@FindService);  nm().cancel(NID_RING) }
        }
    }

    // ---- Notifications -------------------------------------------------------
    private fun nm() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        nm().createNotificationChannel(
            NotificationChannel(CH_LINK, "Watch link", NotificationManager.IMPORTANCE_MIN))
        nm().createNotificationChannel(
            NotificationChannel(CH_RING, "Find ringing", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Rings this phone when the watch calls it"
            })
    }

    private fun startForegroundLink() {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = notifBuilder(CH_LINK)
            .setContentTitle("Watch link active")
            .setContentText("Ready to ring your phone from the watch")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NID_LINK, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NID_LINK, n)
        }
    }

    private fun postRingNotification() {
        val stopIntent = PendingIntent.getService(
            this, 0, Intent(this, FindService::class.java).setAction(ACTION_STOP_RING),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = notifBuilder(CH_RING)
            .setContentTitle("Your watch is ringing this phone")
            .setContentText("Find")
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setCategory(Notification.CATEGORY_ALARM)
            .setPriority(Notification.PRIORITY_MAX)
            .setFullScreenIntent(open, true)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_lock_silent_mode, "Stop", stopIntent)
            .build()
        nm().notify(NID_RING, n)
    }

    private fun notifBuilder(channel: String): Notification.Builder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, channel)
        else @Suppress("DEPRECATION") Notification.Builder(this)
}
