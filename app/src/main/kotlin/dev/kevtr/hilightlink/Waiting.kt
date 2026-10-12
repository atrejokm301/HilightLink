package dev.kevtr.hilightlink

import android.content.Context
import android.os.SystemClock
import dev.kevtr.hilightlink.engine.Effect
import dev.kevtr.hilightlink.engine.Renderer

/**
 * Notifications whose glow lingers until they are dismissed. Each colour that is waiting gets
 * its share of the LEDs, the oldest first, so one look tells which apps want attention.
 */
object Waiting {
    /** By notification key, oldest first. */
    private val waiting = LinkedHashMap<String, Effect>()
    /** The notification whose pattern was played last, and when that pattern ends (elapsedRealtime). */
    private var played: String? = null
    private var playedUntil = 0L

    /** Plays [effect] for a new notification and adds its glow to the ones waiting. */
    @Synchronized
    fun post(context: Context, key: String, effect: Effect) {
        waiting.remove(key)
        if (effect.hold) waiting[key] = effect
        show(context)
        // In quiet hours that stay dark the glow still builds up, to show once they are over.
        val level = Quiet.level()
        if (level <= 0f) return
        played = key
        playedUntil = SystemClock.elapsedRealtime() + effect.durationMs
        Leds.play(context, effect.copy(brightness = effect.brightness * level))
    }

    @Synchronized
    fun remove(context: Context, key: String) {
        if (waiting.remove(key) != null) show(context)
        if (played == key) {
            played = null
            if (SystemClock.elapsedRealtime() < playedUntil) Leds.stop(context)
        }
    }

    /** Lights were paused: nothing is waiting any more. */
    @Synchronized
    fun clear(context: Context) {
        waiting.clear()
        played = null
        show(context)
    }

    /** Sends the glow of everything that is waiting to the helper; dark while paused or quiet. */
    @Synchronized
    fun show(context: Context) {
        val out = IntArray(Renderer.LEDS)
        val level = if (RuleStore.config.value.paused) 0f else Quiet.level()
        if (level > 0f) {
            // A colour that waits more than once counts where it came in last.
            val colors = waiting.values.map { Renderer.scale(Renderer.holdColor(it), level) }
            Renderer.segments(colors.asReversed().distinct().asReversed(), out)
        }
        Leds.glow(context, out)
    }
}
