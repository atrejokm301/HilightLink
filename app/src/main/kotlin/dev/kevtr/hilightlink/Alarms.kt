package dev.kevtr.hilightlink

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Exact wake-ups, so reminders, the timer light and quiet hours keep working while the phone sleeps. */
object Alarms {
    const val REMIND = "dev.kevtr.hilightlink.REMIND"
    const val TIMER = "dev.kevtr.hilightlink.TIMER"
    const val QUIET = "dev.kevtr.hilightlink.QUIET"

    /** One alarm per [action] and [slot]; setting it again moves it. [atElapsed] is elapsedRealtime. */
    fun set(context: Context, action: String, atElapsed: Long, slot: Int = 0) {
        context.getSystemService(AlarmManager::class.java)
            .setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, atElapsed, intent(context, action, slot))
    }

    fun cancel(context: Context, action: String, slot: Int = 0) {
        context.getSystemService(AlarmManager::class.java).cancel(intent(context, action, slot))
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
        when (intent.action) {
            Alarms.REMIND -> Reminders.fire(context)
            Alarms.TIMER -> TimerLight.tick(context, intent.getIntExtra(Alarms.EXTRA_SLOT, 0))
            Alarms.QUIET -> Quiet.refresh(context)
        }
    }
}
