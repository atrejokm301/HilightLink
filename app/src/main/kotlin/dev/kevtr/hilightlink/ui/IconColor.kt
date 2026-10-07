package dev.kevtr.hilightlink.ui

import android.content.Context
import android.graphics.Color
import androidx.core.graphics.drawable.toBitmap

/**
 * The most prominent vivid colour of an app's icon, at full strength so it suits an LED.
 * Null when the icon is missing or has no real colour, such as a black and white one.
 */
fun iconColor(context: Context, pkg: String): Int? {
    val bitmap = runCatching { context.packageManager.getApplicationIcon(pkg).toBitmap(48, 48) }.getOrNull()
        ?: return null
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

    // Vote by hue, counting saturated and bright pixels more than washed-out ones.
    val weight = FloatArray(HUE_BINS)
    val saturation = FloatArray(HUE_BINS)
    val hsv = FloatArray(3)
    for (p in pixels) {
        if (Color.alpha(p) < 128) continue
        Color.colorToHSV(p, hsv)
        if (hsv[1] < 0.3f || hsv[2] < 0.3f) continue
        val bin = (hsv[0] / 360f * HUE_BINS).toInt().coerceIn(0, HUE_BINS - 1)
        val w = hsv[1] * hsv[2]
        weight[bin] += w
        saturation[bin] += hsv[1] * w
    }
    val best = weight.indices.maxBy { weight[it] }
    if (weight[best] < pixels.size * 0.02f) return null
    val hue = (best + 0.5f) * 360f / HUE_BINS
    return Color.HSVToColor(floatArrayOf(hue, (saturation[best] / weight[best]).coerceAtLeast(0.85f), 1f))
}

private const val HUE_BINS = 24
