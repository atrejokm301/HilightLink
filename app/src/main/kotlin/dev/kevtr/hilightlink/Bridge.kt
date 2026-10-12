package dev.kevtr.hilightlink

import android.content.Context
import android.content.IntentFilter
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import org.json.JSONObject

/**
 * The app's line to the engine inside the system (system/Engine.kt), which runs the lights when
 * the app is switched on for the System Framework in an Xposed framework. The engine announces
 * itself with a sticky broadcast that carries its binder, so finding it takes no waiting, and
 * without one everything goes through the root helper as before.
 */
object Bridge {
    const val PKG = "dev.kevtr.hilightlink"
    const val ACTION = "dev.kevtr.hilightlink.ENGINE"
    const val EXTRA_BINDER = "binder"

    /** One request as JSON text, one reply as text: the helper's requests and a few more. */
    const val CALL = IBinder.FIRST_CALL_TRANSACTION
    /** A token binder that stands for the stream; the stream ends with the process that made it. */
    const val STREAM_OPEN = CALL + 1
    /** The token, then one colour per LED. The reply is whether the engine is still there. */
    const val STREAM_FRAME = CALL + 2
    const val STREAM_CLOSE = CALL + 3

    /** What an engine that has been unloaded answers; a newer one has taken its place. */
    const val GONE = "gone"

    @Volatile
    private var found: IBinder? = null
    /** When the engine was last looked for in vain (elapsedRealtime). */
    @Volatile
    private var missedAt = -RETRY_MS

    /** The engine, or null if this phone is not running one. */
    fun binder(context: Context): IBinder? {
        found?.let { if (it.isBinderAlive) return it }
        val now = SystemClock.elapsedRealtime()
        if (now - missedAt < RETRY_MS) return null
        val binder = runCatching {
            context.applicationContext.registerReceiver(null, IntentFilter(ACTION))?.extras?.getBinder(EXTRA_BINDER)
        }.getOrNull()?.takeIf { it.isBinderAlive }
        found = binder
        if (binder == null) missedAt = now
        return binder
    }

    /** The engine's reply to [body], or null without an engine. */
    fun ask(context: Context, body: JSONObject): String? {
        // Twice: the first engine found may just have been replaced by a newer one.
        repeat(2) {
            val binder = binder(context) ?: return null
            val reply = transact(binder, CALL) { it.writeString(body.toString()) }?.use { it.readString() }
            if (reply != null && reply != GONE) return reply
            found = null
        }
        return null
    }

    fun openStream(context: Context): Leds.Stream? {
        val binder = binder(context) ?: return null
        val token = Binder()
        val reply = transact(binder, STREAM_OPEN) { it.writeStrongBinder(token) }?.use { it.readString() }
        return if (reply == "ok") Stream(binder, token) else null
    }

    private class Stream(private val binder: IBinder, private val token: IBinder) : Leds.Stream {
        override fun write(frame: IntArray): Boolean =
            transact(binder, STREAM_FRAME) {
                it.writeStrongBinder(token)
                it.writeIntArray(frame)
            }?.use { it.readInt() != 0 } ?: false

        override fun close() {
            transact(binder, STREAM_CLOSE) { it.writeStrongBinder(token) }?.recycle()
        }
    }

    /** The reply parcel, past its exception header and for the caller to recycle; null if the call failed. */
    private fun transact(binder: IBinder, code: Int, write: (Parcel) -> Unit): Parcel? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            write(data)
            check(binder.transact(code, data, reply, 0))
            reply.readException()
            reply
        } catch (e: Exception) {
            reply.recycle()
            null
        } finally {
            data.recycle()
        }
    }

    private inline fun <T> Parcel.use(read: (Parcel) -> T): T = try { read(this) } finally { recycle() }

    /** How long to go without looking again on a phone that has no engine. */
    private const val RETRY_MS = 3000L
}
