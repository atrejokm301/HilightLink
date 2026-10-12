package dev.kevtr.hilightlink

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import dev.kevtr.hilightlink.daemon.Main

/** Call light: a pattern that loops for as long as the phone rings, over everything else. */
object Calls {
    /** Keys of the call notifications that are ringing. */
    private val ringing = HashSet<String>()

    /** Whether [sbn] is a call that is ringing right now, from the dialer or any calling app. */
    fun isRinging(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        if (n.category != Notification.CATEGORY_CALL) return false
        return when (n.extras?.getInt(EXTRA_CALL_TYPE, 0) ?: 0) {
            CALL_TYPE_INCOMING -> true
            // Apps that do not use the call style ring with an ongoing full-screen notification.
            0 -> sbn.isOngoing && n.fullScreenIntent != null
            else -> false
        }
    }

    /** A call started ringing, or its notification changed while it rings. */
    @Synchronized
    fun ring(context: Context, key: String) {
        val config = RuleStore.config.value
        val level = if (config.quiet.calls) 1f else Quiet.level()
        if (!config.call.on || config.paused || level <= 0f) return
        // Dialers repost the notification while it rings; the pattern must not start over each time.
        if (!ringing.add(key)) return
        val effect = config.call.effect
        Leds.play(context, effect.copy(durationMs = MAX_RING_MS, brightness = effect.brightness * level), Main.CALL)
    }

    /** The call was answered, declined or missed. */
    @Synchronized
    fun end(context: Context, key: String) {
        if (ringing.remove(key) && ringing.isEmpty()) Leds.stop(context, Main.CALL)
    }

    /** Lights were paused, or this process started afresh and knows of no call. */
    @Synchronized
    fun clear(context: Context) {
        ringing.clear()
        Leds.stop(context, Main.CALL)
    }

    /** Nothing rings longer than this, so a call the app lost track of cannot loop forever. */
    private const val MAX_RING_MS = 120_000

    /** Notification.EXTRA_CALL_TYPE and CallStyle.CALL_TYPE_INCOMING, public only from Android 14. */
    private const val EXTRA_CALL_TYPE = "android.callType"
    private const val CALL_TYPE_INCOMING = 1
}
