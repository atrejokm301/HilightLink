package dev.kevtr.hilightlink.ui

import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kevtr.hilightlink.Quiet
import dev.kevtr.hilightlink.QuietConfig
import dev.kevtr.hilightlink.RuleStore
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** A minute of the day the way the phone writes times, such as "22:00" or "10:00 PM". */
fun formatMinute(minute: Int): String =
    LocalTime.of(minute / 60, minute % 60).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuietScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val config by RuleStore.config.collectAsStateWithLifecycle()
    val quiet = config.quiet
    // Changes show on the LEDs straight away when they move the phone in or out of quiet hours.
    fun change(next: QuietConfig) {
        RuleStore.setQuiet(next)
        Quiet.refresh(context)
    }
    fun pick(minute: Int, onPick: (Int) -> Unit) {
        TimePickerDialog(context, { _, h, m -> onPick(h * 60 + m) }, minute / 60, minute % 60, DateFormat.is24HourFormat(context))
            .show()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Quiet hours") },
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
                "Keeps notification lights, the waiting glow and the charging light off at the times you " +
                    "choose. The timer and the music visualizer are not affected.",
                Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Group {
                GroupRow(
                    title = "Quiet hours",
                    subtitle = "Every day, ${formatMinute(quiet.startMinute)} to ${formatMinute(quiet.endMinute)}",
                    trailing = { Switch(quiet.on, onCheckedChange = { change(quiet.copy(on = it)) }) },
                )
                if (quiet.on) {
                    GroupRow(
                        title = "Starts",
                        subtitle = formatMinute(quiet.startMinute),
                        onClick = { pick(quiet.startMinute) { change(quiet.copy(startMinute = it)) } },
                    )
                    GroupRow(
                        title = "Ends",
                        subtitle = formatMinute(quiet.endMinute),
                        onClick = { pick(quiet.endMinute) { change(quiet.copy(endMinute = it)) } },
                    )
                    GroupRow(
                        title = "Dim instead of off",
                        subtitle = "Lights still play, at low brightness",
                        trailing = { Switch(quiet.dim, onCheckedChange = { change(quiet.copy(dim = it)) }) },
                    )
                    GroupRow(
                        title = "Calls still light up",
                        subtitle = "A ringing call plays its pattern as usual",
                        trailing = { Switch(quiet.calls, onCheckedChange = { change(quiet.copy(calls = it)) }) },
                    )
                }
            }
            if (quiet.on && !quiet.dim) {
                Text(
                    "A notification that arrives during quiet hours and is set to stay lit starts to glow when they end.",
                    Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionTitle("Do Not Disturb")
            Group {
                GroupRow(
                    title = "Stay dark in Do Not Disturb",
                    subtitle = "No light for notifications and calls that Do Not Disturb silences. " +
                        "The ones it lets through still light up.",
                    trailing = { Switch(config.respectDnd, onCheckedChange = RuleStore::setRespectDnd) },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
