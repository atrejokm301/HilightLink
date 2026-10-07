package dev.kevtr.hilightlink.daemon

import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import dev.kevtr.hilightlink.engine.Effect
import dev.kevtr.hilightlink.engine.Renderer
import dev.kevtr.hilightlink.engine.Style
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.system.exitProcess

/**
 * Root helper, started with app_process by the root module (or by the app through su).
 * Normal apps cannot reach the lights HAL, so this process owns it and plays patterns
 * on request from the app over a loopback socket.
 */
object Main {
    const val PORT = 48211
    const val EXIT_PORT_BUSY = 3

    @JvmStatic
    fun main(args: Array<String>) {
        val pkg = args.firstOrNull() ?: "dev.kevtr.hilightlink"
        val server = try {
            ServerSocket(PORT, 8, InetAddress.getLoopbackAddress())
        } catch (e: Exception) {
            System.err.println("hilightlinkd: port busy, another helper is running")
            exitProcess(EXIT_PORT_BUSY)
        }
        val player = Player(Lights())
        val tokenFile = File("/data/user/0/$pkg/files/token")

        // An app update moves the APK; exit so the launcher restarts us from the new one.
        val apk = File(System.getProperty("java.class.path") ?: "")
        Thread {
            while (true) {
                Thread.sleep(30_000)
                if (!apk.exists()) {
                    player.clear()
                    exitProcess(0)
                }
            }
        }.apply { isDaemon = true }.start()

        println("hilightlinkd: listening on $PORT")
        while (true) {
            val socket = try { server.accept() } catch (e: Exception) { continue }
            // Do not answer for an app that has since been updated; dropping the request makes
            // the app start the new helper and send it again, so no state lands in the old one.
            if (!apk.exists()) {
                runCatching { socket.close() }
                player.clear()
                exitProcess(0)
            }
            var handedOff = false
            try {
                socket.soTimeout = 2000
                val reader = socket.getInputStream().bufferedReader()
                val line = reader.readLine() ?: continue
                val req = JSONObject(line)
                val token = runCatching { tokenFile.readText().trim() }.getOrDefault("")
                val reply = if (token.isEmpty() || req.optString("token") != token) {
                    "denied"
                } else when (req.optString("cmd")) {
                    "ping" -> "ok"
                    "play" -> {
                        val effect = Effect.fromJson(req.getJSONObject("effect"))
                        if (req.has("rest")) player.play(effect, effectOf(req, "rest"), setRest = true) else player.play(effect)
                        "ok"
                    }
                    "rest" -> { player.setRest(effectOf(req, "effect")); "ok" }
                    "off" -> { player.setRest(null); "ok" }
                    "base" -> { player.setBase(frameOf(req)); "ok" }
                    "timer" -> { player.setTimer(req.optLong("endAt"), req.optLong("totalMs"), req.optInt("color")); "ok" }
                    "stream" -> { handedOff = true; "ok" }
                    "quit" -> { player.clear(); socket.getOutputStream().write("ok\n".toByteArray()); exitProcess(0) }
                    else -> "unknown"
                }
                socket.getOutputStream().write("$reply\n".toByteArray())
                if (handedOff) stream(socket, reader, player)
            } catch (e: Exception) {
                System.err.println("hilightlinkd: $e")
            } finally {
                if (!handedOff) runCatching { socket.close() }
            }
        }
    }

    private fun effectOf(req: JSONObject, name: String): Effect? = req.optJSONObject(name)?.let(Effect::fromJson)

    /** A "base" request carries either one colour for every LED or a frame with one colour each. */
    private fun frameOf(req: JSONObject): IntArray {
        val frame = req.optJSONArray("frame") ?: return IntArray(Renderer.LEDS) { req.optInt("color") }
        return IntArray(Renderer.LEDS) { frame.optInt(it) }
    }

    /** Live frames, one comma-separated line each, shown until the app closes the connection. */
    private fun stream(socket: Socket, reader: BufferedReader, player: Player) {
        Thread {
            val frame = IntArray(Renderer.LEDS)
            try {
                socket.soTimeout = 4000
                while (true) {
                    val line = reader.readLine() ?: break
                    val parts = line.split(',')
                    for (i in frame.indices) frame[i] = parts.getOrNull(i)?.toIntOrNull() ?: 0
                    player.setStream(socket, frame)
                }
            } catch (e: Exception) {
                // Timed out or dropped: fall through and release the LEDs.
            } finally {
                player.endStream(socket)
                runCatching { socket.close() }
            }
        }.start()
    }
}

/** Raw binder client for android.hardware.light.ILights; the AIDL stubs are not on our classpath. */
class Lights {
    private var binder: IBinder? = null

    private fun service(): IBinder? {
        binder?.let { if (it.isBinderAlive) return it }
        val sm = Class.forName("android.os.ServiceManager")
        binder = sm.getMethod("getService", String::class.java).invoke(null, SERVICE) as IBinder?
        return binder
    }

    /** ILights.setLightState(id, HwLightState{color, flashMode, flashOnMs, flashOffMs, brightnessMode}). */
    fun set(id: Int, color: Int): Boolean {
        val b = service() ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(id)
            data.writeInt(1)   // non-null parcelable
            data.writeInt(24)  // parcelable size, including this field
            data.writeInt(color)
            data.writeInt(0)   // FlashMode.NONE
            data.writeInt(0)
            data.writeInt(0)
            data.writeInt(0)   // BrightnessMode.USER
            b.transact(TRANSACTION_SET_LIGHT_STATE, data, reply, 0)
            reply.readException()
            true
        } catch (e: Exception) {
            binder = null
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    companion object {
        const val SERVICE = "android.hardware.light.ILights/default"
        const val DESCRIPTOR = "android.hardware.light.ILights"
        const val TRANSACTION_SET_LIGHT_STATE = 1
        /** Pixel 11 Pro: the eight Hilight LEDs are HAL light ids 1..8, ordinals 0..7. */
        const val FIRST_ID = 1
        /** The HAL's MinUpdatePeriod for these lights. */
        const val FRAME_MS = 33L
    }
}

/**
 * Kernel wakelock, so a pattern keeps running while the phone dozes. Always timed, so it cannot
 * outlive a helper that dies while holding it.
 */
private object Wake {
    private const val NAME = "hilightlinkd"
    private var held = false

    fun hold(ms: Long) {
        held = write("/sys/power/wake_lock", "$NAME ${ms * 1_000_000}") || held
    }

    fun release() {
        if (held) write("/sys/power/wake_unlock", NAME)
        held = false
    }

    private fun write(path: String, text: String) = runCatching { File(path).writeText(text) }.isSuccess
}

/**
 * Decides what the LEDs show. A playing pattern always wins; otherwise the first of these
 * that is active: timer countdown, live stream (music), hold glow, base (charging light).
 */
class Player(private val lights: Lights) {
    private class Countdown(val endAt: Long, val totalMs: Long, val color: Int)

    private val shown = IntArray(Renderer.LEDS)
    private val lock = Any()
    private var playing = false
    private var base = IntArray(Renderer.LEDS)
    /** The pattern whose glow lingers once nothing is playing. */
    private var rest: Effect? = null
    private var glow: IntArray? = null
    private var stream: IntArray? = null
    private var streamOwner: Any? = null
    private var timer: Countdown? = null
    @Volatile private var generation = 0

    /** Plays [effect] once. With [setRest], [then] replaces what lingers after it. */
    fun play(effect: Effect, then: Effect? = null, setRest: Boolean = false) {
        val mine = synchronized(lock) {
            if (setRest) rest = then
            playing = true
            Wake.hold(effect.durationMs + WAKE_MARGIN_MS)
            ++generation
        }
        Thread {
            val frame = IntArray(Renderer.LEDS)
            val start = SystemClock.uptimeMillis()
            var next = start
            while (generation == mine) {
                val t = SystemClock.uptimeMillis() - start
                if (t >= effect.durationMs) break
                Renderer.frame(effect, t, frame)
                synchronized(lock) { if (generation == mine) push(frame) }
                next += Lights.FRAME_MS
                val wait = next - SystemClock.uptimeMillis()
                if (wait > 0) Thread.sleep(wait) else next = SystemClock.uptimeMillis()
            }
            synchronized(lock) {
                if (generation != mine) return@Thread
                playing = false
                enterRest()
            }
        }.start()
    }

    /** Stops any pattern and shows [effect]'s glow; null leaves nothing lingering. */
    fun setRest(effect: Effect?) = synchronized(lock) {
        ++generation
        playing = false
        rest = effect
        enterRest()
    }

    /** Everything dark, for when the helper is about to exit. */
    fun clear() = synchronized(lock) {
        ++generation
        playing = false
        rest = null
        stream = null
        timer = null
        base = IntArray(Renderer.LEDS)
        enterRest()
    }

    /** Shows what lingers now that nothing is playing. Call with [lock] held. */
    private fun enterRest() {
        val e = rest
        glow = if (e != null && e.hold) IntArray(Renderer.LEDS).also { Renderer.holdFrame(e, it) } else null
        Wake.release()
        showRest()
    }

    fun setBase(frame: IntArray) = synchronized(lock) {
        base = frame
        showRest()
    }

    fun setStream(owner: Any, frame: IntArray) = synchronized(lock) {
        streamOwner = owner
        stream = frame.copyOf()
        showRest()
    }

    fun endStream(owner: Any) = synchronized(lock) {
        if (streamOwner !== owner) return@synchronized
        streamOwner = null
        stream = null
        showRest()
    }

    /** Drains the LEDs until [endAt] (elapsedRealtime), then blinks. A zero [totalMs] cancels. */
    fun setTimer(endAt: Long, totalMs: Long, color: Int) {
        val mine = if (totalMs > 0) Countdown(endAt, totalMs, color) else null
        synchronized(lock) {
            timer = mine
            showRest()
        }
        if (mine == null) return
        Thread {
            while (true) {
                Thread.sleep(1000)
                val finished = synchronized(lock) {
                    if (timer !== mine) return@Thread
                    if (SystemClock.elapsedRealtime() >= mine.endAt) {
                        timer = null
                        true
                    } else {
                        showRest()
                        false
                    }
                }
                if (finished) {
                    play(Effect(Style.BLINK, listOf(mine.color), periodMs = 500, durationMs = 6000))
                    return@Thread
                }
            }
        }.start()
    }

    private fun showRest() {
        if (playing) return
        val t = timer
        val frame = when {
            t != null -> IntArray(Renderer.LEDS).also {
                Renderer.meter(t.color, (t.endAt - SystemClock.elapsedRealtime()).toFloat() / t.totalMs, it)
            }
            else -> stream ?: glow ?: base
        }
        push(frame)
    }

    private fun push(frame: IntArray) {
        for (i in frame.indices) {
            if (frame[i] != shown[i] && lights.set(Lights.FIRST_ID + i, frame[i])) shown[i] = frame[i]
        }
    }

    private companion object {
        /** How much longer than the pattern the wakelock is asked for, in case it runs late. */
        const val WAKE_MARGIN_MS = 5000L
    }
}
