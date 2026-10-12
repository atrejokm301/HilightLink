package dev.kevtr.hilightlink

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler

/** Exact wake-ups, so reminders, the timer light and quiet hours keep working while the phone sleeps. */
object Alarms {
    const val REMIND = "dev.kevtr.hilightlink.REMIND"
    const val TIMER = "dev.kevtr.hilightlink.TIMER"
    const val QUIET = "dev.kevtr.hilightlink.QUIET"

    /**
     * How alarms come back inside the system, where there is no receiver of ours to send them
     * to: straight to a listener, on [handler].
     */
    class Direct(val context: Context, val handler: Handler) {
        val listeners = HashMap<String, AlarmManager.OnAlarmListener>()
    }

    /** Set where this code is the engine inside the system. */
    @Volatile
    var direct: Direct? = null

    /** One alarm per [action] and [slot]; setting it again moves it. [atElapsed] is elapsedRealtime. */
    fun set(context: Context, action: String, atElapsed: Long, slot: Int = 0) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val d = direct
        if (d == null) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, atElapsed, intent(context, action, slot))
            return
        }
        val listener = synchronized(d) {
            d.listeners.getOrPut("$action/$slot") { AlarmManager.OnAlarmListener { fire(d.context, action, slot) } }
        }
        // The system's own alarms go off on time while the phone is idle without asking.
        alarms.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, atElapsed, action, listener, d.handler)
    }

    fun cancel(context: Context, action: String, slot: Int = 0) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val d = direct ?: return alarms.cancel(intent(context, action, slot))
        synchronized(d) { d.listeners["$action/$slot"] }?.let(alarms::cancel)
    }

    /** The engine is being unloaded: nothing of it may be called back any more. */
    fun cancelAll(context: Context) {
        val d = direct ?: return
        val alarms = context.getSystemService(AlarmManager::class.java)
        synchronized(d) { d.listeners.values.toList() }.forEach(alarms::cancel)
    }

    /** An alarm went off. */
    fun fire(context: Context, action: String?, slot: Int) {
        when (action) {
            REMIND -> Reminders.fire(context)
            TIMER -> TimerLight.tick(context, slot)
            QUIET -> Quiet.refresh(context)
        }
    }

    private fun intent(context: Context, action: String, slot: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context.applicationContext,
            slot,
            Intent(context.applicationContext, AlarmReceiver::class.java).setAction(action).putExtra(EXTRA_SLOT, slot),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    const val EXTRA_SLOT = "slot"
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        RuleStore.init(context)
        // Left over from before the engine inside the system took the lights over.
        if (Leds.remote(context)) return
        Alarms.fire(context, intent.action, intent.getIntExtra(Alarms.EXTRA_SLOT, 0))
    }
}
