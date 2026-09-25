package com.holrigue.argdos.dashboard

import android.content.Context
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Rings this phone (loud alarm tone + vibration) when the watch calls Find.
 * Shared by the in-app path (MainActivity) and the background [FindService], so
 * the behaviour is identical whether the app is open or closed.
 *
 * The alarm stream is used on purpose: alarms bypass Do-Not-Disturb and their
 * volume can be raised without notification-policy access. We save and restore
 * the user's alarm volume around the ring so we don't leave it maxed.
 */
object PhoneRinger {
    private val handler = Handler(Looper.getMainLooper())
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var savedAlarmVolume = -1
    private var appContext: Context? = null
    private const val AUTO_STOP_MS = 60_000L

    val isRinging: Boolean get() = ringtone != null

    private val autoStop = Runnable { appContext?.let { stop(it) } }

    @Synchronized
    fun start(context: Context) {
        if (isRinging) return
        val ctx = context.applicationContext
        appContext = ctx

        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (am != null) {
            try {
                savedAlarmVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
                am.setStreamVolume(
                    AudioManager.STREAM_ALARM,
                    am.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                    0,
                )
            } catch (_: Exception) {}
        }

        try {
            val uri = RingtoneManager.getActualDefaultRingtoneUri(ctx, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(ctx, uri)?.apply {
                @Suppress("DEPRECATION")
                streamType = AudioManager.STREAM_ALARM
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
                play()
            }
        } catch (_: Exception) {}

        vibrator = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        else @Suppress("DEPRECATION") (ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator))
        try {
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 400), 0))
        } catch (_: Exception) {}

        handler.removeCallbacks(autoStop)
        handler.postDelayed(autoStop, AUTO_STOP_MS)
    }

    @Synchronized
    fun stop(context: Context) {
        handler.removeCallbacks(autoStop)
        try { ringtone?.stop() } catch (_: Exception) {}
        ringtone = null
        try { vibrator?.cancel() } catch (_: Exception) {}
        vibrator = null
        if (savedAlarmVolume >= 0) {
            val am = context.applicationContext
                .getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            try { am?.setStreamVolume(AudioManager.STREAM_ALARM, savedAlarmVolume, 0) } catch (_: Exception) {}
            savedAlarmVolume = -1
        }
    }
}
