package dev.kevtr.hilightlink.engine

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

enum class Style(val label: String) {
    SOLID("Solid"),
    BLINK("Blink"),
    DOUBLE_BLINK("Double blink"),
    STROBE("Strobe"),
    BREATHE("Breathe"),
    HEARTBEAT("Heartbeat"),
    CHASE("Chase"),
    BOUNCE("Bounce"),
    WAVE("Wave"),
    FILL("Fill"),
    CENTER_OUT("Center out"),
    ALTERNATE("Alternate"),
    SPARKLE("Sparkle"),
    RAINBOW("Rainbow"),
}

/** One light pattern. Colours are opaque ARGB; [periodMs] is the length of one cycle. */
data class Effect(
    val style: Style = Style.BREATHE,
    val colors: List<Int> = listOf(0xFF40C4FF.toInt()),
    val periodMs: Int = 1200,
    val durationMs: Int = 6000,
    val brightness: Float = 1f,
    /** Leave a dim steady glow after the animation until the notification is dismissed. */
    val hold: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("style", style.name)
        .put("colors", JSONArray(colors))
        .put("periodMs", periodMs)
        .put("durationMs", durationMs)
        .put("brightness", brightness.toDouble())
        .put("hold", hold)

    companion object {
        fun fromJson(o: JSONObject): Effect {
            val arr = o.optJSONArray("colors") ?: JSONArray()
            val colors = (0 until arr.length()).map { arr.getInt(it) }
            return Effect(
                style = runCatching { Style.valueOf(o.optString("style")) }.getOrDefault(Style.BREATHE),
                colors = colors.ifEmpty { listOf(0xFFFFFFFF.toInt()) },
                periodMs = o.optInt("periodMs", 1200),
                durationMs = o.optInt("durationMs", 6000),
                brightness = o.optDouble("brightness", 1.0).toFloat(),
                hold = o.optBoolean("hold", false),
            )
        }
    }
}

/** Pure frame generator shared by the on-screen preview and the root helper. */
object Renderer {
    const val LEDS = 8
    const val HOLD_BRIGHTNESS = 0.25f

    fun frame(e: Effect, tMs: Long, out: IntArray) {
        val period = e.periodMs.coerceAtLeast(60)
        val cycle = (tMs / period).toInt()
        val p = (tMs % period).toFloat() / period
        val cols = e.colors.ifEmpty { listOf(0xFFFFFFFF.toInt()) }
        val cyc = cols[cycle % cols.size]
        val a = cols[0]
        val b = if (cols.size > 1) cols[1] else 0
        val tri = if (p < 0.5f) p * 2f else 2f - 2f * p
        val n = LEDS

        for (i in 0 until n) {
            val c = when (e.style) {
                Style.SOLID -> cyc
                Style.BLINK -> if (p < 0.5f) cyc else 0
                Style.DOUBLE_BLINK -> if (p < 0.12f || (p >= 0.24f && p < 0.36f)) cyc else 0
                Style.STROBE -> if (p < 0.25f) cyc else 0
                Style.BREATHE -> {
                    val k = (1f - cos(2f * PI.toFloat() * p)) / 2f
                    scale(cyc, k * k)
                }
                Style.HEARTBEAT -> scale(cyc, maxOf(pulse(p, 0f), pulse(p, 0.22f) * 0.7f))
                Style.CHASE -> {
                    val d = ((p * n - i) % n + n) % n
                    scale(cyc, (1f - d / 3.5f).coerceAtLeast(0f))
                }
                Style.BOUNCE -> {
                    val d = abs(tri * (n - 1) - i)
                    scale(cyc, (1f - d / 1.8f).coerceAtLeast(0f))
                }
                Style.WAVE -> {
                    val k = (1f + sin(2f * PI.toFloat() * (p - i.toFloat() / n))) / 2f
                    mix(b, a, k)
                }
                Style.FILL -> scale(cyc, (tri * (n + 1) - i).coerceIn(0f, 1f))
                Style.CENTER_OUT -> {
                    val d = abs(i - (n - 1) / 2f)
                    scale(cyc, (tri * (n / 2f + 1f) - d).coerceIn(0f, 1f))
                }
                Style.ALTERNATE -> if ((i % 2 == 0) != (p >= 0.5f)) a else b
                Style.SPARKLE -> {
                    val slotLen = (period / 6).coerceAtLeast(40)
                    val slot = tMs / slotLen
                    val h = hash(i, slot)
                    if (h % 3 == 0) {
                        scale(cols[(h / 3) % cols.size], 1f - (tMs % slotLen).toFloat() / slotLen)
                    } else 0
                }
                Style.RAINBOW -> hue((p + i.toFloat() / n) % 1f)
            }
            out[i] = scale(c, e.brightness)
        }
    }

    /** The steady glow left behind when [Effect.hold] is on. */
    fun holdFrame(e: Effect, out: IntArray) {
        val c = scale(e.colors.firstOrNull() ?: 0xFFFFFFFF.toInt(), e.brightness * HOLD_BRIGHTNESS)
        out.fill(c)
    }

    /** A bar that is full at [fraction] 1 and empties towards the first LED, the last lit one fading. */
    fun meter(color: Int, fraction: Float, out: IntArray) {
        val lit = fraction.coerceIn(0f, 1f) * LEDS
        for (i in 0 until LEDS) out[i] = scale(color, (lit - i).coerceIn(0f, 1f))
    }

    private fun pulse(p: Float, start: Float) = if (p >= start) exp(-(p - start) * 14f) else 0f

    private fun hash(i: Int, slot: Long): Int {
        var x = (slot * 31 + i) * 0x9E3779B97F4A7C15uL.toLong()
        x = x xor (x ushr 29)
        return ((x and 0x7FFFFFFF).toInt())
    }

    fun scale(color: Int, k: Float): Int {
        val f = k.coerceIn(0f, 1f)
        val r = ((color shr 16 and 0xFF) * f).toInt()
        val g = ((color shr 8 and 0xFF) * f).toInt()
        val bl = ((color and 0xFF) * f).toInt()
        if (r or g or bl == 0) return 0
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }

    private fun mix(from: Int, to: Int, k: Float): Int {
        fun ch(shift: Int): Int {
            val x = from shr shift and 0xFF
            val y = to shr shift and 0xFF
            return (x + (y - x) * k).toInt()
        }
        val r = ch(16); val g = ch(8); val bl = ch(0)
        if (r or g or bl == 0) return 0
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }

    /** Fully saturated colour for a hue in 0..1. */
    fun hue(h: Float): Int {
        val x = (h % 1f + 1f) % 1f * 6f
        val f = x - x.toInt()
        val (r, g, b) = when (x.toInt()) {
            0 -> Triple(1f, f, 0f)
            1 -> Triple(1f - f, 1f, 0f)
            2 -> Triple(0f, 1f, f)
            3 -> Triple(0f, 1f - f, 1f)
            4 -> Triple(f, 0f, 1f)
            else -> Triple(1f, 0f, 1f - f)
        }
        return (0xFF shl 24) or ((r * 255).toInt() shl 16) or ((g * 255).toInt() shl 8) or (b * 255).toInt()
    }
}
