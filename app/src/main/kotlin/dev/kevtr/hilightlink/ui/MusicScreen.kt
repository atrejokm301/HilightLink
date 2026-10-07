package dev.kevtr.hilightlink.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kevtr.hilightlink.Music
import dev.kevtr.hilightlink.MusicConfig
import dev.kevtr.hilightlink.MusicFrames
import dev.kevtr.hilightlink.MusicStyle
import dev.kevtr.hilightlink.RuleStore
import dev.kevtr.hilightlink.engine.Renderer
import kotlin.math.pow
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MusicScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val config by RuleStore.config.collectAsStateWithLifecycle()
    val status by Music.status.collectAsStateWithLifecycle()
    val music = config.music
    val clock = rememberClock()
    fun change(next: MusicConfig) {
        RuleStore.setMusic(next)
        Music.refresh(context)
    }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        change(RuleStore.config.value.music.copy(on = granted))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Music visualizer") },
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
            val levels = remember { FloatArray(Renderer.LEDS) }
            val frames = remember { MusicFrames() }
            LedStage(clock) { t, out ->
                demoLevels(t, levels)
                // Bend the made-up levels the way the sensitivity setting bends real ones.
                val bend = Music.curve(music.sensitivity) / Music.curve(MusicConfig().sensitivity)
                for (i in levels.indices) levels[i] = levels[i].pow(bend)
                frames.frame(music.copy(brightness = 1f), levels, t, out)
            }
            Group {
                GroupRow(
                    title = "Pulse with music",
                    subtitle = when (status) {
                        Music.Status.OFF -> if (config.paused && music.on) "Lights are paused" else "Off"
                        Music.Status.NO_PERMISSION -> "Needs the audio permission"
                        Music.Status.WAITING -> "Waiting for music"
                        Music.Status.LISTENING -> "Following what is playing"
                        Music.Status.FAILED -> "Could not listen to the audio output"
                    },
                    trailing = {
                        Switch(
                            music.on,
                            onCheckedChange = { on ->
                                if (on && !Music.hasPermission(context)) {
                                    askPermission.launch(Manifest.permission.RECORD_AUDIO)
                                } else {
                                    change(music.copy(on = on))
                                }
                            },
                        )
                    },
                )
            }
            Text(
                "Android asks for the microphone permission to read the phone's own sound output. " +
                    "Only loudness per pitch range is used, and nothing is recorded.",
                Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionTitle("Style")
            Panel {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MusicStyle.entries.forEach { style ->
                        FilterChip(
                            selected = music.style == style,
                            onClick = { change(music.copy(style = style)) },
                            label = { Text(style.label) },
                        )
                    }
                }
                Text(
                    music.style.about,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SliderRow("Brightness", "${(music.brightness * 100).toInt()}%", music.brightness, 0.05f..1f) {
                    change(music.copy(brightness = it))
                }
                SliderRow("Sensitivity", "${(music.sensitivity * 100).toInt()}%", music.sensitivity, 0f..1f) {
                    change(music.copy(sensitivity = it))
                }
            }

            if (music.style.usesColor) {
                SectionTitle("Color")
                Panel { ColorPicker("music", music.color) { change(music.copy(color = it)) } }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Made-up band levels with a steady beat, so the preview moves like music would. */
private fun demoLevels(t: Long, out: FloatArray) {
    val beat = 1f - (t % 500) / 500f
    for (i in out.indices) {
        val wobble = (0.5f + 0.5f * sin(t / (140f + i * 37f) + i * 1.7f)).pow(2)
        out[i] = if (i < 2) beat.pow(2) else wobble * (0.4f + 0.6f * beat)
    }
}
