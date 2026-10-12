package dev.kevtr.hilightlink.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kevtr.hilightlink.Leds
import dev.kevtr.hilightlink.RuleStore

/** How long "Test on phone" plays the call pattern, which on a real call runs until it stops ringing. */
private const val TEST_MS = 8000

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val config by RuleStore.config.collectAsStateWithLifecycle()
    val call = config.call
    val clock = rememberClock()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Incoming calls") },
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
            LedStage(clock, render = effectRenderer(call.effect))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { Leds.play(context, call.effect.copy(durationMs = TEST_MS)) },
                    Modifier.weight(1f).height(56.dp),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Test on phone")
                }
                FilledTonalButton(onClick = { Leds.stop(context) }, Modifier.height(56.dp)) { Text("Stop") }
            }
            Text(
                "Plays for as long as the phone rings and stops when you answer or decline. It works for " +
                    "phone calls and for calls from apps, and shows over every other light.",
                Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Group {
                GroupRow(
                    title = "Light up for calls",
                    trailing = { Switch(call.on, onCheckedChange = { RuleStore.setCall(call.copy(on = it)) }) },
                )
            }
            EffectControls(key = "call", effect = call.effect, clock = clock, lasts = false) {
                RuleStore.setCall(call.copy(effect = it))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
