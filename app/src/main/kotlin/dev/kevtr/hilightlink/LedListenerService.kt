package dev.kevtr.hilightlink

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Turns incoming notifications into light patterns according to the saved rules. */
class LedListenerService : NotificationListenerService() {
    private val lastPlayed = HashMap<String, Long>()

    private val battery = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = Charging.refresh(context, intent)
    }

    /** Unlocking the phone counts as having seen what was waiting. */
    private val unlocked = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = Reminders.clear(context)
    }

    private val playback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            Music.playing = configs.any {
                val usage = it.audioAttributes.usage
                usage == AudioAttributes.USAGE_MEDIA || usage == AudioAttributes.USAGE_GAME ||
                    usage == AudioAttributes.USAGE_UNKNOWN
            }
            Music.refresh(this@LedListenerService)
        }
    }

    override fun onCreate() {
        super.onCreate()
        RuleStore.init(this)
        // Sticky broadcast: delivers the current battery state straight away.
        registerReceiver(battery, IntentFilter(Intent.ACTION_BATTERY_CHANGED), RECEIVER_NOT_EXPORTED)
        registerReceiver(unlocked, IntentFilter(Intent.ACTION_USER_PRESENT), RECEIVER_NOT_EXPORTED)
        getSystemService(AudioManager::class.java).registerAudioPlaybackCallback(playback, null)
    }

    override fun onDestroy() {
        unregisterReceiver(battery)
        unregisterReceiver(unlocked)
        getSystemService(AudioManager::class.java).unregisterAudioPlaybackCallback(playback)
        super.onDestroy()
    }

    override fun onListenerConnected() {
        // Warm the helper up so the first notification after boot is not delayed.
        Thread {
            Leds.ensureRunning(this)
            // A helper that outlived this process may still show a light nobody is tracking.
            Waiting.sync(this)
            Charging.refresh(this)
            Music.refresh(this)
            // Pick up a countdown that was already running.
            if (RuleStore.config.value.timer.followClock) {
                runCatching { activeNotifications }.getOrNull()?.forEach { sbn ->
                    countdownEnd(sbn)?.let { TimerLight.follow(this, sbn.key, it) }
                }
            }
        }.start()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val config = RuleStore.config.value
        if (config.timer.followClock) {
            val end = countdownEnd(sbn)
            if (end != null) return TimerLight.follow(this, sbn.key, end)
            TimerLight.unfollow(this, sbn.key)
        }
        if (config.paused || sbn.isOngoing) return
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val rule = RuleStore.ruleFor(sbn.packageName) ?: return
        if (config.onlyScreenOff && getSystemService(PowerManager::class.java).isInteractive) return

        // Apps often post the same notification several times in a burst.
        val now = SystemClock.elapsedRealtime()
        val last = lastPlayed[sbn.key]
        if (last != null && now - last < REPEAT_GUARD_MS) return
        lastPlayed[sbn.key] = now

        Waiting.post(this, sbn.key, rule.effect)
        Reminders.add(this, sbn.key, rule.effect)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        lastPlayed.remove(sbn.key)
        Reminders.remove(this, sbn.key)
        TimerLight.unfollow(this, sbn.key)
        Waiting.remove(this, sbn.key)
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
