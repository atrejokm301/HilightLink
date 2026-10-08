package dev.kevtr.hilightlink.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kevtr.hilightlink.Control
import dev.kevtr.hilightlink.LedListenerService
import dev.kevtr.hilightlink.Leds
import dev.kevtr.hilightlink.Music
import dev.kevtr.hilightlink.Rule
import dev.kevtr.hilightlink.RuleStore
import dev.kevtr.hilightlink.TimerLight
import dev.kevtr.hilightlink.engine.Effect
import dev.kevtr.hilightlink.engine.Style
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        RuleStore.init(this)
        setContent {
            HilightTheme {
                var screen by rememberSaveable { mutableStateOf<String?>(null) }
                BackHandler(enabled = screen != null) { screen = null }
                AnimatedContent(
                    targetState = screen,
                    transitionSpec = {
                        val forward = targetState != null
                        (slideInHorizontally { if (forward) it / 4 else -it / 4 } + fadeIn()) togetherWith
                            (slideOutHorizontally { if (forward) -it / 4 else it / 4 } + fadeOut())
                    },
                    label = "screen",
                ) { target ->
                    when (target) {
                        null -> HomeScreen(onOpen = { screen = it })
                        CHARGING_SCREEN -> ChargingScreen(onBack = { screen = null })
                        MUSIC_SCREEN -> MusicScreen(onBack = { screen = null })
                        TIMER_SCREEN -> TimerScreen(onBack = { screen = null })
                        else -> EditorScreen(pkg = target, onBack = { screen = null })
                    }
                }
            }
        }
    }
}

/** Pseudo package names that route to the screens that are not app rules. */
const val CHARGING_SCREEN = "#charging"
const val MUSIC_SCREEN = "#music"
const val TIMER_SCREEN = "#timer"

/** Minutes between replays of a missed notification; 0 is off. */
private val REMINDER_CHOICES = listOf(0, 1, 2, 5, 10, 15, 30)

/** Idle animation on the home screen. */
private val SHOWCASE = Effect(style = Style.RAINBOW, periodMs = 3200)

data class AppEntry(val pkg: String, val label: String)

fun appLabel(context: Context, pkg: String): String {
    if (pkg == RuleStore.DEFAULT_PKG) return "All other apps"
    return runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
}

@Composable
fun AppIcon(pkg: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap = remember(pkg) {
        runCatching { context.packageManager.getApplicationIcon(pkg).toBitmap(120, 120).asImageBitmap() }.getOrNull()
    }
    if (bitmap != null) Image(bitmap, contentDescription = null, modifier = modifier.size(40.dp).clip(CircleShape))
    else Spacer(modifier.size(40.dp))
}

private fun listenerEnabled(context: Context): Boolean {
    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
    val me = ComponentName(context, LedListenerService::class.java)
    return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val config by RuleStore.config.collectAsStateWithLifecycle()
    val clock = rememberClock()
    var access by remember { mutableStateOf(listenerEnabled(context)) }
    var helper by remember { mutableStateOf<Boolean?>(null) }
    var picking by remember { mutableStateOf(false) }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val timer by TimerLight.running.collectAsStateWithLifecycle()

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val saved = runCatching {
            context.contentResolver.openOutputStream(uri)!!.use { it.write(RuleStore.exportJson().toByteArray()) }
        }.isSuccess
        Toast.makeText(context, if (saved) "Backup saved" else "Could not save the backup", Toast.LENGTH_SHORT).show()
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
        }.getOrNull()
        val restored = text != null && RuleStore.importJson(text)
        if (restored) Control.apply(context)
        Toast.makeText(
            context,
            if (restored) "Backup restored" else "That file is not a Hilight Link backup",
            Toast.LENGTH_LONG,
        ).show()
    }

    LifecycleResumeEffect(Unit) {
        access = listenerEnabled(context)
        Music.refresh(context)
        scope.launch { helper = withContext(Dispatchers.IO) { Leds.ensureRunning(context) } }
        onPauseOrDispose { }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = { LargeTopAppBar(title = { Text("Hilight Link") }, scrollBehavior = scroll) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { picking = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Add app") },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()).padding(horizontal = 16.dp),
            // Rows scroll under the gesture bar and the last one can still clear it.
            contentPadding = PaddingValues(bottom = padding.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { LedStage(clock, render = effectRenderer(SHOWCASE)) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusPill("Notifications", access)
                    StatusPill("Root helper", helper)
                }
            }
            if (!access) {
                item {
                    Notice(
                        text = "Hilight Link needs notification access to see when your apps notify you.",
                        action = "Grant access",
                        onAction = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                    )
                }
            }
            if (helper == false) {
                item {
                    Notice(
                        text = "The root helper is not running. Allow root for Hilight Link in your root " +
                            "manager (Magisk, KernelSU or APatch), then start it.",
                        action = "Start helper",
                        onAction = {
                            helper = null
                            scope.launch { helper = withContext(Dispatchers.IO) { Leds.restart(context) } }
                        },
                    )
                }
            }

            item { SectionTitle("Lights") }
            item {
                Group {
                    GroupRow(
                        title = "Pause all lights",
                        subtitle = if (config.paused) "Everything is off until you switch this back" else
                            "Also available as a Quick Settings tile",
                        leading = { IconBadge(PauseIcon) },
                        trailing = { Switch(config.paused, onCheckedChange = { Control.setPaused(context, it) }) },
                    )
                    GroupRow(
                        title = "Charging light",
                        subtitle = when {
                            config.charging.chargingOn && config.charging.chargedOn -> "While charging and when charged"
                            config.charging.chargingOn -> "While charging"
                            config.charging.chargedOn -> "When charged"
                            else -> "Off"
                        },
                        onClick = { onOpen(CHARGING_SCREEN) },
                        leading = { IconBadge(Bolt) },
                        trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                    )
                    GroupRow(
                        title = "Music visualizer",
                        subtitle = if (config.music.on) "${config.music.style.label}, follows what is playing" else "Off",
                        onClick = { onOpen(MUSIC_SCREEN) },
                        leading = { IconBadge(MusicIcon) },
                        trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                    )
                    GroupRow(
                        title = "Timer light",
                        subtitle = when {
                            (timer?.endAt ?: 0L) > SystemClock.elapsedRealtime() -> "Counting down"
                            config.timer.followClock -> "Follows Clock timers"
                            else -> "Start one from here"
                        },
                        onClick = { onOpen(TIMER_SCREEN) },
                        leading = { IconBadge(TimerIcon) },
                        trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                    )
                    ReminderRow(config.reminderMinutes)
                    GroupRow(
                        title = "Only when the screen is off",
                        subtitle = "Skip patterns while you are using the phone",
                        leading = { IconBadge(Icons.Default.Lock) },
                        trailing = { Switch(config.onlyScreenOff, onCheckedChange = RuleStore::setOnlyScreenOff) },
                    )
                }
            }

            item { SectionTitle("Apps") }
            item {
                val default = config.rules.firstOrNull { it.pkg == RuleStore.DEFAULT_PKG }
                val apps = config.rules.filter { it.pkg != RuleStore.DEFAULT_PKG }
                Group {
                    RuleRow(
                        pkg = RuleStore.DEFAULT_PKG,
                        rule = default,
                        onClick = {
                            if (default == null) RuleStore.put(Rule(RuleStore.DEFAULT_PKG, enabled = false))
                            onOpen(RuleStore.DEFAULT_PKG)
                        },
                    )
                    apps.forEach { rule -> RuleRow(pkg = rule.pkg, rule = rule, onClick = { onOpen(rule.pkg) }) }
                }
            }
            if (config.rules.none { it.pkg != RuleStore.DEFAULT_PKG }) {
                item {
                    Text(
                        "No apps linked yet. Tap Add app to give one its own light pattern.",
                        Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item { SectionTitle("Backup") }
            item {
                Group {
                    GroupRow(
                        title = "Save to a file",
                        subtitle = "All app rules and light settings",
                        onClick = { save.launch("hilight-link-backup.json") },
                        leading = { IconBadge(SaveIcon) },
                    )
                    GroupRow(
                        title = "Restore from a file",
                        subtitle = "Replaces the current rules and settings",
                        onClick = { restore.launch(arrayOf("application/json", "application/octet-stream", "text/*")) },
                        leading = { IconBadge(RestoreIcon) },
                    )
                }
            }
            item { Spacer(Modifier.height(96.dp)) }
        }
    }

    if (picking) {
        AppPicker(
            exclude = config.rules.map { it.pkg }.toSet(),
            onDismiss = { picking = false },
            onPick = { pkg ->
                picking = false
                // Start the new rule in the colour of the icon of the app.
                val color = iconColor(context, pkg)
                RuleStore.put(if (color != null) Rule(pkg, effect = Effect(colors = listOf(color))) else Rule(pkg))
                onOpen(pkg)
            },
        )
    }
}

@Composable
private fun ReminderRow(minutes: Int) {
    var open by remember { mutableStateOf(false) }
    Box {
        GroupRow(
            title = "Missed notification reminder",
            subtitle = if (minutes == 0) "Off" else "Replays every $minutes min until you unlock",
            onClick = { open = true },
            leading = { IconBadge(Icons.Default.Refresh) },
            trailing = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            REMINDER_CHOICES.forEach { m ->
                DropdownMenuItem(
                    text = { Text(if (m == 0) "Off" else "Every $m min") },
                    onClick = {
                        RuleStore.setReminderMinutes(m)
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
private fun StatusPill(label: String, ok: Boolean?) {
    val scheme = MaterialTheme.colorScheme
    val dot = when (ok) { true -> Color(0xFF00C853); false -> scheme.error; null -> scheme.outline }
    Surface(color = scheme.surfaceContainerHigh, shape = CircleShape) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(dot, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(
                "$label ${when (ok) { true -> "on"; false -> "off"; null -> "…" }}",
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun Notice(text: String, action: String, onAction: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(color = scheme.errorContainer, contentColor = scheme.onErrorContainer, shape = GroupShape) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text, style = MaterialTheme.typography.bodyLarge)
            Button(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun RuleRow(pkg: String, rule: Rule?, onClick: () -> Unit) {
    val context = LocalContext.current
    GroupRow(
        title = remember(pkg) { appLabel(context, pkg) },
        subtitle = when {
            rule == null -> "Not set"
            !rule.enabled -> "Off"
            else -> rule.effect.style.label
        },
        onClick = onClick,
        leading = {
            if (pkg == RuleStore.DEFAULT_PKG) IconBadge(Icons.Default.Notifications) else AppIcon(pkg)
        },
        trailing = {
            if (rule != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (rule.effect.style != Style.RAINBOW) ColorStack(rule.effect.colors)
                    Spacer(Modifier.width(12.dp))
                    Switch(rule.enabled, onCheckedChange = { RuleStore.put(rule.copy(enabled = it)) })
                }
            }
        },
    )
}

/** Overlapping dots showing a rule's colours at a glance. */
@Composable
private fun ColorStack(colors: List<Int>) {
    Box {
        colors.forEachIndexed { i, c ->
            Box(
                Modifier
                    .offset(x = (i * 12).dp)
                    .size(20.dp)
                    .background(Color(c), CircleShape)
                    .border(2.dp, MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
            )
        }
        Spacer(Modifier.width((20 + (colors.size - 1) * 12).dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppPicker(exclude: Set<String>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(launcher, 0)
                .map { it.activityInfo.packageName }
                .distinct()
                .filter { it != context.packageName }
                .map { AppEntry(it, appLabel(context, it)) }
                .sortedBy { it.label.lowercase() }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text("Choose an app", Modifier.padding(start = 8.dp, bottom = 12.dp), style = MaterialTheme.typography.headlineSmall)
            TextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search apps") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = CircleShape,
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            val list = apps
            if (list == null) {
                Text("Loading…", Modifier.padding(24.dp))
            } else {
                val shown = list.filter {
                    it.pkg !in exclude && (query.isBlank() || it.label.contains(query, ignoreCase = true))
                }
                LazyColumn(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    items(shown, key = { it.pkg }) { app ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { onPick(app.pkg) }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppIcon(app.pkg)
                            Spacer(Modifier.width(16.dp))
                            Text(
                                app.label,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}
