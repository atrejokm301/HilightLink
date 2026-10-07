package dev.kevtr.hilightlink

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import dev.kevtr.hilightlink.engine.Effect

/** Replays the pattern of notifications that are still waiting, until the phone is unlocked. */
object Reminders {
    /** Waiting notifications by key, oldest first, with the pattern each one played. */
    private val pending = LinkedHashMap<String, Effect>()

    @Synchronized
    fun add(context: Context, key: String, effect: Effect) {
        pending.remove(key)
        pending[key] = effect
        schedule(context)
    }

    @Synchronized
    fun remove(context: Context, key: String) {
        if (pending.remove(key) != null && pending.isEmpty()) Alarms.cancel(context, Alarms.REMIND)
    }

    /** The user has looked at the phone, or lights were paused. */
    @Synchronized
    fun clear(context: Context) {
        pending.clear()
        Alarms.cancel(context, Alarms.REMIND)
    }

    @Synchronized
    fun fire(context: Context) {
        val config = RuleStore.config.value
        val effect = pending.values.lastOrNull() ?: return
        if (config.reminderMinutes <= 0 || config.paused) return
        // With the screen on the user is already looking; try again next time.
        if (!context.getSystemService(PowerManager::class.java).isInteractive) Leds.play(context, effect)
        schedule(context)
    }

    private fun schedule(context: Context) {
        val minutes = RuleStore.config.value.reminderMinutes
        if (minutes > 0) Alarms.set(context, Alarms.REMIND, SystemClock.elapsedRealtime() + minutes * 60_000L)
    }
}
