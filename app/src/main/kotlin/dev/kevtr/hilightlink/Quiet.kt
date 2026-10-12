package dev.kevtr.hilightlink

import android.content.Context
import android.os.SystemClock
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * Quiet hours: a stretch of every day in which notification lights, the waiting glow and the
 * charging light stay dark or dimmed. The timer and the music visualizer are left alone.
 */
object Quiet {
    /** How much of their brightness lights keep when quiet hours only dim them. */
    const val DIM = 0.15f

    fun active(c: QuietConfig, minuteOfDay: Int): Boolean {
        if (!c.on || c.startMinute == c.endMinute) return false
        return if (c.startMinute < c.endMinute) {
            minuteOfDay in c.startMinute until c.endMinute
        } else {
            minuteOfDay >= c.startMinute || minuteOfDay < c.endMinute
        }
    }

    /** How bright lights may be right now: 1 outside quiet hours, 0 when they must stay dark. */
    fun level(): Float {
        val c = RuleStore.config.value.quiet
        val now = LocalTime.now()
        return when {
            !active(c, now.hour * 60 + now.minute) -> 1f
            c.dim -> DIM
            else -> 0f
        }
    }

    /** Brings the lights in line with the time of day and the settings. */
    fun refresh(context: Context) {
        Waiting.show(context)
        Charging.refresh(context)
        schedule(context)
    }

    /** Sets the alarm for the next time quiet hours start or end. */
    fun schedule(context: Context) {
        val c = RuleStore.config.value.quiet
        if (!c.on) return Alarms.cancel(context, Alarms.QUIET)
        val now = ZonedDateTime.now()
        val next = listOf(c.startMinute, c.endMinute).minOf { minute ->
            val at = now.with(LocalTime.of(minute / 60, minute % 60))
            Duration.between(now, if (at.isAfter(now)) at else at.plusDays(1)).toMillis()
        }
        // A moment late rather than early, so the alarm lands on the far side of the change.
        Alarms.set(context, Alarms.QUIET, SystemClock.elapsedRealtime() + next + 1000)
    }
}
