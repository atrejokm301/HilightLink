package dev.kevtr.hilightlink

import android.content.Context
import android.os.PowerManager
import dev.kevtr.hilightlink.daemon.Main
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

/** App-side handle on the root helper. All calls are queued on one background thread. */
object Leds {
    private val executor = Executors.newSingleThreadExecutor()

    fun play(context: Context, effect: Effect) {
        val app = context.applicationContext
        // Keep the CPU awake while the helper animates; the steady hold glow needs no CPU.
        wake(app, effect.durationMs + 3000L)
        executor.execute {
            request(app, JSONObject().put("cmd", "play").put("effect", effect.toJson()))
        }
    }

    fun off(context: Context) {
        val app = context.applicationContext
        executor.execute { request(app, JSONObject().put("cmd", "off"), startIfDown = false) }
    }

    /** Sets what each LED shows whenever nothing else is active; all zeros turns it off. */
    fun base(context: Context, frame: IntArray) {
        val app = context.applicationContext
        executor.execute {
            request(
                app,
                JSONObject().put("cmd", "base").put("frame", JSONArray(frame)),
                startIfDown = frame.any { it != 0 },
            )
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

    /** Holds the CPU awake for [ms] so the helper can update the LEDs with the screen off. */
    fun wake(context: Context, ms: Long) {
        context.applicationContext.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hilightlink:play")
            .acquire(ms)
    }

    /** Live frames over one open connection, for the music visualizer. Not thread safe. */
    class Stream internal constructor(private val socket: Socket) {
        private val out: OutputStream = socket.getOutputStream()
        private val line = StringBuilder(96)

        /** False once the helper has gone away. */
        fun write(frame: IntArray): Boolean = try {
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

        fun close() {
            runCatching { socket.close() }
        }
    }

    /** Opens a live stream, or null if the helper does not answer. Blocking. */
    fun openStream(context: Context): Stream? = try {
        val socket = Socket()
        socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), Main.PORT), 500)
        socket.soTimeout = 2000
        socket.tcpNoDelay = true
        val body = JSONObject().put("cmd", "stream").put("token", token(context.applicationContext))
        socket.getOutputStream().write("$body\n".toByteArray())
        if (socket.getInputStream().bufferedReader().readLine() == "ok") Stream(socket) else { socket.close(); null }
    } catch (e: Exception) {
        null
    }

    /** True if the helper answers, starting it through su first if needed. Blocking. */
    fun ensureRunning(context: Context): Boolean =
        request(context.applicationContext, JSONObject().put("cmd", "ping"))

    /** True if the helper answers right now. Blocking. */
    fun isRunning(context: Context): Boolean =
        request(context.applicationContext, JSONObject().put("cmd", "ping"), startIfDown = false)

    /** Stops the helper so the next request starts a fresh one from the current APK. Blocking. */
    fun restart(context: Context): Boolean {
        val app = context.applicationContext
        request(app, JSONObject().put("cmd", "quit"), startIfDown = false)
        Thread.sleep(300)
        return ensureRunning(app)
    }

    private fun request(context: Context, body: JSONObject, startIfDown: Boolean = true): Boolean {
        body.put("token", token(context))
        send(body)?.let { return it == "ok" }
        if (!startIfDown || !startHelper(context)) return false
        repeat(20) {
            Thread.sleep(250)
            send(body)?.let { return it == "ok" }
        }
        return false
    }

    /** One request line, one reply line. Null when nothing is listening. */
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
}
