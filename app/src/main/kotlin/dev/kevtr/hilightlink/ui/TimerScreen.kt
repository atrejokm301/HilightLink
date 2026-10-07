package dev.kevtr.hilightlink.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kevtr.hilightlink.RuleStore
import dev.kevtr.hilightlink.TimerConfig
import dev.kevtr.hilightlink.TimerLight
import dev.kevtr.hilightlink.engine.Renderer
import kotlinx.coroutines.delay

private val QUICK_MINUTES = listOf(1, 3, 5, 10, 15, 30, 60)

/** "4:05" or "1:02:03". */
fun formatRemaining(ms: Long): String {
    val s = (ms.coerceAtLeast(0) + 999) / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TimerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val config by RuleStore.config.collectAsStateWithLifecycle()
    val running by TimerLight.running.collectAsStateWithLifecycle()
    val timer = config.timer
    val clock = rememberClock()
    val now by produceState(SystemClock.elapsedRealtime()) {
        while (true) {
            value = SystemClock.elapsedRealtime()
            delay(250)
        }
    }
    val active = running?.takeIf { it.endAt > now }
    fun change(next: TimerConfig) {
        RuleStore.setTimer(next)
        TimerLight.push(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Timer light") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            LedStage(clock) { t, out ->
                val r = TimerLight.running.value
                val left = if (r != null) (r.endAt - SystemClock.elapsedRealtime()).toFloat() / r.totalMs else -1f
                // With nothing running, show a short countdown on a loop as a demonstration.
                Renderer.meter(timer.color, if (left > 0f) left else 1f - (t % 8000) / 7000f, out)
            }
            Text(
                if (active != null) formatRemaining(active.endAt - now) else formatRemaining(timer.minutes * 60_000L),
                Modifier.fillMaxWidth().padding(top = 8.dp),
                style = MaterialTheme.typography.displayMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                when {
                    active == null -> "The LEDs go out one by one, then blink when time is up"
                    active.key != null -> "Following a timer from another app"
                    else -> "Running"
                },
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { TimerLight.start(context, timer.minutes * 60_000L) },
                    Modifier.weight(1f).height(56.dp),
                ) { Text(if (active != null) "Restart" else "Start") }
                if (active != null) {
                    FilledTonalButton(onClick = { TimerLight.cancel(context) }, Modifier.height(56.dp)) { Text("Cancel") }
                }
            }

            SectionTitle("Length")
            Panel {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QUICK_MINUTES.forEach { m ->
                        FilterChip(
                            selected = timer.minutes == m,
                            onClick = { change(timer.copy(minutes = m)) },
                            label = { Text(if (m == 60) "1 h" else "$m min") },
                        )
                    }
                }
                SliderRow("Minutes", "${timer.minutes}", timer.minutes.toFloat(), 1f..120f) {
                    change(timer.copy(minutes = it.toInt()))
                }
                Text(
                    "This timer only drives the lights. It makes no sound.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Group {
                GroupRow(
                    title = "Follow Clock timers",
                    subtitle = "Show a countdown from the Clock app without starting one here",
                    trailing = { Switch(timer.followClock, onCheckedChange = { change(timer.copy(followClock = it)) }) },
                )
            }

            SectionTitle("Color")
            Panel { ColorPicker("timer", timer.color) { change(timer.copy(color = it)) } }
            Spacer(Modifier.height(24.dp))
        }
    }
}
