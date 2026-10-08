package dev.kevtr.hilightlink

import android.content.Context
import android.os.SystemClock
import dev.kevtr.hilightlink.engine.Renderer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs

/** Countdown shown on the LEDs: they drain as the time runs out, then blink. */
object TimerLight {
    /** [key] is the notification being followed, or null for a timer started in the app. */
    data class Running(val endAt: Long, val totalMs: Long, val key: String?)

    private val state = MutableStateFlow<Running?>(null)
    val running: StateFlow<Running?> get() = state

    @Synchronized
    fun start(context: Context, totalMs: Long, key: String? = null, endAt: Long = SystemClock.elapsedRealtime() + totalMs) {
        state.value = Running(endAt, totalMs, key)
        push(context)
        // Wake the phone each time an LED goes out so the helper can redraw, and once at the end.
        val now = SystemClock.elapsedRealtime()
        for (slot in 1 until Renderer.LEDS) {
            val at = endAt - totalMs * slot / Renderer.LEDS
            if (at > now) Alarms.set(context, Alarms.TIMER, at, slot) else Alarms.cancel(context, Alarms.TIMER, slot)
        }
        Alarms.set(context, Alarms.TIMER, endAt, FINAL_SLOT)
    }

    @Synchronized
    fun cancel(context: Context) {
        state.value = null
        push(context)
        for (slot in 0 until Renderer.LEDS) Alarms.cancel(context, Alarms.TIMER, slot)
    }

    /** A countdown notification was posted or updated. */
    @Synchronized
    fun follow(context: Context, key: String, endAt: Long) {
        val current = state.value
        val now = SystemClock.elapsedRealtime()
        if (endAt <= now) return unfollow(context, key)
        if (current?.key == key) {
            if (abs(current.endAt - endAt) < 1500) return
            // Time was added or removed: keep the scale, so the bar jumps rather than refills.
            start(context, (current.totalMs + endAt - current.endAt).coerceAtLeast(endAt - now), key, endAt)
        } else {
            start(context, endAt - now, key, endAt)
        }
    }

    /** A notification went away or stopped counting down. */
    @Synchronized
    fun unfollow(context: Context, key: String) {
        val r = state.value?.takeIf { it.key == key } ?: return
        // As a timer runs out its notification changes or goes away, a moment before or after
        // the end. That is not a cancel: leave the helper to blink.
        if (r.endAt - SystemClock.elapsedRealtime() < END_GRACE_MS) state.value = null else cancel(context)
    }

    /** Sends the current countdown, or its absence, to the helper. */
    fun push(context: Context) {
        val config = RuleStore.config.value
        val r = state.value
        if (r == null || config.paused || r.endAt <= SystemClock.elapsedRealtime()) {
            Leds.timer(context, 0, 0, 0)
        } else {
            Leds.timer(context, r.endAt, r.totalMs, config.timer.color)
        }
    }

    /** An alarm fired: the helper redraws by itself, it only needs the CPU awake for a moment. */
    fun tick(context: Context, slot: Int) {
        val final = slot == FINAL_SLOT
        Leds.wake(context, if (final) 9000 else 2500)
        if (final) state.value = null
    }

    private const val FINAL_SLOT = 0
    private const val END_GRACE_MS = 1500L
}
