package dev.kevtr.hilightlink

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import dev.kevtr.hilightlink.daemon.Main
import dev.kevtr.hilightlink.engine.Effect
import dev.kevtr.hilightlink.engine.Renderer
import dev.kevtr.hilightlink.engine.Style

/**
 * Light while the phone is plugged in: one colour (or a battery meter) while charging and
 * another once full, plus a red blink when the battery runs low.
 */
object Charging {
    private val LOW_LEVELS = intArrayOf(15, 5)
    private val LOW_BATTERY = Effect(Style.DOUBLE_BLINK, listOf(0xFFFF1744.toInt()), periodMs = 1000, durationMs = 5000)

    /** Battery percentage at the last broadcast, to spot it crossing a low level. */
    private var lastPercent = -1

    /**
     * Pushes the light that matches the battery state and the saved settings to the helper.
     * [battery] is set when this is a real battery broadcast rather than a settings change.
     */
    fun refresh(context: Context, battery: Intent? = null) {
        // The engine inside the system watches the battery itself.
        if (Leds.remote(context)) return
        val intent = battery
            ?: context.applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val config = RuleStore.config.value
        val out = IntArray(Renderer.LEDS)
        val level = Quiet.level()
        if (intent != null && !config.paused && level > 0f) {
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            val percent = percent(intent)
            val full = intent.getIntExtra(BatteryManager.EXTRA_STATUS, 0) == BatteryManager.BATTERY_STATUS_FULL ||
                percent >= 100
            if (plugged) {
                frame(config.charging, percent / 100f, full, out)
                for (i in out.indices) out[i] = Renderer.scale(out[i], level)
            }
            if (battery != null) warn(context, config.charging, plugged, percent, level)
        }
        Leds.base(context, out)
    }

    /** What the LEDs show while plugged in at [level] (0..1). */
    fun frame(c: ChargingConfig, level: Float, full: Boolean, out: IntArray) {
        when {
            full -> out.fill(if (c.chargedOn) Renderer.scale(c.chargedColor, c.brightness) else 0)
            !c.chargingOn -> out.fill(0)
            // Never fewer than a faint first LED, so an empty battery still shows it is charging.
            c.meter -> Renderer.meter(Renderer.scale(c.chargingColor, c.brightness), level.coerceAtLeast(0.05f), out)
            else -> out.fill(Renderer.scale(c.chargingColor, c.brightness))
        }
    }

    private fun percent(battery: Intent): Int {
        val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
        val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        return level * 100 / scale
    }

    private fun warn(context: Context, c: ChargingConfig, plugged: Boolean, percent: Int, level: Float) {
        val before = lastPercent
        lastPercent = percent
        if (!c.lowBattery || plugged || before < 0) return
        if (LOW_LEVELS.any { before > it && percent <= it }) {
            Leds.play(context, LOW_BATTERY.copy(brightness = level), Main.ALERT)
        }
    }
}
