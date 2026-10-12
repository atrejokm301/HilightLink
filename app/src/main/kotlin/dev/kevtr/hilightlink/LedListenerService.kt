package dev.kevtr.hilightlink

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * The app's view of the notifications, and what keeps its process around for the charging
 * light, the reminders and the music visualizer.
 */
class LedListenerService : NotificationListenerService() {
    private val lights by lazy { NotificationLights(this, ::silenced) }

    private val battery = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = Charging.refresh(context, intent)
    }

    /** Unlocking the phone counts as having seen what was waiting. */
    private val unlocked = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = Reminders.clear(context)
    }

    /** The clock or the time zone was changed, which moves quiet hours. */
    private val clockSet = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = Quiet.refresh(context)
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
        val clock = IntentFilter(Intent.ACTION_TIME_CHANGED).apply { addAction(Intent.ACTION_TIMEZONE_CHANGED) }
        registerReceiver(clockSet, clock, RECEIVER_NOT_EXPORTED)
        getSystemService(AudioManager::class.java).registerAudioPlaybackCallback(playback, null)
    }

    override fun onDestroy() {
        unregisterReceiver(battery)
        unregisterReceiver(unlocked)
        unregisterReceiver(clockSet)
        getSystemService(AudioManager::class.java).unregisterAudioPlaybackCallback(playback)
        super.onDestroy()
    }

    override fun onListenerConnected() {
        Thread {
            // With an engine inside the system the notifications are its to light.
            if (!Leds.remote(this)) lights.connected(runCatching { activeNotifications }.getOrNull().orEmpty().toList())
            Music.refresh(this)
        }.start()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!Leds.remote(this)) lights.posted(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (!Leds.remote(this)) lights.removed(sbn)
    }

    /** Whether Do Not Disturb is holding this notification back. */
    private fun silenced(sbn: StatusBarNotification): Boolean {
        val ranking = Ranking()
        return runCatching { currentRanking.getRanking(sbn.key, ranking) && !ranking.matchesInterruptionFilter() }
            .getOrDefault(false)
    }
}
