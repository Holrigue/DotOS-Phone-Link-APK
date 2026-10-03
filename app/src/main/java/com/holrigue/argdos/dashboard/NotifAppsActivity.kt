package com.holrigue.argdos.dashboard

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Choose apps": which apps' notifications are forwarded to the watch. Every app
 * is forwarded by default; switching one off adds it to [Prefs.mutedApps], which
 * [NotificationRelayService] checks before relaying.
 */
class NotifAppsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DotTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    NotifAppsScreen(onClose = { finish() })
                }
            }
        }
    }
}

private data class AppRow(val label: String, val pkg: String)

// Launchable apps (the ones a person recognises), excluding this app itself.
// Needs the MAIN/LAUNCHER <queries> entry in the manifest for package visibility
// on Android 11+.
private fun loadLaunchableApps(ctx: Context): List<AppRow> {
    val pm = ctx.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    @Suppress("DEPRECATION")
    val found = pm.queryIntentActivities(intent, 0)
    return found
        .map { AppRow(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
        .filter { it.pkg != ctx.packageName }
        .distinctBy { it.pkg }
        .sortedBy { it.label.lowercase() }
}

@Composable
private fun NotifAppsScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf<List<AppRow>?>(null) }
    val muted = remember { mutableStateListOf<String>().also { it.addAll(Prefs.mutedApps(ctx)) } }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { loadLaunchableApps(ctx) }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Choose apps", style = MaterialTheme.typography.headlineSmall, color = DotWhite)
            Button(onClick = onClose) { Text("Done") }
        }
        Text(
            "Notifications from apps switched off here are not sent to the watch. " +
                "Everything is on by default.",
            style = MaterialTheme.typography.bodySmall,
            color = DotGrey,
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search apps") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            onClick = { Prefs.clearMutedApps(ctx); muted.clear() },
            enabled = muted.isNotEmpty(),
        ) { Text("Turn all apps back on") }

        val list = apps
        if (list == null) {
            Text("Loading apps...", style = MaterialTheme.typography.bodyMedium, color = DotGrey)
        } else {
            val shown = if (query.isBlank()) list
                        else list.filter { it.label.contains(query, ignoreCase = true) }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(shown, key = { it.pkg }) { app ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(app.label, style = MaterialTheme.typography.bodyMedium, color = DotWhite)
                            Text(app.pkg, style = MaterialTheme.typography.bodySmall, color = DotGrey)
                        }
                        Switch(
                            checked = app.pkg !in muted,
                            onCheckedChange = { on ->
                                Prefs.setAppMuted(ctx, app.pkg, muted = !on)
                                if (on) muted.remove(app.pkg) else muted.add(app.pkg)
                            },
                        )
                    }
                }
            }
        }
    }
}
