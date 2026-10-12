package dev.kevtr.hilightlink.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import dev.kevtr.hilightlink.RuleStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        RuleStore.init(this)
        setContent {
            HilightTheme {
                var screen by rememberSaveable { mutableStateOf<String?>(null) }
                var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
                BackHandler(enabled = screen != null || tab != Tab.HOME) {
                    if (screen != null) screen = null else tab = Tab.HOME
                }
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
                        null -> MainScreen(tab = tab, onTab = { tab = it }, onOpen = { screen = it })
                        CHARGING_SCREEN -> ChargingScreen(onBack = { screen = null })
                        MUSIC_SCREEN -> MusicScreen(onBack = { screen = null })
                        TIMER_SCREEN -> TimerScreen(onBack = { screen = null })
                        CALL_SCREEN -> CallScreen(onBack = { screen = null })
                        QUIET_SCREEN -> QuietScreen(onBack = { screen = null })
                        else -> EditorScreen(id = target, onBack = { screen = null }, onOpen = { screen = it })
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
const val CALL_SCREEN = "#call"
const val QUIET_SCREEN = "#quiet"

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPicker(exclude: Set<String>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
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
