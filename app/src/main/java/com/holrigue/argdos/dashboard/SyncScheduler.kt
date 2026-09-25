package com.holrigue.argdos.dashboard

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import java.util.concurrent.TimeUnit

/**
 * Owns the single periodic auto-sync job. [apply] reconciles WorkManager with the
 * saved preferences: it enqueues (or updates) the periodic [SyncWorker] when
 * auto-sync is on, and cancels it when off. WorkManager persists the schedule
 * across reboots on its own, so no boot receiver is needed.
 */
object SyncScheduler {
    private const val WORK_NAME = "argdos_auto_sync"

    fun apply(context: Context) {
        val wm = WorkManager.getInstance(context)
        if (!Prefs.autoSyncEnabled(context)) {
            wm.cancelUniqueWork(WORK_NAME)
            return
        }
        val minutes = Prefs.intervalMinutes(context)
            .coerceAtLeast(Prefs.MIN_INTERVAL_MIN)
            .toLong()

        val request = PeriodicWorkRequestBuilder<SyncWorker>(minutes, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                    .build()
            )
            .setBackoffCriteria(
                BackoffPolicy.LINEAR,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS,
            )
            .build()

        // UPDATE keeps one job and picks up an interval change without duplicating.
        wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
