package dev.kevtr.hilightlink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kevtr.hilightlink.Leds
import dev.kevtr.hilightlink.RuleStore
import dev.kevtr.hilightlink.engine.Effect
import dev.kevtr.hilightlink.engine.Style

private const val MAX_COLORS = 3

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(pkg: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val config by RuleStore.config.collectAsStateWithLifecycle()
    val rule = config.rules.firstOrNull { it.pkg == pkg }
    if (rule == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val effect = rule.effect
    fun change(next: Effect) = RuleStore.put(rule.copy(effect = next))
    val clock = rememberClock()
    var slot by remember { mutableIntStateOf(0) }
    val active = slot.coerceIn(0, effect.colors.lastIndex)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (pkg != RuleStore.DEFAULT_PKG) {
                            AppIcon(pkg, Modifier.size(32.dp))
                            Spacer(Modifier.width(12.dp))
                        }
                        Text(remember(pkg) { appLabel(context, pkg) }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { RuleStore.remove(pkg); onBack() }) {
                        Icon(Icons.Default.Delete, contentDescription = "Remove")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = padding.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LedStage(clock, render = effectRenderer(effect))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { Leds.play(context, effect, effect.takeIf { it.hold }) }, Modifier.weight(1f).height(56.dp)) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Test on phone")
                }
                FilledTonalButton(onClick = { Leds.off(context) }, Modifier.height(56.dp)) { Text("Stop") }
            }
            Group {
                GroupRow(
                    title = "Light up for this app",
                    trailing = { Switch(rule.enabled, onCheckedChange = { RuleStore.put(rule.copy(enabled = it)) }) },
                )
            }

            SectionTitle("Style")
            Style.entries.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { s ->
                        StyleTile(
                            style = s,
                            effect = effect,
                            clock = clock,
                            selected = effect.style == s,
                            onClick = { change(effect.copy(style = s)) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }

            if (effect.style != Style.RAINBOW) {
                SectionTitle("Colors")
                Panel {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        effect.colors.forEachIndexed { i, c -> Dot(c, selected = i == active, size = 48.dp) { slot = i } }
                        Spacer(Modifier.weight(1f))
                        if (effect.colors.size > 1) {
                            TextButton(onClick = {
                                slot = 0
                                change(effect.copy(colors = effect.colors.filterIndexed { i, _ -> i != active }))
                            }) { Text("Remove") }
                        }
                        if (effect.colors.size < MAX_COLORS) {
                            FilledTonalButton(onClick = {
                                slot = effect.colors.size
                                change(effect.copy(colors = effect.colors + 0xFFFFFFFF.toInt()))
                            }) { Text("Add") }
                        }
                    }
                    ColorPicker(key = pkg to active, selected = effect.colors[active]) { c ->
                        change(effect.copy(colors = effect.colors.mapIndexed { i, old -> if (i == active) c else old }))
                    }
                }
            }

            SectionTitle("Timing")
            Panel {
                if (effect.style != Style.SOLID) {
                    SliderRow("Speed", "${"%.1f".format(effect.periodMs / 1000f)} s per cycle", effect.periodMs.toFloat(), 150f..4000f) {
                        change(effect.copy(periodMs = it.toInt()))
                    }
                }
                SliderRow("Lasts", "${effect.durationMs / 1000} s", effect.durationMs.toFloat(), 1000f..60000f) {
                    change(effect.copy(durationMs = (it / 1000).toInt() * 1000))
                }
                SliderRow("Brightness", "${(effect.brightness * 100).toInt()}%", effect.brightness, 0.05f..1f) {
                    change(effect.copy(brightness = it))
                }
            }
            Group {
                GroupRow(
                    title = "Stay lit until dismissed",
                    subtitle = "Leaves a dim glow after the pattern ends",
                    trailing = { Switch(effect.hold, onCheckedChange = { change(effect.copy(hold = it)) }) },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** A style choice that plays itself in the rule's own colours. */
@Composable
private fun StyleTile(
    style: Style,
    effect: Effect,
    clock: State<Long>,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val sample = remember(style, effect.colors, effect.periodMs) { effect.copy(style = style, brightness = 1f) }
    Column(
        modifier
            .clip(TileShape)
            .background(if (selected) scheme.primaryContainer else scheme.surfaceContainerHigh)
            .then(if (selected) Modifier.border(2.dp, scheme.primary, TileShape) else Modifier)
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black).padding(horizontal = 6.dp),
        ) {
            LedStrip(clock, Modifier.fillMaxSize(), effectRenderer(sample))
        }
        Text(
            style.label,
            Modifier.padding(horizontal = 4.dp),
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) scheme.onPrimaryContainer else scheme.onSurface,
        )
    }
}
