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

    const val DEFAULT_INTERVAL_MIN = 30
    const val MIN_INTERVAL_MIN = 15   // WorkManager's floor for periodic work

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun watchAddress(c: Context): String? = sp(c).getString(K_ADDR, null)
    fun setWatchAddress(c: Context, addr: String) = sp(c).edit().putString(K_ADDR, addr).apply()

    fun autoSyncEnabled(c: Context): Boolean = sp(c).getBoolean(K_AUTO, false)
    fun setAutoSyncEnabled(c: Context, on: Boolean) = sp(c).edit().putBoolean(K_AUTO, on).apply()

    fun intervalMinutes(c: Context): Int = sp(c).getInt(K_INTERVAL, DEFAULT_INTERVAL_MIN)
    fun setIntervalMinutes(c: Context, min: Int) =
        sp(c).edit().putInt(K_INTERVAL, min.coerceAtLeast(MIN_INTERVAL_MIN)).apply()
}
