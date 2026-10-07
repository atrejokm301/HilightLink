package dev.kevtr.hilightlink

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.Looper
import dev.kevtr.hilightlink.engine.Renderer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors
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
        for (band in 0 until Renderer.LEDS) {
            val from = (BAND_EDGES_HZ[band] / hzPerBin).toInt().coerceIn(1, bins - 1)
            val to = (BAND_EDGES_HZ[band + 1] / hzPerBin).toInt().coerceIn(from + 1, bins)
            var loudest = 0f
            for (k in from until to) {
                loudest = maxOf(loudest, hypot(fft[2 * k].toFloat(), fft[2 * k + 1].toFloat()))
            }
            // Each band is measured against its own recent peak, so quiet treble still dances.
            peaks[band] = maxOf(peaks[band] * 0.995f, loudest, NOISE_FLOOR)
            val level = (loudest / peaks[band]).pow(1.6f)
            levels[band] = maxOf(level, levels[band] * 0.72f)
        }
        val out = IntArray(Renderer.LEDS)
        frame(RuleStore.config.value.music, levels, out)
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

    /** Turns eight band levels (0..1, bass first) into LED colours. Shared with the preview. */
    fun frame(config: MusicConfig, levels: FloatArray, out: IntArray) {
        when (config.style) {
            MusicStyle.SPECTRUM -> for (i in out.indices) {
                out[i] = Renderer.scale(Renderer.hue(i * 0.8f / out.size), quantize(levels[i]) * config.brightness)
            }
            MusicStyle.PULSE -> {
                val beat = quantize(maxOf(levels[0], levels[1]))
                out.fill(Renderer.scale(config.color, beat * config.brightness))
            }
        }
    }

    /** Fewer brightness steps means fewer LED writes for the helper. */
    private fun quantize(level: Float): Float {
        val q = (level * STEPS).toInt() / STEPS.toFloat()
        return if (q < 2f / STEPS) 0f else q
    }

    private const val CAPTURE_SIZE = 1024
    private const val CAPTURE_RATE_MHZ = 15_000
    private const val NOISE_FLOOR = 6f
    private const val STEPS = 16
    private const val SILENT_FRAMES = 30
    private val BAND_EDGES_HZ = floatArrayOf(40f, 110f, 220f, 440f, 880f, 1800f, 3600f, 7500f, 16000f)
}
