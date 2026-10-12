package dev.kevtr.hilightlink.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kevtr.hilightlink.Config
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

/** The sections of the app, one per item in the bottom bar. */
enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Default.Home),
    APPS("Apps", Icons.Default.Notifications),
    LIGHTS("Lights", Bolt),
}

/** Minutes between replays of a missed notification; 0 is off. */
private val REMINDER_CHOICES = listOf(0, 1, 2, 5, 10, 15, 30)

/** Idle animation on the home section. */
private val SHOWCASE = Effect(style = Style.RAINBOW, periodMs = 3200)

private fun listenerEnabled(context: Context): Boolean {
    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
    val me = ComponentName(context, LedListenerService::class.java)
    return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
}

/** The rules that stand for a whole app. */
private fun apps(rules: List<Rule>) = rules.filter { it.pkg != RuleStore.DEFAULT_PKG && !it.scoped }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(tab: Tab, onTab: (Tab) -> Unit, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val config by RuleStore.config.collectAsStateWithLifecycle()
    var access by remember { mutableStateOf(listenerEnabled(context)) }
    var helper by remember { mutableStateOf<Boolean?>(null) }
    var picking by remember { mutableStateOf(false) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    // Each section starts with its title open.
    val bar = rememberTopAppBarState()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(bar)
    LaunchedEffect(tab) {
        bar.heightOffset = 0f
        bar.contentOffset = 0f
    }

    val grantAccess = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
    val startHelper: () -> Unit = {
        helper = null
        scope.launch { helper = withContext(Dispatchers.IO) { Leds.restart(context) } }
    }

    LifecycleResumeEffect(Unit) {
        access = listenerEnabled(context)
        Music.refresh(context)
        scope.launch { helper = withContext(Dispatchers.IO) { Leds.ensureRunning(context) } }
        onPauseOrDispose { }
    }

    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet {
                SettingsMenu(config, access, helper, grantAccess, startHelper, onOpen)
            }
        },
    ) {
        Scaffold(
            modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
            topBar = {
                LargeTopAppBar(
                    title = {
                        Text(if (tab == Tab.HOME) "Hilight Link" else tab.label)
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawer.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Settings")
                        }
                    },
                    scrollBehavior = scroll,
                )
            },
            bottomBar = {
                NavigationBar {
                    Tab.entries.forEach { item ->
                        NavigationBarItem(
                            selected = item == tab,
                            onClick = { onTab(item) },
                            icon = { Icon(item.icon, contentDescription = null) },
                            label = { Text(item.label) },
                        )
                    }
                }
            },
            floatingActionButton = {
                if (tab == Tab.APPS) {
                    ExtendedFloatingActionButton(
                        onClick = { picking = true },
                        icon = { Icon(Icons.Default.Add, contentDescription = null) },
                        text = { Text("Add app") },
                    )
                }
            },
        ) { padding ->
            when (tab) {
                Tab.HOME -> HomeTab(padding, config, access, helper, grantAccess, startHelper, onTab)
                Tab.APPS -> AppsTab(padding, config, onOpen)
                Tab.LIGHTS -> LightsTab(padding, config, onOpen)
            }
        }
    }

    if (picking) {
        AppPicker(
            exclude = apps(config.rules).map { it.pkg }.toSet(),
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

/** The scrolling body every section shares. */
@Composable
private fun SectionList(padding: PaddingValues, content: LazyListScope.() -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun Chevron() = Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)

/** Preview, whether everything is working, and the master switch. */
@Composable
private fun HomeTab(
    padding: PaddingValues,
    config: Config,
    access: Boolean,
    helper: Boolean?,
    onGrantAccess: () -> Unit,
    onStartHelper: () -> Unit,
    onTab: (Tab) -> Unit,
) {
    val context = LocalContext.current
    val clock = rememberClock()
    SectionList(padding) {
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
                    onAction = onGrantAccess,
                )
            }
        }
        if (helper == false) {
            item {
                Notice(
                    text = "The root helper is not running. Allow root for Hilight Link in your root " +
                        "manager (Magisk, KernelSU or APatch), then start it.",
                    action = "Start helper",
                    onAction = onStartHelper,
                )
            }
        }
        item {
            Group {
                GroupRow(
                    title = "Pause all lights",
                    subtitle = if (config.paused) "Everything is off until you switch this back" else
                        "Also available as a Quick Settings tile",
                    leading = { IconBadge(PauseIcon) },
                    trailing = { Switch(config.paused, onCheckedChange = { Control.setPaused(context, it) }) },
                )
            }
        }

        item { SectionTitle("At a glance") }
        item {
            val linked = apps(config.rules).count { it.enabled }
            val lights = listOfNotNull(
                "calls".takeIf { config.call.on },
                "charging".takeIf { config.charging.chargingOn || config.charging.chargedOn },
                "music".takeIf { config.music.on },
                "Clock timers".takeIf { config.timer.followClock },
            )
            Group {
                GroupRow(
                    title = "Apps",
                    subtitle = when (linked) {
                        0 -> "No apps linked yet"
                        1 -> "1 app linked"
                        else -> "$linked apps linked"
                    },
                    onClick = { onTab(Tab.APPS) },
                    leading = { IconBadge(Tab.APPS.icon) },
                    trailing = { Chevron() },
                )
                GroupRow(
                    title = "Lights",
                    subtitle = if (lights.isEmpty()) "All off" else
                        "On for " + lights.joinToString(", "),
                    onClick = { onTab(Tab.LIGHTS) },
                    leading = { IconBadge(Tab.LIGHTS.icon) },
                    trailing = { Chevron() },
                )
            }
        }
    }
}

/** One light pattern per app, channel or contact. */
@Composable
private fun AppsTab(padding: PaddingValues, config: Config, onOpen: (String) -> Unit) {
    val default = config.rules.firstOrNull { it.pkg == RuleStore.DEFAULT_PKG }
    val apps = apps(config.rules)
    SectionList(padding) {
        item { SectionTitle("Your apps") }
        if (apps.isEmpty()) {
            item {
                Text(
                    "No apps linked yet. Tap Add app to give one its own light pattern.",
                    Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            item {
                Group {
                    // Each app, then its rules for single channels and contacts.
                    apps.forEach { app ->
                        RuleRow(pkg = app.pkg, rule = app, onClick = { onOpen(app.id) })
                        config.rules.filter { it.scoped && it.pkg == app.pkg }.forEach { part ->
                            RuleRow(pkg = part.pkg, rule = part, onClick = { onOpen(part.id) })
                        }
                    }
                }
            }
        }

        item { SectionTitle("Everything else") }
        item {
            Group {
                RuleRow(
                    pkg = RuleStore.DEFAULT_PKG,
                    rule = default,
                    onClick = {
                        if (default == null) RuleStore.put(Rule(RuleStore.DEFAULT_PKG, enabled = false))
                        onOpen(RuleStore.DEFAULT_PKG)
                    },
                )
            }
        }
        // Room for the last row to clear the Add app button.
        item { Spacer(Modifier.height(72.dp)) }
    }
}

/** The lights that are not tied to an app's notifications. */
@Composable
private fun LightsTab(padding: PaddingValues, config: Config, onOpen: (String) -> Unit) {
    val timer by TimerLight.running.collectAsStateWithLifecycle()
    SectionList(padding) {
        item { SectionTitle("Phone") }
        item {
            Group {
                GroupRow(
                    title = "Incoming calls",
                    subtitle = if (config.call.on) "${config.call.effect.style.label}, until you answer" else "Off",
                    onClick = { onOpen(CALL_SCREEN) },
                    leading = { IconBadge(Icons.Default.Call) },
                    trailing = { Chevron() },
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
                    trailing = { Chevron() },
                )
            }
        }

        item { SectionTitle("Extras") }
        item {
            Group {
                GroupRow(
                    title = "Music visualizer",
                    subtitle = if (config.music.on) "${config.music.style.label}, follows what is playing" else "Off",
                    onClick = { onOpen(MUSIC_SCREEN) },
                    leading = { IconBadge(MusicIcon) },
                    trailing = { Chevron() },
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
                    trailing = { Chevron() },
                )
            }
        }
    }
}

/** The side menu: when the lights stay dark, backups, and what the app needs from the phone. */
@Composable
private fun SettingsMenu(
    config: Config,
    access: Boolean,
    helper: Boolean?,
    onGrantAccess: () -> Unit,
    onStartHelper: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val context = LocalContext.current
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

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                "Settings",
                Modifier.padding(start = 12.dp, top = 24.dp, bottom = 4.dp),
                style = MaterialTheme.typography.headlineSmall,
            )
        }
        item { SectionTitle("When to light up") }
        item {
            Group {
                GroupRow(
                    title = "Quiet hours",
                    subtitle = when {
                        config.quiet.on -> "${formatMinute(config.quiet.startMinute)} to ${formatMinute(config.quiet.endMinute)}"
                        config.respectDnd -> "Off. Dark in Do Not Disturb"
                        else -> "Off"
                    },
                    onClick = { onOpen(QUIET_SCREEN) },
                    leading = { IconBadge(MoonIcon) },
                    trailing = { Chevron() },
                )
                GroupRow(
                    title = "Only when the screen is off",
                    subtitle = "Skip patterns while you are using the phone",
                    leading = { IconBadge(Icons.Default.Lock) },
                    trailing = { Switch(config.onlyScreenOff, onCheckedChange = RuleStore::setOnlyScreenOff) },
                )
                ReminderRow(config.reminderMinutes)
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

        item { SectionTitle("System") }
        item {
            Group {
                GroupRow(
                    title = "Notification access",
                    subtitle = if (access) "Granted" else "Not granted. Tap to open Settings",
                    onClick = onGrantAccess,
                    leading = { IconBadge(Icons.Default.Notifications) },
                    trailing = { Chevron() },
                )
                GroupRow(
                    title = "Root helper",
                    subtitle = when (helper) {
                        true -> "Running. Tap to restart"
                        false -> "Not running. Tap to start"
                        null -> "Checking…"
                    },
                    onClick = onStartHelper,
                    leading = { IconBadge(Icons.Default.Build) },
                )
            }
        }
    }
}

/** Opens in place to show the choices as chips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReminderRow(minutes: Int) {
    var open by remember { mutableStateOf(false) }
    val turn by animateFloatAsState(if (open) 180f else 0f, label = "arrow")
    Column(Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        GroupRow(
            title = "Missed notification reminder",
            subtitle = if (minutes == 0) "Off" else "Replays every $minutes min until you unlock",
            onClick = { open = !open },
            leading = { IconBadge(Icons.Default.Refresh) },
            trailing = { Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, Modifier.rotate(turn)) },
        )
        AnimatedVisibility(open) {
            FlowRow(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                REMINDER_CHOICES.forEach { m ->
                    PillChip(if (m == 0) "Off" else "$m min", selected = m == minutes, onClick = { RuleStore.setReminderMinutes(m) })
                }
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
            rule.scoped -> "${scopeLabel(rule)}: ${if (rule.enabled) rule.effect.style.label else "kept dark"}"
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
