package com.holrigue.argdos.dashboard

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Background auto-sync: read the latest metrics from Health Connect and push
 * them to the saved watch over BLE. Scheduled periodically by [SyncScheduler].
 *
 * It reconnects to the watch by the MAC saved during a manual connect, so no BLE
 * scan (restricted in the background) is needed. On Android 14+ reading Health
 * Connect in the background also needs READ_HEALTH_DATA_IN_BACKGROUND; without it
 * the read throws and we simply skip this run.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext

        // Nothing to sync to until the user has connected once.
        val address = Prefs.watchAddress(ctx) ?: return Result.success()

        val hc = HealthConnectSource(ctx)
        if (!hc.isAvailable()) return Result.success()
        val hasPerms = try { hc.hasAllPermissions() } catch (e: Exception) { false }
        if (!hasPerms) return Result.success()

        val snap = try {
            hc.read()
        } catch (e: Exception) {
            // Missing background permission, or a transient Health Connect error.
            return Result.retry()
        }

        // Nothing worth sending (all metrics missing) - treat as a no-op success.
        if (snap.steps == null && snap.hrHigh == null && snap.sleepScore == null) {
            return Result.success()
        }

        val packet = HealthPacket.build(
            sleepScore = snap.sleepScore,
            steps = snap.steps,
            stress = null,          // Health Connect has no stress type
            hrLow = snap.hrLow,
            hrHigh = snap.hrHigh,
        )

        val ok = BlePush.push(ctx, address, packet)
        // Retry (with WorkManager backoff) if the watch was unreachable this round;
        // the next periodic run will try again regardless.
        return if (ok) Result.success() else Result.retry()
    }
}
