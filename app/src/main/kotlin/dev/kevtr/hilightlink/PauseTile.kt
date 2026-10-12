package dev.kevtr.hilightlink

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Switches every light off or back on, from the app, the tile, or a restored backup. */
object Control {
    fun setPaused(context: Context, paused: Boolean) {
        RuleStore.setPaused(paused)
        apply(context)
    }

    /** Brings the LEDs and the tile in line with the saved settings. */
    fun apply(context: Context) {
        val app = context.applicationContext
        // The engine inside the system does its part itself, from the settings it is sent.
        if (Leds.remote(app)) TimerLight.push(app) else lights(app)
        Music.refresh(app)
        TileService.requestListeningState(app, ComponentName(app, PauseTile::class.java))
    }

    /** Brings the notification, call, charging and timer lights in line with the saved settings. */
    fun lights(context: Context) {
        if (RuleStore.config.value.paused) {
            Waiting.clear(context)
            Calls.clear(context)
            Leds.stopAll(context)
            Reminders.clear(context)
        }
        Quiet.refresh(context)
        TimerLight.push(context)
    }
}

/** Quick Settings tile: lit while the lights are on, tap to pause them all. */
class PauseTile : TileService() {
    override fun onStartListening() {
        RuleStore.init(this)
        show()
    }

    override fun onClick() {
        RuleStore.init(this)
        Control.setPaused(this, !RuleStore.config.value.paused)
        show()
    }

    private fun show() {
        val tile = qsTile ?: return
        val paused = RuleStore.config.value.paused
        tile.state = if (paused) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        tile.subtitle = if (paused) "Paused" else "On"
        tile.updateTile()
    }
}
