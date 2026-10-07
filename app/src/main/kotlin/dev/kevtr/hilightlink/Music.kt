package dev.kevtr.hilightlink

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.kevtr.hilightlink.engine.Renderer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.pow

/** Music visualizer: listens to what the phone is playing and streams frames to the helper. */
object Music {
    enum class Status { OFF, NO_PERMISSION, WAITING, LISTENING, FAILED }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val state = MutableStateFlow(Status.OFF)
    val status: StateFlow<Status> get() = state

    /** Whether some app is playing media right now, as reported by the listener service. */
    @Volatile var playing = false

    // Main thread only.
    private var visualizer: Visualizer? = null
    private val levels = FloatArray(Renderer.LEDS)
    private val peaks = FloatArray(Renderer.LEDS)
    private val frames = MusicFrames()

    // IO thread only.
    private var stream: Leds.Stream? = null
    private var silentFrames = 0

    fun hasPermission(context: Context) =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Starts or stops listening to match the settings and whether anything is playing. */
    fun refresh(context: Context) {
        val app = context.applicationContext
        main.post {
            val config = RuleStore.config.value
            val audio = app.getSystemService(AudioManager::class.java)
            val wanted = config.music.on && !config.paused
            state.value = when {
                !wanted -> Status.OFF
                !hasPermission(app) -> Status.NO_PERMISSION
                !(playing || audio.isMusicActive) -> Status.WAITING
                visualizer != null || start(app) -> Status.LISTENING
                else -> Status.FAILED
            }
            if (state.value != Status.LISTENING) stop()
        }
    }

    private fun start(context: Context): Boolean = try {
        // Session 0 is the phone's whole output mix.
        val v = Visualizer(0)
        v.captureSize = CAPTURE_SIZE.coerceIn(Visualizer.getCaptureSizeRange()[0], Visualizer.getCaptureSizeRange()[1])
        v.scalingMode = Visualizer.SCALING_MODE_NORMALIZED
        v.setDataCaptureListener(
            object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(v: Visualizer, waveform: ByteArray, samplingRate: Int) = Unit
                override fun onFftDataCapture(v: Visualizer, fft: ByteArray, samplingRate: Int) =
                    onFft(context, fft, samplingRate)
            },
            minOf(Visualizer.getMaxCaptureRate(), CAPTURE_RATE_MHZ),
            false,
            true,
        )
        v.enabled = true
        peaks.fill(0f)
        levels.fill(0f)
        visualizer = v
        true
    } catch (e: Exception) {
        false
    }

    private fun stop() {
        visualizer?.let { v -> runCatching { v.enabled = false; v.release() } }
        visualizer = null
        io.execute {
            stream?.close()
            stream = null
        }
    }

    private fun onFft(context: Context, fft: ByteArray, samplingRateMilliHz: Int) {
        if (visualizer == null) return
        // Bin k covers k * rate / size Hz, as real and imaginary bytes at 2k and 2k+1.
        val hzPerBin = samplingRateMilliHz / 1000f / fft.size
        val bins = fft.size / 2
        val music = RuleStore.config.value.music
        val curve = curve(music.sensitivity)
        // A sensitive setting also listens further down into the quiet.
        val floor = NOISE_FLOOR * (1.5f - music.sensitivity)
        for (band in 0 until Renderer.LEDS) {
            val from = (BAND_EDGES_HZ[band] / hzPerBin).toInt().coerceIn(1, bins - 1)
            val to = (BAND_EDGES_HZ[band + 1] / hzPerBin).toInt().coerceIn(from + 1, bins)
            var loudest = 0f
            for (k in from until to) {
                loudest = maxOf(loudest, hypot(fft[2 * k].toFloat(), fft[2 * k + 1].toFloat()))
            }
            // Each band is measured against its own recent peak, so quiet treble still dances.
            peaks[band] = maxOf(peaks[band] * 0.995f, loudest, floor)
            val level = (loudest / peaks[band]).pow(curve)
            levels[band] = maxOf(level, levels[band] * 0.72f)
        }
        val out = IntArray(Renderer.LEDS)
        frames.frame(music, levels, SystemClock.uptimeMillis(), out)
        io.execute { send(context, out) }
    }

    private fun send(context: Context, out: IntArray) {
        // During silence let go of the LEDs so the charging light or a hold glow can show.
        silentFrames = if (out.all { it == 0 }) silentFrames + 1 else 0
        if (silentFrames > SILENT_FRAMES) {
            stream?.close()
            stream = null
            return
        }
        val s = stream ?: Leds.openStream(context)?.also { stream = it } ?: return
        if (!s.write(out)) {
            s.close()
            stream = null
        }
    }

    /**
     * The power a band's loudness (0..1 of its recent peak) is raised to. A higher [sensitivity]
     * gives a lower power, which lifts the quiet parts.
     */
    fun curve(sensitivity: Float) = 2.6f - 2f * sensitivity.coerceIn(0f, 1f)

    private const val CAPTURE_SIZE = 1024
    private const val CAPTURE_RATE_MHZ = 15_000
    private const val NOISE_FLOOR = 6f
    private const val SILENT_FRAMES = 30
    private val BAND_EDGES_HZ = floatArrayOf(40f, 110f, 220f, 440f, 880f, 1800f, 3600f, 7500f, 16000f)
}

/**
 * Turns band levels into LED colours. It remembers the beat count, the meter's peak and where
 * Shuffle has got to between frames, so the live lights and the on-screen preview each keep their own.
 */
class MusicFrames {
    private var lastBass = 0f
    private var lastBeatMs = 0L
    private var beats = 0
    private var peak = 0f
    private var peakSetMs = 0L
    private var lastMs = 0L

    // Shuffle.
    private var current = MusicStyle.SPECTRUM
    private val bag = ArrayList<MusicStyle>()
    private var switchedMs = -1L
    private var beatsAtSwitch = 0
    private var quietSinceMs = -1L
    private var energyFast = 0f
    private var energySlow = 0f

    /** [levels] are eight band levels (0..1, bass first); [tMs] is any steadily running clock. */
    fun frame(config: MusicConfig, levels: FloatArray, tMs: Long, out: IntArray) {
        val n = out.size
        val bass = maxOf(levels[0], levels[1])
        val beat = bass > BEAT_LEVEL && bass - lastBass > BEAT_RISE && tMs - lastBeatMs > BEAT_GAP_MS
        if (beat) {
            beats++
            lastBeatMs = tMs
        }
        lastBass = bass
        val seconds = (tMs - lastMs).coerceIn(0L, 200L) / 1000f
        lastMs = tMs
        // The styles that are always lit go dark in silence, so the helper can let go of the LEDs.
        val silent = levels.all { quantize(it) == 0f }
        fun rainbow(i: Int) = Renderer.hue(i * 0.8f / n)
        fun color(i: Int) = if (config.rainbow) rainbow(i) else config.color
        val style = if (config.style == MusicStyle.SHUFFLE) shuffled(levels, tMs, seconds, beat, silent) else config.style

        when (style) {
            // Never drawn itself: shuffled() always answers with one of the others.
            MusicStyle.SHUFFLE -> out.fill(0)
            MusicStyle.SPECTRUM -> for (i in 0 until n) {
                out[i] = Renderer.scale(rainbow(i), quantize(levels[i]) * config.brightness)
            }
            MusicStyle.PULSE -> {
                val beat = quantize(bass) * config.brightness
                for (i in 0 until n) out[i] = Renderer.scale(color(i), beat)
            }
            MusicStyle.METER -> {
                val lit = (levels.sum() / n * METER_GAIN).coerceAtMost(1f) * n
                if (lit >= peak) {
                    peak = lit
                    peakSetMs = tMs
                } else if (tMs - peakSetMs > PEAK_HOLD_MS) {
                    peak = maxOf(lit, peak - PEAK_FALL_PER_S * seconds)
                }
                val dot = if (peak > 0.5f) (peak - 0.01f).toInt().coerceAtMost(n - 1) else -1
                for (i in 0 until n) {
                    // Green at the quiet end through yellow to red at the loud end.
                    val color = if (config.rainbow) rainbow(i) else Renderer.hue(0.33f * (1f - i / (n - 1f)))
                    val k = if (i == dot) 1f else quantize((lit - i).coerceIn(0f, 1f))
                    out[i] = Renderer.scale(color, k * config.brightness)
                }
            }
            MusicStyle.BURST -> {
                val reach = quantize(bass) * (n / 2f + 0.5f)
                for (i in 0 until n) {
                    val distance = abs(i - (n - 1) / 2f)
                    out[i] = Renderer.scale(color(i), (reach - distance + 0.5f).coerceIn(0f, 1f) * config.brightness)
                }
            }
            MusicStyle.CHASE -> {
                val glow = if (silent) 0f else quantize(maxOf(CHASE_FLOOR, bass)) * config.brightness
                val head = beats % n
                out.fill(0)
                val tail = (head + n - 1) % n
                out[tail] = Renderer.scale(color(tail), glow * CHASE_TAIL)
                out[head] = Renderer.scale(color(head), glow)
            }
            MusicStyle.BEAT_COLOR -> {
                val glow = if (silent) 0f else quantize(GLOW_FLOOR + (1f - GLOW_FLOOR) * bass) * config.brightness
                // With the rainbow spread out, each beat turns the whole wheel a step further.
                for (i in 0 until n) {
                    val hue = beats * HUE_STEP + if (config.rainbow) i * 0.8f / n else 0f
                    out[i] = Renderer.scale(Renderer.hue(hue), glow)
                }
            }
        }
    }

    /**
     * The style Shuffle shows right now. It moves on when the music gives it a reason to, so a
     * change lands on the music instead of cutting across it:
     *  - on a beat, once a phrase worth of beats has gone by;
     *  - on a beat where the music suddenly gets louder than it has been (a drop or a chorus);
     *  - when sound comes back after a pause (the next track);
     *  - after a fixed time, for music with no beat to count.
     */
    private fun shuffled(levels: FloatArray, tMs: Long, seconds: Float, beat: Boolean, silent: Boolean): MusicStyle {
        val energy = levels.sum() / levels.size
        // Start both averages level, or the first seconds would look like a surge.
        if (switchedMs < 0) {
            energyFast = energy
            energySlow = energy
        }
        energyFast += (energy - energyFast) * (1f - exp(-seconds / FAST_S))
        energySlow += (energy - energySlow) * (1f - exp(-seconds / SLOW_S))
        val surge = beat && energyFast > energySlow * SURGE_RATIO + SURGE_MARGIN

        val back = !silent && quietSinceMs >= 0 && tMs - quietSinceMs > PAUSE_MS
        if (!silent) quietSinceMs = -1L else if (quietSinceMs < 0) quietSinceMs = tMs

        val shownMs = tMs - switchedMs
        val due = when {
            switchedMs < 0 -> true
            back || surge -> shownMs > MIN_SHOW_MS
            beat -> beats - beatsAtSwitch >= PHRASE_BEATS && shownMs > PHRASE_MIN_MS
            else -> shownMs > MAX_SHOW_MS
        }
        if (due) {
            current = draw(tMs)
            switchedMs = tMs
            beatsAtSwitch = beats
            // The surge that caused this change must not cause the next one too.
            energySlow = maxOf(energySlow, energyFast)
        }
        return current
    }

    /**
     * Next style out of a shuffled bag: every style comes up once before any repeats, and never
     * the same one twice in a row.
     */
    private fun draw(tMs: Long): MusicStyle {
        if (bag.isEmpty()) {
            bag += MusicStyle.entries.filter { it != MusicStyle.SHUFFLE }
            bag.shuffle()
            if (bag.last() == current) bag.add(0, bag.removeAt(bag.lastIndex))
        }
        // Without a beat the styles that only move on one would sit still, so they wait their turn.
        val steady = tMs - lastBeatMs < BEAT_RECENT_MS
        val pick = bag.indexOfLast { steady || it !in NEEDS_BEAT }
        return bag.removeAt(if (pick >= 0) pick else bag.lastIndex)
    }

    /** Fewer brightness steps means fewer LED writes for the helper. */
    private fun quantize(level: Float): Float {
        val q = (level * STEPS).toInt() / STEPS.toFloat()
        return if (q < 2f / STEPS) 0f else q
    }

    private companion object {
        const val STEPS = 16
        /** A beat is the bass jumping up by [BEAT_RISE] to above [BEAT_LEVEL], at most one per gap. */
        const val BEAT_LEVEL = 0.55f
        const val BEAT_RISE = 0.15f
        const val BEAT_GAP_MS = 180L
        /** The average of all bands rarely gets near 1, so the meter is scaled up to use every LED. */
        const val METER_GAIN = 1.6f
        const val PEAK_HOLD_MS = 450L
        const val PEAK_FALL_PER_S = 9f
        const val CHASE_FLOOR = 0.35f
        const val CHASE_TAIL = 0.3f
        const val GLOW_FLOOR = 0.35f
        /** Far enough round the colour wheel that every beat is a clearly different colour. */
        const val HUE_STEP = 0.19f

        val NEEDS_BEAT = setOf(MusicStyle.CHASE, MusicStyle.BEAT_COLOR)
        /** Sixteen beats is four bars of most music, the usual length of a phrase. */
        const val PHRASE_BEATS = 16
        /** Fast music still keeps a style this long, so the changes do not get restless. */
        const val PHRASE_MIN_MS = 6000L
        const val MIN_SHOW_MS = 3000L
        const val MAX_SHOW_MS = 20_000L
        const val PAUSE_MS = 1200L
        const val BEAT_RECENT_MS = 3000L
        /** How quickly the short and the long loudness averages follow the music, in seconds. */
        const val FAST_S = 0.4f
        const val SLOW_S = 5f
        const val SURGE_RATIO = 1.5f
        const val SURGE_MARGIN = 0.08f
    }
}
