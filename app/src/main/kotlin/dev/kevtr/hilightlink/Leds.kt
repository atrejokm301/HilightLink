package dev.kevtr.hilightlink

import android.content.Context
import android.os.PowerManager
import dev.kevtr.hilightlink.daemon.Main
import dev.kevtr.hilightlink.daemon.Player
import dev.kevtr.hilightlink.daemon.command
import dev.kevtr.hilightlink.engine.Effect
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * Handle on whatever drives the LEDs. That is the engine inside the system when there is one
 * (see [Bridge]), and the root helper otherwise. All calls are queued on one background thread.
 */
object Leds {
    private val executor = Executors.newSingleThreadExecutor()

    /** Set where this code is the engine inside the system: requests go straight to its player. */
    @Volatile
    var local: Player? = null

    /** Whether the engine inside the system runs the lights, rather than this process. */
    fun remote(context: Context): Boolean = local == null && Bridge.binder(context) != null

    /**
     * Plays [effect] once at [level]: [Main.NOTE] for a notification, [Main.ALERT] and
     * [Main.CALL] for what must show over one.
     */
    fun play(context: Context, effect: Effect, level: Int = Main.NOTE) {
        val app = context.applicationContext
        // Keep the CPU awake until the request is delivered; the player holds its own wakelock from there.
        if (local == null) wake(app, minOf(effect.durationMs + 3000L, MAX_WAKE_MS))
        executor.execute {
            request(app, JSONObject().put("cmd", "play").put("effect", effect.toJson()).put("level", level))
        }
    }

    /** Cuts the pattern at [level] short. */
    fun stop(context: Context, level: Int = Main.NOTE) = stop(context, JSONObject().put("cmd", "stop").put("level", level))

    /** Cuts every pattern short. */
    fun stopAll(context: Context) = stop(context, JSONObject().put("cmd", "stop"))

    private fun stop(context: Context, body: JSONObject) {
        val app = context.applicationContext
        executor.execute { request(app, body, startIfDown = false) }
    }

    /** Sets the glow of the notifications that are waiting, one colour per LED; all zeros is none. */
    fun glow(context: Context, frame: IntArray) = frame(context, "glow", frame)

    /** Sets what each LED shows whenever nothing else is active; all zeros turns it off. */
    fun base(context: Context, frame: IntArray) = frame(context, "base", frame)

    private fun frame(context: Context, cmd: String, frame: IntArray) {
        val app = context.applicationContext
        executor.execute {
            request(app, JSONObject().put("cmd", cmd).put("frame", JSONArray(frame)), startIfDown = frame.any { it != 0 })
        }
    }

    /** Countdown that drains until [endAt] (elapsedRealtime). A zero [totalMs] cancels it. */
    fun timer(context: Context, endAt: Long, totalMs: Long, color: Int) {
        val app = context.applicationContext
        executor.execute {
            request(
                app,
                JSONObject().put("cmd", "timer").put("endAt", endAt).put("totalMs", totalMs).put("color", color),
                startIfDown = totalMs > 0,
            )
        }
    }

    /** Hands the engine inside the system the settings it is to work from. Nothing without one. */
    fun config(context: Context, json: String) {
        val app = context.applicationContext
        executor.execute {
            if (remote(app)) Bridge.ask(app, JSONObject().put("cmd", "config").put("config", json))
        }
    }

    /** Sends the engine inside the system a request of its own; [then] gets the reply, or null without one. */
    fun engine(context: Context, body: JSONObject, then: (String?) -> Unit = {}) {
        val app = context.applicationContext
        executor.execute { then(Bridge.ask(app, body)) }
    }

    /** Holds the CPU awake for [ms] so the LEDs can be updated with the screen off. */
    fun wake(context: Context, ms: Long) {
        context.applicationContext.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hilightlink:play")
            .acquire(ms)
    }

    /** Live frames over one open connection, for the music visualizer. Not thread safe. */
    interface Stream {
        /** False once the other end has gone away. */
        fun write(frame: IntArray): Boolean

        fun close()
    }

    private class SocketStream(private val socket: Socket) : Stream {
        private val out: OutputStream = socket.getOutputStream()
        private val line = StringBuilder(96)

        override fun write(frame: IntArray): Boolean = try {
            line.setLength(0)
            for (i in frame.indices) {
                if (i > 0) line.append(',')
                line.append(frame[i])
            }
            line.append('\n')
            out.write(line.toString().toByteArray())
            true
        } catch (e: Exception) {
            false
        }

        override fun close() {
            runCatching { socket.close() }
        }
    }

    /** Opens a live stream, or null if nothing answers. Blocking. */
    fun openStream(context: Context): Stream? {
        val app = context.applicationContext
        if (remote(app)) return Bridge.openStream(app)
        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), Main.PORT), 500)
            socket.soTimeout = 2000
            socket.tcpNoDelay = true
            val body = JSONObject().put("cmd", "stream").put("token", token(app))
            socket.getOutputStream().write("$body\n".toByteArray())
            if (socket.getInputStream().bufferedReader().readLine() == "ok") SocketStream(socket) else { socket.close(); null }
        } catch (e: Exception) {
            null
        }
    }

    /** True if the lights are being run, starting the helper through su first if needed. Blocking. */
    fun ensureRunning(context: Context): Boolean =
        request(context.applicationContext, JSONObject().put("cmd", "ping"))

    /** True if the lights are being run right now. Blocking. */
    fun isRunning(context: Context): Boolean =
        request(context.applicationContext, JSONObject().put("cmd", "ping"), startIfDown = false)

    /** Stops the helper so the next request starts a fresh one from the current APK. Blocking. */
    fun restart(context: Context): Boolean {
        val app = context.applicationContext
        request(app, JSONObject().put("cmd", "quit"), startIfDown = false)
        Thread.sleep(300)
        return ensureRunning(app)
    }

    /** A notification channel of some app: its id and the name the app gave it. */
    data class Channel(val id: String, val name: String)

    /** The notification channels of [pkg], or null if they could not be listed. Blocking. */
    fun channels(context: Context, pkg: String): List<Channel>? = runCatching {
        val app = context.applicationContext
        val uid = app.packageManager.getApplicationInfo(pkg, 0).uid
        val reply = ask(app, JSONObject().put("cmd", "channels").put("pkg", pkg).put("uid", uid), startIfDown = true)
        val list = JSONObject(reply!!).getJSONArray("channels")
        (0 until list.length()).map { list.getJSONObject(it).run { Channel(getString("id"), getString("name")) } }
    }.getOrNull()

    /** The engine is being unloaded: let its thread go. */
    fun close() {
        executor.shutdown()
    }

    private fun request(context: Context, body: JSONObject, startIfDown: Boolean = true): Boolean =
        ask(context, body, startIfDown) == "ok"

    /** The reply to [body], or null if nothing answered. */
    private fun ask(context: Context, body: JSONObject, startIfDown: Boolean): String? {
        local?.let { return it.command(body) }
        Bridge.ask(context, body)?.let { return it }
        body.put("token", token(context))
        send(body)?.let { return it }
        if (!startIfDown || !startHelper(context)) return null
        repeat(20) {
            Thread.sleep(250)
            send(body)?.let { return it }
        }
        return null
    }

    /** One request line to the helper, one reply line. Null when nothing is listening. */
    private fun send(body: JSONObject): String? = try {
        Socket().use { s ->
            s.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), Main.PORT), 500)
            s.soTimeout = 2000
            s.getOutputStream().write("$body\n".toByteArray())
            s.getInputStream().bufferedReader().readLine()
        }
    } catch (e: Exception) {
        null
    }

    private fun startHelper(context: Context): Boolean = try {
        val apk = context.applicationInfo.sourceDir
        // Mask 1 pins the helper to CPU 0, an efficiency core, same as the module's service.sh.
        val cmd = "nohup taskset 1 app_process -Djava.class.path=$apk /system/bin --nice-name=hilightlinkd " +
            "${Main::class.java.name} ${context.packageName} >/dev/null 2>&1 &"
        ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start().waitFor() == 0
    } catch (e: Exception) {
        false
    }

    /** Shared secret the helper reads from our private storage, so other apps cannot drive it. */
    @Synchronized
    private fun token(context: Context): String {
        val file = File(context.filesDir, "token")
        if (!file.exists()) {
            val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
            file.writeText(bytes.joinToString("") { "%02x".format(it) })
        }
        return file.readText().trim()
    }

    private const val MAX_WAKE_MS = 65_000L
}
