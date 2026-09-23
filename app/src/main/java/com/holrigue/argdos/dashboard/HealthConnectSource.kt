package com.holrigue.argdos.dashboard

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Reads the wearer's metrics from Health Connect, where Gadgetbridge publishes
 * the Amazfit's data. Health Connect has no "stress" or "sleep score" type, so
 * stress is left out (the watch shows "--") and a sleep score is derived from
 * the last sleep session's duration and its deep/REM share.
 */
class HealthConnectSource(private val context: Context) {

    data class Snapshot(
        val steps: Int?,
        val hrBpm: Int?,
        val sleepScore: Int?,
        val detail: String,
    )

    val permissions: Set<String> = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
    )

    fun sdkStatus(): Int = HealthConnectClient.getSdkStatus(context)

    fun isAvailable(): Boolean = sdkStatus() == HealthConnectClient.SDK_AVAILABLE

    private fun client(): HealthConnectClient = HealthConnectClient.getOrCreate(context)

    suspend fun grantedPermissions(): Set<String> =
        client().permissionController.getGrantedPermissions()

    suspend fun hasAllPermissions(): Boolean =
        grantedPermissions().containsAll(permissions)

    /** Read the latest values. Missing metrics come back null. */
    suspend fun read(): Snapshot {
        val c = client()
        val zone = ZoneId.systemDefault()
        val now = Instant.now()
        val startOfDay = LocalDate.now(zone).atStartOfDay(zone).toInstant()

        // Steps: today's total.
        val steps: Int? = try {
            val agg = c.aggregate(
                AggregateRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(startOfDay, now),
                )
            )
            agg[StepsRecord.COUNT_TOTAL]?.toInt()
        } catch (e: Exception) {
            null
        }

        // Heart rate: the most recent sample in the last 6 hours.
        val hr: Int? = try {
            val resp = c.readRecords(
                ReadRecordsRequest(
                    recordType = HeartRateRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(now.minus(Duration.ofHours(6)), now),
                )
            )
            var latestTime: Instant? = null
            var latestBpm: Long? = null
            for (rec in resp.records) for (s in rec.samples) {
                if (latestTime == null || s.time.isAfter(latestTime)) {
                    latestTime = s.time; latestBpm = s.beatsPerMinute
                }
            }
            latestBpm?.toInt()
        } catch (e: Exception) {
            null
        }

        // Sleep: score from the most recent session (last 36 h).
        val sleep: Int? = try {
            val resp = c.readRecords(
                ReadRecordsRequest(
                    recordType = SleepSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(now.minus(Duration.ofHours(36)), now),
                )
            )
            resp.records.maxByOrNull { it.endTime }?.let { sleepScore(it) }
        } catch (e: Exception) {
            null
        }

        val detail = "steps=${steps ?: "-"}  hr=${hr ?: "-"}  sleep=${sleep ?: "-"}"
        return Snapshot(steps, hr, sleep, detail)
    }

    /**
     * A 0..100 sleep score: up to 70 points for duration (8 h = full) plus up to
     * 30 for the deep+REM share (50% of asleep time = full). Deliberately simple;
     * it stands in for the score Health Connect does not carry.
     */
    private fun sleepScore(session: SleepSessionRecord): Int {
        var asleepSec = 0L
        var deepRemSec = 0L
        if (session.stages.isNotEmpty()) {
            for (st in session.stages) {
                val d = Duration.between(st.startTime, st.endTime).seconds
                when (st.stage) {
                    SleepSessionRecord.STAGE_TYPE_AWAKE,
                    SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> { /* not asleep */ }
                    SleepSessionRecord.STAGE_TYPE_DEEP,
                    SleepSessionRecord.STAGE_TYPE_REM -> { asleepSec += d; deepRemSec += d }
                    else -> asleepSec += d   // light / sleeping / unknown
                }
            }
        } else {
            asleepSec = Duration.between(session.startTime, session.endTime).seconds
        }

        val hours = asleepSec / 3600.0
        val durationScore = (hours / 8.0 * 70.0).coerceIn(0.0, 70.0)
        val quality = if (asleepSec > 0) deepRemSec.toDouble() / asleepSec else 0.0
        val qualityScore = (quality / 0.5 * 30.0).coerceIn(0.0, 30.0)
        return (durationScore + qualityScore).roundToInt().coerceIn(0, 100)
    }
}
