package dev.kevtr.hilightlink

import android.content.Context
import dev.kevtr.hilightlink.engine.Effect

/**
 * Notifications whose glow lingers until they are dismissed. Like the old notification LED,
 * the newest one shows, and dismissing it brings back the one before.
 */
object Waiting {
    /** By notification key, oldest first. */
    private val waiting = LinkedHashMap<String, Effect>()
    /** The notification whose pattern was played last, and so may still be playing. */
    private var played: String? = null

    /** Plays [effect] for a new notification, then shows whichever light is waiting. */
    @Synchronized
    fun post(context: Context, key: String, effect: Effect) {
        waiting.remove(key)
        if (effect.hold) waiting[key] = effect
        played = key
        Leds.play(context, effect, waiting.values.lastOrNull())
    }

    @Synchronized
    fun remove(context: Context, key: String) {
        val shown = waiting.keys.lastOrNull() == key || played == key
        waiting.remove(key)
        if (played == key) played = null
        if (shown) Leds.rest(context, waiting.values.lastOrNull())
    }

    /** Lights were paused; the caller switches the LEDs off. */
    @Synchronized
    fun clear() {
        waiting.clear()
        played = null
    }

    /** Brings the helper in line after this process restarted with nothing waiting. */
    @Synchronized
    fun sync(context: Context) {
        if (waiting.isEmpty()) Leds.rest(context, null)
    }
}
