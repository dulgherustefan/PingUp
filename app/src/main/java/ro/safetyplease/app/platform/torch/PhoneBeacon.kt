package ro.safetyplease.app.platform.torch

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.Vibrator
import ro.safetyplease.ui.common.Beacon

/** The vibrator and, with [sound], a beep on the alarm stream, so it sounds over media at full volume. */
class PhoneBeacon(context: Context, sound: Boolean) : Beacon {
    private val vibrator = context.getSystemService(Vibrator::class.java)?.takeIf { it.hasVibrator() }
    private val tone = if (sound) runCatching { ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME) }.getOrNull() else null

    override fun vibrate(pattern: LongArray) {
        vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    override fun beep(ms: Int) {
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP, ms)
    }

    override fun release() {
        vibrator?.cancel()
        tone?.release()
    }
}
