package com.holrigue.argdos.dashboard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-starts the background Find link after a reboot, if the user enabled it and
 * a watch has been paired. Without this the "ring even when closed" feature
 * would stay dead until the app is opened again.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (Prefs.findBackgroundEnabled(context) && Prefs.watchAddress(context) != null) {
            FindService.start(context)
        }
    }
}
