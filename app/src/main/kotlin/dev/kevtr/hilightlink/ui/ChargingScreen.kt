package dev.kevtr.hilightlink.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kevtr.hilightlink.Charging
import dev.kevtr.hilightlink.ChargingConfig
import dev.kevtr.hilightlink.RuleStore
import dev.kevtr.hilightlink.engine.Renderer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChargingScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val config by RuleStore.config.collectAsStateWithLifecycle()
    val charging = config.charging
    val clock = rememberClock()
    // Changes show on the LEDs straight away when the phone is plugged in.
    fun change(next: ChargingConfig) {
        RuleStore.setCharging(next)
        Charging.refresh(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Charging light") },
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
            Text(
                "A steady light while the phone is plugged in. Notification patterns play over it.",
                Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Panel {
                SliderRow("Brightness", "${(charging.brightness * 100).toInt()}%", charging.brightness, 0.02f..1f) {
                    change(charging.copy(brightness = it))
                }
            }

            SectionTitle("While charging")
            StatePanel(
                key = "charging",
                clock = clock,
                on = charging.chargingOn,
                color = charging.chargingColor,
                brightness = charging.brightness,
                onToggle = { change(charging.copy(chargingOn = it)) },
                onColor = { change(charging.copy(chargingColor = it)) },
                meter = charging.meter,
                onMeter = { change(charging.copy(meter = it)) },
            )

            SectionTitle("When fully charged")
            StatePanel(
                key = "charged",
                clock = clock,
                on = charging.chargedOn,
                color = charging.chargedColor,
                brightness = charging.brightness,
                onToggle = { change(charging.copy(chargedOn = it)) },
                onColor = { change(charging.copy(chargedColor = it)) },
            )

            SectionTitle("On battery")
            Group {
                GroupRow(
                    title = "Low battery warning",
                    subtitle = "Blinks red when the battery drops to 15% and to 5%",
                    trailing = { Switch(charging.lowBattery, onCheckedChange = { change(charging.copy(lowBattery = it)) }) },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StatePanel(
    key: String,
    clock: State<Long>,
    on: Boolean,
    color: Int,
    brightness: Float,
    onToggle: (Boolean) -> Unit,
    onColor: (Int) -> Unit,
    /** Null where a battery meter makes no sense, such as when the battery is full. */
    meter: Boolean? = null,
    onMeter: (Boolean) -> Unit = {},
) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Show light", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Switch(on, onCheckedChange = onToggle)
        }
        if (on) {
            if (meter != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Battery meter", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "LEDs fill up as the battery charges",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(meter, onCheckedChange = onMeter)
                }
            }
            val shown = Renderer.scale(color, brightness)
            LedStage(clock, height = 64.dp) { t, out ->
                // The meter preview charges from empty to full, then rests there for a moment.
                if (meter == true) Renderer.meter(shown, (t % 6000) / 4500f, out) else out.fill(shown)
            }
            ColorPicker(key, color, onColor)
        }
    }
}
