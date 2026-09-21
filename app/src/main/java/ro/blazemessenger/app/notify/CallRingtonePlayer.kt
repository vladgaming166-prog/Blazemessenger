package ro.blazemessenger.app.notify

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import ro.blazemessenger.app.R
import ro.blazemessenger.app.domain.model.LocalSettings
import ro.blazemessenger.app.domain.model.RingtoneChoice

@Singleton
class CallRingtonePlayer @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var player: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null

    fun start(settings: LocalSettings) {
        stop()
        val notificationManager = context.getSystemService(android.app.NotificationManager::class.java)
        val filter = notificationManager.currentInterruptionFilter
        val dndAllowsSound = filter == android.app.NotificationManager.INTERRUPTION_FILTER_ALL
        val ringer = audioManager.ringerMode
        if (settings.vibrateCalls && ringer != AudioManager.RINGER_MODE_SILENT && dndAllowsSound) {
            vibrate()
        }
        if (!dndAllowsSound || ringer != AudioManager.RINGER_MODE_NORMAL) return
        val uri = ringtoneUri(settings) ?: return
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .build()
        focusRequest = focus
        audioManager.requestAudioFocus(focus)
        player = MediaPlayer().apply {
            setAudioAttributes(attributes)
            setDataSource(context, uri)
            isLooping = true
            val volume = settings.ringtoneVolume.coerceIn(0f, 1f)
            setVolume(volume, volume)
            setOnPreparedListener { it.start() }
            prepareAsync()
        }
    }

    fun preview(settings: LocalSettings) {
        stop()
        val uri = ringtoneUri(settings) ?: return
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        player = MediaPlayer().apply {
            setAudioAttributes(attributes)
            setDataSource(context, uri)
            isLooping = false
            val volume = settings.ringtoneVolume.coerceIn(0f, 1f)
            setVolume(volume, volume)
            setOnCompletionListener { stop() }
            setOnPreparedListener { it.start() }
            prepareAsync()
        }
    }

    fun stop() {
        player?.runCatching {
            setOnCompletionListener(null)
            stop()
            release()
        }
        player = null
        vibrator()?.cancel()
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    private fun ringtoneUri(settings: LocalSettings): Uri? {
        return when (settings.ringtone) {
            RingtoneChoice.DEFAULT -> resourceUri(R.raw.blaze_ringtone)
            RingtoneChoice.SOFT -> resourceUri(R.raw.blaze_notification)
            RingtoneChoice.CUSTOM -> {
                val raw = settings.customRingtoneUri
                if (raw.isBlank()) resourceUri(R.raw.blaze_ringtone) else Uri.parse(raw)
            }
        }
    }

    private fun resourceUri(resId: Int): Uri {
        return Uri.parse("android.resource://${context.packageName}/$resId")
    }

    private fun vibrate() {
        val vibrator = vibrator() ?: return
        if (!vibrator.hasVibrator()) return
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 480, 360, 480), 0))
    }

    private fun vibrator(): Vibrator? {
        return if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }
}
