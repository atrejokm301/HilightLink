package dev.kevtr.hilightlink.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kevtr.hilightlink.engine.Effect
import dev.kevtr.hilightlink.engine.Renderer
import kotlin.math.pow

/** Wallpaper-based Material You colours, following the system light or dark setting. */
@Composable
fun HilightTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialTheme(colorScheme = scheme, content = content)
}

val GroupShape = RoundedCornerShape(28.dp)
val TileShape = RoundedCornerShape(20.dp)

/** Icons the core Material set leaves out, from their 24 dp path data. */
private fun icon(name: String, path: String): ImageVector = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
    .addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black))
    .build()

val Bolt = icon("Bolt", "M11,21h-1l1,-7H7.5c-0.88,0 -0.33,-0.75 -0.31,-0.78C8.48,10.94 10.42,7.54 13,3h1l-1,7h3.51c0.4,0 0.62,0.19 0.4,0.66C12.97,17.55 11,21 11,21z")
val PauseIcon = icon("Pause", "M6,19h4V5H6v14zm8,-14v14h4V5h-4z")
val MusicIcon = icon("Music", "M12,3v10.55c-0.59,-0.34 -1.27,-0.55 -2,-0.55 -2.21,0 -4,1.79 -4,4s1.79,4 4,4 4,-1.79 4,-4V7h4V3h-6z")
val TimerIcon = icon("Timer", "M15,1H9v2h6V1zm-4,13h2V8h-2v6zm8.03,-6.61l1.42,-1.42c-0.43,-0.51 -0.9,-0.99 -1.41,-1.41l-1.42,1.42C16.07,4.74 14.12,4 12,4c-4.97,0 -9,4.03 -9,9s4.02,9 9,9 9,-4.03 9,-9c0,-2.12 -0.74,-4.07 -1.97,-5.61zM12,20c-3.87,0 -7,-3.13 -7,-7s3.13,-7 7,-7 7,3.13 7,7 -3.13,7 -7,7z")
val SaveIcon = icon("Save", "M19,9h-4V3H9v6H5l7,7 7,-7zM5,18v2h14v-2H5z")
val RestoreIcon = icon("Restore", "M9,16h6v-6h4l-7,-7 -7,7h4zm-4,2h14v2H5z")

/** One animation clock per screen, read only while drawing so ticks never recompose. */
@Composable
fun rememberClock(): State<Long> {
    val clock = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) withFrameMillis { clock.longValue = it }
    }
    return clock
}

/** How strongly an LED is lit, 0..1, from its brightest channel. */
private fun level(argb: Int): Float = maxOf(argb shr 16 and 0xFF, argb shr 8 and 0xFF, argb and 0xFF) / 255f

/**
 * A dim LED keeps its hue and only glows less, so lift all channels by the same factor
 * instead of darkening the colour, which would turn it muddy on screen.
 */
private fun lift(argb: Int): Color {
    val level = level(argb)
    if (level == 0f) return Color.Black
    val k = level.pow(0.2f) / level / 255f
    fun ch(shift: Int) = ((argb shr shift and 0xFF) * k).coerceAtMost(1f)
    return Color(ch(16), ch(8), ch(0))
}

/** The eight LEDs drawn with a soft glow, from the same frames the root helper plays. */
@Composable
fun LedStrip(clock: State<Long>, modifier: Modifier = Modifier, render: (Long, IntArray) -> Unit) {
    val frame = remember { IntArray(Renderer.LEDS) }
    Canvas(modifier) {
        render(clock.value, frame)
        val slot = size.width / frame.size
        val r = minOf(slot * 0.26f, size.height * 0.2f)
        for (i in frame.indices) {
            val center = Offset(slot * (i + 0.5f), size.height / 2f)
            drawCircle(Color(0xFF1F1F1F), r, center)
            if (frame[i] == 0) continue
            val c = lift(frame[i])
            // Brightness shows as the size of the dot and its glow rather than a darker colour.
            val power = level(frame[i]).pow(0.5f)
            val core = r * (0.55f + 0.45f * power)
            val glow = r * (1.4f + 1.6f * power)
            drawCircle(
                Brush.radialGradient(listOf(c.copy(alpha = 0.15f + 0.45f * power), Color.Transparent), center, glow),
                glow,
                center,
            )
            drawCircle(c, core, center)
            drawCircle(Color.White.copy(alpha = 0.35f * power), core * 0.45f, center)
        }
    }
}

/** Large black stage for a live preview. */
@Composable
fun LedStage(clock: State<Long>, height: Dp = 112.dp, render: (Long, IntArray) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(36.dp))
            .background(Color.Black)
            .padding(horizontal = 20.dp),
    ) {
        LedStrip(clock, Modifier.fillMaxSize(), render)
    }
}

fun effectRenderer(effect: Effect): (Long, IntArray) -> Unit = { t, out -> Renderer.frame(effect, t, out) }

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** Rows grouped into one rounded block with hairline gaps, like current system settings. */
@Composable
fun Group(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(GroupShape), verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
}

@Composable
fun GroupRow(
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(4.dp),
        modifier = Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Row(
            Modifier.heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(16.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(12.dp))
                trailing()
            }
        }
    }
}

/** Icon on a tonal circle, used as the leading element of a row. */
@Composable
fun IconBadge(icon: ImageVector) {
    Box(
        Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

/** Rounded container for a block of controls. */
@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = GroupShape, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

/** Slider with its name on the left and current value on the right. */
@Composable
fun SliderRow(
    label: String,
    value: String,
    position: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column {
        Row {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Text(value, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        }
        Slider(position, onValueChange = onChange, valueRange = range)
    }
}

@Composable
fun Dot(color: Int, selected: Boolean, size: Dp = 40.dp, onClick: () -> Unit) {
    val c = Color(color)
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(c)
            .border(
                if (selected) 3.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                CircleShape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = if (c.luminance() > 0.5f) Color.Black else Color.White,
                modifier = Modifier.size(size / 2),
            )
        }
    }
}

private val SWATCHES = listOf(
    0xFFFFFFFF, 0xFFFF1744, 0xFFFF9100, 0xFFFFEA00, 0xFF00E676, 0xFF1DE9B6,
    0xFF00B0FF, 0xFF2979FF, 0xFF651FFF, 0xFFD500F9, 0xFFFF4081,
).map { it.toInt() }

private val HUE_TRACK = Brush.horizontalGradient((0..12).map { Color(Renderer.hue(it / 12f)) })

/** Reads a pasted six-digit hex colour such as "#FF8800", "ff8800" or "0xFF8800". */
fun parseHex(text: String): Int? {
    val digits = text.trim().removePrefix("#").removePrefix("0x").removePrefix("0X")
    if (digits.length != 6 || digits.any { it !in "0123456789abcdefABCDEF" }) return null
    val rgb = digits.toIntOrNull(16) ?: return null
    return (0xFF shl 24) or rgb
}

private fun saturationOf(argb: Int): Float {
    val r = argb shr 16 and 0xFF; val g = argb shr 8 and 0xFF; val b = argb and 0xFF
    val max = maxOf(r, g, b)
    return if (max == 0) 0f else (max - minOf(r, g, b)) / max.toFloat()
}

/** Hue in 0..1, matching [Renderer.hue]. */
private fun hueOf(argb: Int): Float {
    val r = (argb shr 16 and 0xFF).toFloat(); val g = (argb shr 8 and 0xFF).toFloat(); val b = (argb and 0xFF).toFloat()
    val max = maxOf(r, g, b)
    val d = max - minOf(r, g, b)
    if (d == 0f) return 0f
    val h = when (max) {
        r -> ((g - b) / d + 6f) % 6f
        g -> (b - r) / d + 2f
        else -> (r - g) / d + 4f
    }
    return (h / 6f).coerceIn(0f, 0.999f)
}

/** Slider drawn over a gradient bar that shows what each position gives. */
@Composable
private fun GradientSlider(track: Brush, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Box(contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxWidth().height(16.dp).clip(CircleShape).background(track))
        Slider(
            value,
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                activeTrackColor = Color.Transparent,
                inactiveTrackColor = Color.Transparent,
            ),
        )
    }
}

/** Preset swatches, a hex code field, and hue and saturation sliders, so any colour can be reached. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorPicker(key: Any, selected: Int, onPick: (Int) -> Unit) {
    var hue by remember(key) { mutableStateOf(hueOf(selected)) }
    var saturation by remember(key) { mutableStateOf(saturationOf(selected)) }
    // Last colour the sliders produced; anything else came from a swatch or the hex field.
    var fromSliders by remember(key) { mutableStateOf(selected) }
    if (selected != fromSliders) {
        fromSliders = selected
        hue = hueOf(selected)
        saturation = saturationOf(selected)
    }
    fun custom(): Int {
        val pure = Renderer.hue(hue)
        fun ch(shift: Int) = (255 + ((pure shr shift and 0xFF) - 255) * saturation).toInt()
        return ((0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)).also { fromSliders = it }
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SWATCHES.forEach { c -> Dot(c, selected = selected == c) { onPick(c) } }
    }
    // Keyed on the selection so swatches and sliders keep the field in step.
    var hex by remember(key, selected) { mutableStateOf("#%06X".format(selected and 0xFFFFFF)) }
    OutlinedTextField(
        value = hex,
        onValueChange = { typed ->
            hex = typed
            parseHex(typed)?.let(onPick)
        },
        label = { Text("Hex code") },
        placeholder = { Text("#FF8800") },
        leadingIcon = { Box(Modifier.size(24.dp).background(Color(selected), CircleShape)) },
        isError = parseHex(hex) == null,
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium),
        modifier = Modifier.fillMaxWidth(),
    )
    GradientSlider(HUE_TRACK, hue, 0f..0.999f) { hue = it; onPick(custom()) }
    GradientSlider(
        Brush.horizontalGradient(listOf(Color.White, Color(Renderer.hue(hue)))),
        saturation,
        0f..1f,
    ) { saturation = it; onPick(custom()) }
}
