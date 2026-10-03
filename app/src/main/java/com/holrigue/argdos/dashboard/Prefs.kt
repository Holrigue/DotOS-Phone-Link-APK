package com.holrigue.argdos.dashboard

import android.content.Context

/**
 * Tiny SharedPreferences store for the few things auto-sync needs to remember:
 * which watch to reconnect to (its BLE MAC, saved after a successful manual
 * connect), whether background auto-sync is on, and how often it runs.
 */
object Prefs {
    private const val FILE = "argdos_prefs"
    private const val K_ADDR = "watch_addr"
    private const val K_AUTO = "auto_sync"
    private const val K_INTERVAL = "interval_min"
    private const val K_FIND_BG = "find_background"
    private const val K_NOTIF_RELAY = "notif_relay"
    private const val K_MUTED_APPS = "notif_muted_pkgs"
    private const val K_LAST_SYNC = "last_sync_ms"
    private const val K_LAST_SYNC_DETAIL = "last_sync_detail"

    const val DEFAULT_INTERVAL_MIN = 15
    const val MIN_INTERVAL_MIN = 15   // WorkManager's floor for periodic work

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun watchAddress(c: Context): String? = sp(c).getString(K_ADDR, null)
    fun setWatchAddress(c: Context, addr: String) = sp(c).edit().putString(K_ADDR, addr).apply()

    fun autoSyncEnabled(c: Context): Boolean = sp(c).getBoolean(K_AUTO, false)
    fun setAutoSyncEnabled(c: Context, on: Boolean) = sp(c).edit().putBoolean(K_AUTO, on).apply()

    fun intervalMinutes(c: Context): Int = sp(c).getInt(K_INTERVAL, DEFAULT_INTERVAL_MIN)
    fun setIntervalMinutes(c: Context, min: Int) =
        sp(c).edit().putInt(K_INTERVAL, min.coerceAtLeast(MIN_INTERVAL_MIN)).apply()

    // "Ring my phone from the watch even when the app is closed": keeps a
    // background BLE link (a foreground service) that listens for the watch's
    // Find call and rings the phone.
    fun findBackgroundEnabled(c: Context): Boolean = sp(c).getBoolean(K_FIND_BG, false)
    fun setFindBackgroundEnabled(c: Context, on: Boolean) =
        sp(c).edit().putBoolean(K_FIND_BG, on).apply()

    // "Forward phone notifications to the watch": the NotificationListenerService
    // pushes each posted notification to the watch's New Alert characteristic.
    // Also requires the user to grant notification access in system settings.
    fun notifRelayEnabled(c: Context): Boolean = sp(c).getBoolean(K_NOTIF_RELAY, false)
    fun setNotifRelayEnabled(c: Context, on: Boolean) =
        sp(c).edit().putBoolean(K_NOTIF_RELAY, on).apply()

    // Apps whose notifications are NOT forwarded to the watch (by package name).
    // Everything is forwarded by default; the user mutes the noisy ones from the
    // "Choose apps" screen.
    fun mutedApps(c: Context): Set<String> =
        sp(c).getStringSet(K_MUTED_APPS, emptySet()) ?: emptySet()

    fun setAppMuted(c: Context, pkg: String, muted: Boolean) {
        // getStringSet hands back the live set: always edit a copy.
        val next = mutedApps(c).toMutableSet()
        if (muted) next.add(pkg) else next.remove(pkg)
        sp(c).edit().putStringSet(K_MUTED_APPS, next).apply()
    }

    fun clearMutedApps(c: Context) = sp(c).edit().remove(K_MUTED_APPS).apply()

    // When the last health push to the watch succeeded (manual or background),
    // and a short readout of what was sent. 0 = never.
    fun lastSyncMs(c: Context): Long = sp(c).getLong(K_LAST_SYNC, 0L)
    fun lastSyncDetail(c: Context): String = sp(c).getString(K_LAST_SYNC_DETAIL, "") ?: ""
    fun setLastSync(c: Context, whenMs: Long, detail: String) =
        sp(c).edit().putLong(K_LAST_SYNC, whenMs).putString(K_LAST_SYNC_DETAIL, detail).apply()
}
