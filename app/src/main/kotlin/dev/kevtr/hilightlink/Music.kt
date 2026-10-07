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
 * Turns band levels into LED colours. It remembers the beat count and the meter's peak between
 * frames, so the live lights and the on-screen preview each keep their own.
 */
class MusicFrames {
    private var lastBass = 0f
    private var lastBeatMs = 0L
    private var beats = 0
    private var peak = 0f
    private var peakSetMs = 0L
    private var lastMs = 0L

    /** [levels] are eight band levels (0..1, bass first); [tMs] is any steadily running clock. */
    fun frame(config: MusicConfig, levels: FloatArray, tMs: Long, out: IntArray) {
        val n = out.size
        val bass = maxOf(levels[0], levels[1])
        if (bass > BEAT_LEVEL && bass - lastBass > BEAT_RISE && tMs - lastBeatMs > BEAT_GAP_MS) {
            beats++
            lastBeatMs = tMs
        }
        lastBass = bass
        val seconds = (tMs - lastMs).coerceIn(0L, 200L) / 1000f
        lastMs = tMs
        // The styles that are always lit go dark in silence, so the helper can let go of the LEDs.
        val silent = levels.all { quantize(it) == 0f }

        when (config.style) {
            MusicStyle.SPECTRUM -> for (i in 0 until n) {
                out[i] = Renderer.scale(Renderer.hue(i * 0.8f / n), quantize(levels[i]) * config.brightness)
            }
            MusicStyle.PULSE -> out.fill(Renderer.scale(config.color, quantize(bass) * config.brightness))
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
                    val color = Renderer.hue(0.33f * (1f - i / (n - 1f)))
                    val k = if (i == dot) 1f else quantize((lit - i).coerceIn(0f, 1f))
                    out[i] = Renderer.scale(color, k * config.brightness)
                }
            }
            MusicStyle.BURST -> {
                val reach = quantize(bass) * (n / 2f + 0.5f)
                for (i in 0 until n) {
                    val distance = abs(i - (n - 1) / 2f)
                    out[i] = Renderer.scale(config.color, (reach - distance + 0.5f).coerceIn(0f, 1f) * config.brightness)
                }
            }
            MusicStyle.CHASE -> {
                val glow = if (silent) 0f else quantize(maxOf(CHASE_FLOOR, bass)) * config.brightness
                val head = beats % n
                out.fill(0)
                out[(head + n - 1) % n] = Renderer.scale(config.color, glow * CHASE_TAIL)
                out[head] = Renderer.scale(config.color, glow)
            }
            MusicStyle.BEAT_COLOR -> {
                val glow = if (silent) 0f else quantize(GLOW_FLOOR + (1f - GLOW_FLOOR) * bass) * config.brightness
                out.fill(Renderer.scale(Renderer.hue(beats * HUE_STEP), glow))
            }
        }
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
    }
}
