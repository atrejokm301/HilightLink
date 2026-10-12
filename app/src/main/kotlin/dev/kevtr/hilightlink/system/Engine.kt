package dev.kevtr.hilightlink.system

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import android.os.PowerManager
import android.os.UserHandle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import dev.kevtr.hilightlink.Alarms
import dev.kevtr.hilightlink.Bridge
import dev.kevtr.hilightlink.Charging
import dev.kevtr.hilightlink.Control
import dev.kevtr.hilightlink.Leds
import dev.kevtr.hilightlink.NotificationLights
import dev.kevtr.hilightlink.Quiet
import dev.kevtr.hilightlink.Reminders
import dev.kevtr.hilightlink.RuleStore
import dev.kevtr.hilightlink.TimerLight
import dev.kevtr.hilightlink.daemon.Lights
import dev.kevtr.hilightlink.daemon.Player
import dev.kevtr.hilightlink.daemon.Waker
import dev.kevtr.hilightlink.daemon.command
import dev.kevtr.hilightlink.engine.Renderer
import org.json.JSONObject
import java.io.File

/**
 * The lights, run from inside the system process instead of from the app and its root helper.
 * [Loader] starts it there when the app is switched on for the System Framework in an Xposed
 * framework. In here the system hands over every notification without notification access,
 * the lights service can be reached without root, and nothing puts the process to sleep.
 *
 * The app stays the place where the settings are made. It finds the engine through [Bridge],
 * sends it the settings, and leaves the notification, call, charging and timer lights to it.
 */
object Engine {
    private const val TAG = "HilightLink"
    private var running: Running? = null

    @JvmStatic
    @Synchronized
    fun start(system: Context) {
        if (running != null) return
        val next = Running(system)
        try {
            next.start()
            running = next
        } catch (t: Throwable) {
            // Half started is worse than not at all: the app falls back on the root helper.
            next.stop()
            throw t
        }
    }

    @JvmStatic
    @Synchronized
    fun stop() {
        running?.stop()
        running = null
    }

    private class Running(private val system: Context) {
        /** The shared code asks for the application context, which the system's own does not have to offer. */
        private val context = object : ContextWrapper(system) {
            override fun getApplicationContext(): Context = this
        }
        private val thread = HandlerThread("hilightlink")
        private lateinit var handler: Handler
        private val player = Player(Lights(), PowerWake(context))
        private val listener = Listener()
        private val lights = NotificationLights(context) { listener.silenced(it) }
        private val binder = BridgeBinder()
        private val announcement = Intent(Bridge.ACTION).putExtras(Bundle().apply { putBinder(Bridge.EXTRA_BINDER, binder) })
        private var appUid = -1
        @Volatile
        private var alive = false
        /** The app's stream of live frames, by the token it opened it with. */
        private var stream: IBinder? = null

        private val battery = receiver { Charging.refresh(context, it) }
        /** Unlocking the phone counts as having seen what was waiting. */
        private val unlocked = receiver { Reminders.clear(context) }
        /** The clock or the time zone was changed, which moves quiet hours. */
        private val clockSet = receiver { Quiet.refresh(context) }

        fun start() {
            thread.start()
            handler = Handler(thread.looper)
            appUid = context.packageManager.getApplicationInfo(Bridge.PKG, 0).uid
            RuleStore.init(File(DIR))
            Leds.local = player
            Alarms.direct = Alarms.Direct(context, handler)
            alive = true

            // Sticky broadcast: delivers the current battery state straight away.
            listen(battery, Intent.ACTION_BATTERY_CHANGED)
            listen(unlocked, Intent.ACTION_USER_PRESENT)
            listen(clockSet, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)
            // The way System UI is told of notifications: no notification access to grant.
            NotificationListenerService::class.java
                .getMethod("registerAsSystemService", Context::class.java, ComponentName::class.java, Int::class.javaPrimitiveType)
                .invoke(listener, system, ComponentName("android", "HilightLink"), currentUser())
            context.sendStickyBroadcastAsUser(announcement, allUsers())
            Log.i(TAG, "Engine running, ${RuleStore.config.value.rules.size} rules")
        }

        fun stop() {
            alive = false
            attempt { context.removeStickyBroadcastAsUser(announcement, allUsers()) }
            attempt { NotificationListenerService::class.java.getMethod("unregisterAsSystemService").invoke(listener) }
            listOf(battery, unlocked, clockSet).forEach { attempt { context.unregisterReceiver(it) } }
            attempt { Alarms.cancelAll(context) }
            attempt { player.clear() }
            Leds.close()
            thread.quitSafely()
            Log.i(TAG, "Engine stopped")
        }

        private fun listen(receiver: BroadcastReceiver, vararg actions: String) {
            val filter = IntentFilter().apply { actions.forEach(::addAction) }
            context.registerReceiver(receiver, filter, null, handler, Context.RECEIVER_NOT_EXPORTED)
        }

        private fun receiver(act: (Intent) -> Unit) = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) = attempt { if (alive) act(intent) }
        }

        /** Runs [work] on the engine's thread. */
        private fun post(work: () -> Unit) {
            handler.post { attempt { if (alive) work() } }
        }

        /** The requests of the helper, and the ones only the engine has. */
        private fun command(req: JSONObject): String = when (req.optString("cmd")) {
            "config" -> {
                val text = req.getString("config")
                post { if (RuleStore.importJson(text)) Control.lights(context) }
                "ok"
            }
            "timerStart" -> { TimerLight.start(context, req.getLong("totalMs"), endAt = req.getLong("endAt")); "ok" }
            "timerCancel" -> { TimerLight.cancel(context); "ok" }
            "timerState" -> TimerLight.stateJson()
            // There is no helper to restart.
            "quit" -> "ok"
            else -> player.command(req) ?: "unknown"
        }

        /**
         * Told of notifications the way System UI is. The callbacks arrive on the system's main
         * thread, so they only hand the work on.
         */
        private inner class Listener : NotificationListenerService() {
            override fun onListenerConnected() = post {
                lights.connected(runCatching { activeNotifications }.getOrNull().orEmpty().toList())
            }

            override fun onNotificationPosted(sbn: StatusBarNotification) = post { lights.posted(sbn) }

            override fun onNotificationRemoved(sbn: StatusBarNotification) = post { lights.removed(sbn) }

            /** Whether Do Not Disturb is holding this notification back. */
            fun silenced(sbn: StatusBarNotification): Boolean {
                val ranking = Ranking()
                return runCatching { currentRanking.getRanking(sbn.key, ranking) && !ranking.matchesInterruptionFilter() }
                    .getOrDefault(false)
            }
        }

        /** What the app talks to; see [Bridge] for the calls. Only the app may. */
        private inner class BridgeBinder : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code !in Bridge.CALL..Bridge.STREAM_CLOSE) return super.onTransact(code, data, reply, flags)
                if (getCallingUid() != appUid || reply == null) return false
                // From here on as the system itself, or its wakelocks and alarms would be the app's.
                val caller = clearCallingIdentity()
                return try {
                    when (code) {
                        Bridge.CALL -> {
                            val out = if (alive) command(JSONObject(data.readString()!!)) else Bridge.GONE
                            reply.writeNoException()
                            reply.writeString(out)
                        }
                        Bridge.STREAM_OPEN -> {
                            val token = data.readStrongBinder()
                            // The stream ends with the app's process, however that goes.
                            token.linkToDeath({ endStream(token) }, 0)
                            reply.writeNoException()
                            reply.writeString(if (alive) "ok" else Bridge.GONE)
                        }
                        Bridge.STREAM_FRAME -> {
                            val token = data.readStrongBinder()
                            val frame = data.createIntArray()
                            val shown = alive && frame != null && frame.size == Renderer.LEDS
                            if (shown) {
                                synchronized(binder) {
                                    stream = token
                                    player.setStream(binder, frame!!)
                                }
                            }
                            reply.writeNoException()
                            reply.writeInt(if (shown) 1 else 0)
                        }
                        else -> {
                            endStream(data.readStrongBinder())
                            reply.writeNoException()
                        }
                    }
                    true
                } catch (t: Throwable) {
                    Log.e(TAG, "Request from the app failed", t)
                    false
                } finally {
                    restoreCallingIdentity(caller)
                }
            }

            private fun endStream(token: IBinder) {
                synchronized(binder) {
                    if (stream != token) return
                    stream = null
                    player.endStream(binder)
                }
            }
        }

        private fun currentUser(): Int = runCatching {
            Class.forName("android.app.ActivityManager").getMethod("getCurrentUser").invoke(null) as Int
        }.getOrDefault(0)

        private fun allUsers(): UserHandle = UserHandle::class.java.getField("ALL").get(null) as UserHandle

        /** Nothing may be thrown into the system process. */
        private inline fun attempt(work: () -> Unit) {
            try {
                work()
            } catch (t: Throwable) {
                Log.e(TAG, "Engine", t)
            }
        }
    }

    /** A wakelock of the system's own, in place of the kernel one the root helper takes. */
    private class PowerWake(context: Context) : Waker {
        private val lock = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hilightlink:lights")
            .apply { setReferenceCounted(false) }

        override fun hold(ms: Long) = lock.acquire(ms)

        override fun release() {
            if (lock.isHeld) lock.release()
        }
    }

    /** Where the engine keeps its copy of the settings. */
    private const val DIR = "/data/system/hilightlink"
}
