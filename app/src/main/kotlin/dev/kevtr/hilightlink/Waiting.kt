package dev.kevtr.hilightlink

import android.content.Context
import dev.kevtr.hilightlink.engine.Effect

/**
 * Notifications whose light lingers (a glow or a blink) until they are dismissed. Like the old
 * notification LED, the newest one shows, and dismissing it brings back the one before.
 */
object Waiting {
    /** By notification key, oldest first. */
    private val waiting = LinkedHashMap<String, Effect>()

    /** Plays [effect] for a new notification, then shows whichever light is waiting. */
    @Synchronized
    fun post(context: Context, key: String, effect: Effect) {
        waiting.remove(key)
        if (effect.hold || effect.blink) waiting[key] = effect
        Leds.play(context, effect, waiting.values.lastOrNull())
    }

    @Synchronized
    fun remove(context: Context, key: String) {
        val shown = waiting.keys.lastOrNull() == key
        waiting.remove(key)
        if (shown) Leds.rest(context, waiting.values.lastOrNull())
    }

    /** The phone was unlocked: blinking has done its job, a steady glow stays until dismissed. */
    @Synchronized
    fun seen(context: Context) {
        var changed = waiting.values.removeAll { it.blink && !it.hold }
        for (entry in waiting.entries) {
            if (entry.value.blink) {
                entry.setValue(entry.value.copy(blink = false))
                changed = true
            }
        }
        if (changed) Leds.rest(context, waiting.values.lastOrNull())
    }

    /** Lights were paused; the caller switches the LEDs off. */
    @Synchronized
    fun clear() = waiting.clear()

    /** Brings the helper in line after this process restarted with nothing waiting. */
    @Synchronized
    fun sync(context: Context) {
        if (waiting.isEmpty()) Leds.rest(context, null)
    }
}
