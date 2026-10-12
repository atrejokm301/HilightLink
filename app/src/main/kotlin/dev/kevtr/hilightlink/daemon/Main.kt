package dev.kevtr.hilightlink.daemon

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import dev.kevtr.hilightlink.engine.Effect
import dev.kevtr.hilightlink.engine.Renderer
import dev.kevtr.hilightlink.engine.Style
import org.json.JSONArray
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

    /** Levels a pattern can play at; a higher one shows over a lower one. */
    const val NOTE = 0
    const val ALERT = 1
    const val CALL = 2

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
            // A thread each, so a client that connects and says nothing cannot hold up the others.
            Thread { serve(socket, tokenFile, player) }.start()
        }
    }

    private fun serve(socket: Socket, tokenFile: File, player: Player) {
        try {
            socket.soTimeout = 2000
            val reader = socket.getInputStream().bufferedReader()
            val line = reader.readLine() ?: return
            val req = JSONObject(line)
            val token = runCatching { tokenFile.readText().trim() }.getOrDefault("")
            var streaming = false
            val reply = if (token.isEmpty() || req.optString("token") != token) {
                "denied"
            } else when (req.optString("cmd")) {
                "ping" -> "ok"
                "play" -> {
                    player.play(Effect.fromJson(req.getJSONObject("effect")), req.optInt("level", NOTE))
                    "ok"
                }
                // Without a level, every pattern stops.
                "stop" -> { player.stop(if (req.has("level")) req.getInt("level") else null); "ok" }
                "glow" -> { player.setGlow(frameOf(req)); "ok" }
                "base" -> { player.setBase(frameOf(req)); "ok" }
                "timer" -> { player.setTimer(req.optLong("endAt"), req.optLong("totalMs"), req.optInt("color")); "ok" }
                "channels" -> Channels.of(req.optString("pkg"), req.optInt("uid"))
                "stream" -> { streaming = true; "ok" }
                "quit" -> { player.clear(); socket.getOutputStream().write("ok\n".toByteArray()); exitProcess(0) }
                else -> "unknown"
            }
            socket.getOutputStream().write("$reply\n".toByteArray())
            if (streaming) stream(socket, reader, player)
        } catch (e: Exception) {
            System.err.println("hilightlinkd: $e")
        } finally {
            runCatching { socket.close() }
        }
    }

    /** A frame request carries either one colour for every LED or a frame with one colour each. */
    private fun frameOf(req: JSONObject): IntArray {
        val frame = req.optJSONArray("frame") ?: return IntArray(Renderer.LEDS) { req.optInt("color") }
        return IntArray(Renderer.LEDS) { frame.optInt(it) }
    }

    /** Live frames, one comma-separated line each, shown until the app closes the connection. */
    private fun stream(socket: Socket, reader: BufferedReader, player: Player) {
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
        }
    }
}

/**
 * The notification channels of an app, for rules that cover only one of them. Apps may not
 * list another app's channels, so the helper asks the notification service as root.
 */
private object Channels {
    /** One line of JSON, {"channels":[{"id":"…","name":"…"}]}, or "failed". */
    fun of(pkg: String, uid: Int): String = try {
        val sm = Class.forName("android.os.ServiceManager")
        val binder = sm.getMethod("getService", String::class.java).invoke(null, "notification") as IBinder
        val nm = Class.forName("android.app.INotificationManager\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
        // Apps such as Gmail give every account a channel with the same name, told apart only by
        // the group it is in, so the group's name goes with it.
        val groups = runCatching {
            list(nm, "getNotificationChannelGroupsForPackage", pkg, uid)
                .filterIsInstance<NotificationChannelGroup>().associate { it.id to it.name?.toString() }
        }.getOrDefault(emptyMap())
        val out = JSONArray()
        list(nm, "getNotificationChannelsForPackage", pkg, uid).filterIsInstance<NotificationChannel>().forEach {
            val name = it.name?.toString() ?: it.id
            val group = groups[it.group]
            out.put(JSONObject().put("id", it.id).put("name", if (group.isNullOrBlank()) name else "$name ($group)"))
        }
        JSONObject().put("channels", out).toString()
    } catch (e: Exception) {
        System.err.println("hilightlinkd: channels: $e")
        "failed"
    }

    /**
     * Calls a notification service method that takes (String pkg, int uid, boolean includeDeleted)
     * and answers with a list. The arguments are filled in by type in case their order changes.
     */
    private fun list(nm: Any, name: String, pkg: String, uid: Int): List<*> {
        val method = nm.javaClass.methods.first { it.name == name }
        val args = method.parameterTypes.map { type ->
            when (type) {
                String::class.java -> pkg
                Int::class.javaPrimitiveType -> uid
                else -> false
            }
        }
        val slice = method.invoke(nm, *args.toTypedArray())!!
        return slice.javaClass.getMethod("getList").invoke(slice) as List<*>
    }
}

/** Raw binder client for android.hardware.light.ILights; the AIDL stubs are not on our classpath. */
class Lights {
    private var binder: IBinder? = null
    /** HAL ids of the Hilight LEDs in the order they sit on the phone; null until the HAL was asked. */
    private var ids: IntArray? = null

    private fun service(): IBinder? {
        binder?.let { if (it.isBinderAlive) return it }
        val sm = Class.forName("android.os.ServiceManager")
        binder = sm.getMethod("getService", String::class.java).invoke(null, SERVICE) as IBinder?
        return binder
    }

    /**
     * ILights.getLights(): the ids of the lights apps may drive, by ordinal. Empty on a phone
     * without Hilight LEDs, so its other lights are never touched; null if the call failed.
     */
    private fun find(b: IBinder): IntArray? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            b.transact(TRANSACTION_GET_LIGHTS, data, reply, 0)
            reply.readException()
            val found = ArrayList<Pair<Int, Int>>()
            repeat(reply.readInt()) {
                if (reply.readInt() == 0) return@repeat  // null entry
                // HwLight{id, ordinal, type, ...}, led by its size so newer fields can be skipped.
                val start = reply.dataPosition()
                val size = reply.readInt()
                val id = reply.readInt()
                val ordinal = reply.readInt()
                if (reply.readInt() == TYPE_APPLICATION) found += ordinal to id
                reply.setDataPosition(start + size)
            }
            found.sortedBy { it.first }.map { it.second }.toIntArray()
        } catch (e: Exception) {
            binder = null
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /**
     * Sets LED [led] (0 is the first) with
     * ILights.setLightState(id, HwLightState{color, flashMode, flashOnMs, flashOffMs, brightnessMode}).
     */
    fun set(led: Int, color: Int): Boolean {
        val b = service() ?: return false
        val id = (ids ?: find(b)?.also { ids = it })?.getOrNull(led) ?: return false
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
        const val TRANSACTION_GET_LIGHTS = 2
        /** LightType.APPLICATION. Pixel 11 Pro: the eight Hilight LEDs, HAL ids 1..8, ordinals 0..7. */
        const val TYPE_APPLICATION = 10
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
 * Decides what the LEDs show: the first of these that is active.
 *  1. a ringing call
 *  2. an alert (a timer that ran out, low battery)
 *  3. a notification pattern
 *  4. the timer countdown
 *  5. the live stream (music)
 *  6. the glow of notifications that are waiting
 *  7. the base (charging light)
 * A pattern that a higher one covers keeps its place in time, and shows for whatever is left
 * of it once the higher one ends.
 */
class Player(private val lights: Lights) {
    private class Countdown(val endAt: Long, val totalMs: Long, val color: Int)

    /** A pattern running since [start] (uptimeMillis) for as long as its effect lasts. */
    private class Shot(val effect: Effect, val start: Long) {
        fun over(now: Long) = now - start >= effect.durationMs
    }

    private val shown = IntArray(Renderer.LEDS)
    private val lock = Any()
    /** The pattern at each level, [Main.NOTE] to [Main.CALL]. */
    private val shots = arrayOfNulls<Shot>(3)
    private var animating = false
    private var base = IntArray(Renderer.LEDS)
    private var glow = IntArray(Renderer.LEDS)
    private var stream: IntArray? = null
    private var streamOwner: Any? = null
    private var timer: Countdown? = null

    /** Plays [effect] at [level], in place of what was playing there. */
    fun play(effect: Effect, level: Int = Main.NOTE) {
        synchronized(lock) {
            val now = SystemClock.uptimeMillis()
            shots[level.coerceIn(0, shots.lastIndex)] = Shot(effect, now)
            Wake.hold(shots.maxOf { if (it == null) 0L else it.start + it.effect.durationMs - now } + WAKE_MARGIN_MS)
            if (animating) return
            animating = true
        }
        Thread(::animate).start()
    }

    /** Draws the highest pattern frame by frame until none is left. */
    private fun animate() {
        val frame = IntArray(Renderer.LEDS)
        var next = SystemClock.uptimeMillis()
        while (true) {
            synchronized(lock) {
                val now = SystemClock.uptimeMillis()
                val shot = top(now)
                if (shot == null) {
                    animating = false
                    Wake.release()
                    push(still())
                    return
                }
                Renderer.frame(shot.effect, now - shot.start, frame)
                push(frame)
            }
            next += Lights.FRAME_MS
            val wait = next - SystemClock.uptimeMillis()
            if (wait > 0) Thread.sleep(wait) else next = SystemClock.uptimeMillis()
        }
    }

    /** Cuts the pattern at [level] short; null stops them all. */
    fun stop(level: Int?) = synchronized(lock) {
        if (level == null) shots.fill(null) else if (level in shots.indices) shots[level] = null
        show()
    }

    /** Everything dark, for when the helper is about to exit. */
    fun clear() = synchronized(lock) {
        shots.fill(null)
        stream = null
        timer = null
        glow = IntArray(Renderer.LEDS)
        base = IntArray(Renderer.LEDS)
        show()
    }

    fun setGlow(frame: IntArray) = synchronized(lock) {
        glow = frame
        show()
    }

    fun setBase(frame: IntArray) = synchronized(lock) {
        base = frame
        show()
    }

    fun setStream(owner: Any, frame: IntArray) = synchronized(lock) {
        streamOwner = owner
        stream = frame.copyOf()
        show()
    }

    fun endStream(owner: Any) = synchronized(lock) {
        if (streamOwner !== owner) return@synchronized
        streamOwner = null
        stream = null
        show()
    }

    /** Drains the LEDs until [endAt] (elapsedRealtime), then blinks. A zero [totalMs] cancels. */
    fun setTimer(endAt: Long, totalMs: Long, color: Int) {
        val mine = if (totalMs > 0) Countdown(endAt, totalMs, color) else null
        synchronized(lock) {
            timer = mine
            show()
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
                        show()
                        false
                    }
                }
                if (finished) {
                    play(Effect(Style.BLINK, listOf(mine.color), periodMs = 500, durationMs = 6000), Main.ALERT)
                    return@Thread
                }
            }
        }.start()
    }

    /** The highest pattern that is still running. Call with [lock] held. */
    private fun top(now: Long): Shot? {
        for (level in shots.indices.reversed()) {
            val shot = shots[level] ?: continue
            if (shot.over(now)) shots[level] = null else return shot
        }
        return null
    }

    /** What shows while no pattern is playing. Call with [lock] held. */
    private fun still(): IntArray {
        val t = timer
        if (t != null) {
            return IntArray(Renderer.LEDS).also {
                Renderer.meter(t.color, (t.endAt - SystemClock.elapsedRealtime()).toFloat() / t.totalMs, it)
            }
        }
        return stream ?: glow.takeIf { frame -> frame.any { it != 0 } } ?: base
    }

    /** Redraws after a change, unless a pattern is playing over it. Call with [lock] held. */
    private fun show() {
        if (top(SystemClock.uptimeMillis()) == null) push(still())
    }

    private fun push(frame: IntArray) {
        for (i in frame.indices) {
            if (frame[i] != shown[i] && lights.set(i, frame[i])) shown[i] = frame[i]
        }
    }

    private companion object {
        /** How much longer than the patterns the wakelock is asked for, in case they run late. */
        const val WAKE_MARGIN_MS = 5000L
    }
}
