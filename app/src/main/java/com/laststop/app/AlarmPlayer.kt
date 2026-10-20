package com.laststop.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.net.toUri

class AlarmPlayer(context: Context, private val onFallback: () -> Unit = {}) {
    private val appContext = context.applicationContext
    private val audio = appContext.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attributes).setOnAudioFocusChangeListener { }.build()
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= 31)
        appContext.getSystemService(VibratorManager::class.java).defaultVibrator
        else appContext.getSystemService(Vibrator::class.java)
    private var player: MediaPlayer? = null
    private var tone: ToneGenerator? = null
    private var active = false
    private var prepared = false
    private var candidates = emptyList<String>()
    private var nextCandidate = 0

    fun start(soundUri: String = "") {
        if (active) return
        active = true
        runCatching { audio.requestAudioFocus(focus) }
        val default = runCatching { RingtoneManager.getActualDefaultRingtoneUri(appContext, RingtoneManager.TYPE_ALARM)?.toString() }.getOrNull()
        candidates = listOfNotNull(soundUri.takeIf { it.isNotBlank() }, default,
            "android.resource://${appContext.packageName}/${R.raw.fallback_alarm}").distinct()
        nextCandidate = 0
        playNext()
        runCatching { vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 350, 700, 700), 0)) }
    }

    private fun playNext() {
        if (!active) return
        prepared = false
        player?.release()
        player = null
        if (nextCandidate > 0) onFallback()
        val uri = candidates.getOrNull(nextCandidate++)
        if (uri == null) {
            tone = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, 100) }.getOrNull()
            ensurePlaying()
            return
        }
        val next = MediaPlayer()
        player = next
        try {
            next.setAudioAttributes(attributes)
            next.isLooping = true
            next.setOnPreparedListener {
                if (active && player === it) { prepared = true; it.start() }
            }
            next.setOnErrorListener { failed, _, _ ->
                if (player === failed && active) playNext()
                true
            }
            next.setDataSource(appContext, uri.toUri())
            next.prepareAsync()
        } catch (_: Exception) {
            playNext()
        }
    }

    fun ensurePlaying() {
        if (!active) return
        if (tone != null) { tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 1500); return }
        if (prepared) runCatching { if (player?.isPlaying == false) player?.start() }
    }

    fun stop() {
        active = false
        prepared = false
        runCatching { player?.release() }
        player = null
        runCatching { tone?.release() }
        tone = null
        runCatching { vibrator.cancel() }
        runCatching { audio.abandonAudioFocusRequest(focus) }
    }
}
