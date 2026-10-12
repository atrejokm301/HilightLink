package dev.kevtr.hilightlink

import android.app.Notification
import android.content.Context
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import java.util.concurrent.ConcurrentHashMap

/**
 * Turns notifications into light patterns according to the saved rules. Fed by the app's
 * notification listener, or by the listener of the engine inside the system.
 *
 * [silenced] tells whether Do Not Disturb is holding a notification back.
 */
class NotificationLights(private val context: Context, private val silenced: (StatusBarNotification) -> Boolean) {
    private val lastPlayed = HashMap<String, Long>()
    /** Keys of the notifications that are showing, to tell an update from a new one. */
    private val showing = ConcurrentHashMap.newKeySet<String>()

    /** The listener is connected; [current] is what is showing. May block. */
    fun connected(current: List<StatusBarNotification>) {
        current.forEach { showing.add(it.key) }
        // Warm the helper up so the first notification after boot is not delayed.
        Leds.ensureRunning(context)
        // A helper that outlived this process may still show a light nobody is tracking.
        Calls.clear(context)
        Quiet.refresh(context)
        current.filter(Calls::isRinging).forEach(::ring)
        // Pick up a countdown that was already running.
        if (RuleStore.config.value.timer.followClock) {
            current.forEach { sbn ->
                countdownEnd(sbn)?.let { TimerLight.follow(context, sbn.key, it) }
            }
        }
    }

    fun posted(sbn: StatusBarNotification) {
        if (sbn.packageName == Bridge.PKG) return
        val update = !showing.add(sbn.key)
        val config = RuleStore.config.value
        if (config.timer.followClock) {
            val end = countdownEnd(sbn)
            if (end != null) return TimerLight.follow(context, sbn.key, end)
            TimerLight.unfollow(context, sbn.key)
        }
        if (sbn.notification.category == Notification.CATEGORY_CALL) {
            // The same notification stays up once the call is answered, no longer ringing.
            if (Calls.isRinging(sbn)) ring(sbn) else Calls.end(context, sbn.key)
        }
        if (config.paused || sbn.isOngoing) return
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        // Progress bars, media controls and the like ask to alert only the first time.
        if (update && sbn.notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0) return
        val rule = RuleStore.ruleFor(sbn.packageName, sbn.notification.channelId) { titleOf(sbn) } ?: return
        if (quieted(sbn)) return
        if (config.onlyScreenOff && context.getSystemService(PowerManager::class.java).isInteractive) return

        // Apps often post the same notification several times in a burst.
        val now = SystemClock.elapsedRealtime()
        val last = lastPlayed[sbn.key]
        if (last != null && now - last < REPEAT_GUARD_MS) return
        lastPlayed[sbn.key] = now

        Waiting.post(context, sbn.key, rule.effect)
        Reminders.add(context, sbn.key, rule.effect)
    }

    fun removed(sbn: StatusBarNotification) {
        showing.remove(sbn.key)
        lastPlayed.remove(sbn.key)
        Reminders.remove(context, sbn.key)
        TimerLight.unfollow(context, sbn.key)
        Calls.end(context, sbn.key)
        Waiting.remove(context, sbn.key)
    }

    private fun ring(sbn: StatusBarNotification) {
        if (!quieted(sbn)) Calls.ring(context, sbn.key)
    }

    /** Whether Do Not Disturb is holding this notification back, and the lights should respect that. */
    private fun quieted(sbn: StatusBarNotification): Boolean = RuleStore.config.value.respectDnd && silenced(sbn)

    /** Who or what the notification is from: its title and, for a group chat, the name of the group. */
    private fun titleOf(sbn: StatusBarNotification): String {
        val extras = sbn.notification.extras ?: return ""
        return listOfNotNull(
            extras.getCharSequence(Notification.EXTRA_TITLE),
            extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE),
        ).joinToString("\n")
    }

    /**
     * When a running countdown shown by this notification ends (elapsedRealtime), or null.
     * Covers the metric style current Clock timers use and the older chronometer style.
     */
    private fun countdownEnd(sbn: StatusBarNotification): Long? {
        if (!sbn.isOngoing) return null
        val extras = sbn.notification.extras ?: return null
        val now = SystemClock.elapsedRealtime()
        val end = runCatching {
            extras.getParcelableArrayList(EXTRA_METRICS, Bundle::class.java)
                ?.firstNotNullOfOrNull { metric ->
                    metric.getBundle("value")
                        ?.takeIf { it.getBoolean("countDown") && it.containsKey("zeroElapsedRealtime") }
                        ?.getLong("zeroElapsedRealtime")
                }
        }.getOrNull()
            ?: if (extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER) &&
                extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN)
            ) now + sbn.notification.`when` - System.currentTimeMillis() else null
        return end?.takeIf { it > now && it - now < MAX_COUNTDOWN_MS }
    }

    private companion object {
        const val REPEAT_GUARD_MS = 2000L
        const val MAX_COUNTDOWN_MS = 24 * 3600_000L
        const val EXTRA_METRICS = "android.metrics"
    }
}
