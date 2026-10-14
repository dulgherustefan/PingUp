package ro.safetyplease.ui.ios

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionMixWithOthers
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
import platform.AVFAudio.setActive
import platform.AudioToolbox.AudioServicesPlaySystemSound
import platform.AudioToolbox.kSystemSoundID_Vibrate
import platform.CoreHaptics.CHHapticEngine
import platform.CoreHaptics.CHHapticEvent
import platform.CoreHaptics.CHHapticEventParameter
import platform.CoreHaptics.CHHapticEventParameterIDHapticIntensity
import platform.CoreHaptics.CHHapticEventTypeHapticContinuous
import platform.CoreHaptics.CHHapticPattern
import platform.CoreHaptics.CHHapticPatternPlayerProtocol
import platform.CoreHaptics.CHHapticTimeImmediate
import platform.Foundation.NSData
import platform.Foundation.create
import ro.safetyplease.ui.common.Beacon
import kotlin.math.PI
import kotlin.math.sin

/**
 * "Fă-te văzut" and the route's buzzes on an iPhone, as Android's vibrator and tone give them: the pattern played by
 * the haptic engine, and with [sound] a beep through the speaker. The person started it, so the beep plays with the
 * silent switch on, over whatever else plays. Used from the screen that shows it, on the main thread.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosBeacon(private val sound: Boolean) : Beacon {
    private val engine: CHHapticEngine? = runCatching { CHHapticEngine(andReturnError = null) }.getOrNull()
    private var player: CHHapticPatternPlayerProtocol? = null
    private val beeps = HashMap<Int, AVAudioPlayer>()
    private var playing = false

    /** [pattern]: milliseconds off, on, off, on... as Android's waveform. */
    override fun vibrate(pattern: LongArray) {
        val events = ArrayList<CHHapticEvent>()
        var at = 0L
        pattern.forEachIndexed { i, ms ->
            if (i % 2 == 1 && ms > 0) {
                val strong = listOf(CHHapticEventParameter(CHHapticEventParameterIDHapticIntensity, 1.0f))
                events += CHHapticEvent(CHHapticEventTypeHapticContinuous, strong, at / 1000.0, ms / 1000.0)
            }
            at += ms
        }
        if (events.isEmpty()) return
        stopVibrating()
        val engine = engine
        val started = engine != null && runCatching {
            engine.startAndReturnError(null)
            val haptics = CHHapticPattern(events = events, parameters = emptyList<Any>(), error = null)
            player = engine.createPlayerWithPattern(haptics, null)?.also { it.startAtTime(CHHapticTimeImmediate, null) }
            player != null
        }.getOrDefault(false)
        // a phone without the haptic engine buzzes once, as iOS's own vibration does
        if (!started) AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)
    }

    override fun beep(ms: Int) {
        if (!sound || ms <= 0) return
        if (!playing) {
            val session = AVAudioSession.sharedInstance()
            session.setCategory(AVAudioSessionCategoryPlayback, AVAudioSessionCategoryOptionMixWithOthers, null)
            session.setActive(true, null)
            playing = true
        }
        val beep = beeps.getOrPut(ms) { AVAudioPlayer(data = tone(ms), error = null) }
        beep.setCurrentTime(0.0)
        beep.play()
    }

    override fun release() {
        stopVibrating()
        engine?.stopWithCompletionHandler(null)
        beeps.values.forEach { it.stop() }
        beeps.clear()
        if (playing) {
            AVAudioSession.sharedInstance().setActive(false, AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation, null)
            playing = false
        }
    }

    private fun stopVibrating() {
        player?.let { runCatching { it.stopAtTime(CHHapticTimeImmediate, null) } }
        player = null
    }

    private companion object {
        const val RATE = 44_100
        const val PITCH_HZ = 2_000.0

        /** [ms] of a clear tone, as 16-bit mono WAV, with 5 ms ramps so it doesn't click. */
        fun tone(ms: Int): NSData {
            val count = RATE * ms / 1000
            val ramp = RATE / 200
            val wav = ByteArray(HEADER + count * 2)
            fun put(at: Int, value: Int, bytes: Int) {
                for (i in 0 until bytes) wav[at + i] = (value shr (8 * i)).toByte()
            }
            fun tag(at: Int, text: String) = text.encodeToByteArray().copyInto(wav, at)
            tag(0, "RIFF")
            put(4, HEADER - 8 + count * 2, 4)
            tag(8, "WAVE")
            tag(12, "fmt ")
            put(16, 16, 4)
            put(20, 1, 2)
            put(22, 1, 2)
            put(24, RATE, 4)
            put(28, RATE * 2, 4)
            put(32, 2, 2)
            put(34, 16, 2)
            tag(36, "data")
            put(40, count * 2, 4)
            for (i in 0 until count) {
                val envelope = minOf(1.0, i / ramp.toDouble(), (count - i) / ramp.toDouble())
                put(HEADER + 2 * i, (sin(2 * PI * PITCH_HZ * i / RATE) * envelope * LOUDNESS * Short.MAX_VALUE).toInt(), 2)
            }
            return wav.usePinned { NSData.create(bytes = it.addressOf(0), length = wav.size.toULong()) }
        }

        const val HEADER = 44
        const val LOUDNESS = 0.9
    }
}
