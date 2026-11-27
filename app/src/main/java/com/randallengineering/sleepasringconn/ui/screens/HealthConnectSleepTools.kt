package com.randallengineering.sleepasringconn.ui.screens

import android.content.pm.PackageManager
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.randallengineering.sleepasringconn.healthconnect.HealthConnectExporter
import com.randallengineering.sleepasringconn.healthconnect.HealthConnectManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shows which apps wrote the sleep sessions Health Connect has for the last 30 days, and
 * offers a full rebuild of this app's sleep history there.
 */
@Composable
fun HealthConnectSleepTools(manager: HealthConnectManager) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sources by remember { mutableStateOf<List<Pair<String, Int>>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmRebuild by remember { mutableStateOf(false) }

    suspend fun loadSources() {
        sources = withContext(Dispatchers.IO) {
            try {
                val now = System.currentTimeMillis()
                manager.sleepSessionCountsBySource(now - HealthConnectExporter.SLEEP_WINDOW_MS, now)
                    .map { (pkg, n) -> appLabel(context, pkg) to n }
                    .sortedByDescending { it.second }
            } catch (e: Exception) {
                message = "Couldn't read Health Connect: ${e.message}"
                emptyList()
            }
        }
    }

    LaunchedEffect(Unit) { loadSources() }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Sleep in Health Connect (last 30 days)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        when (val list = sources) {
            null -> Text("Reading…", style = MaterialTheme.typography.bodySmall)
            else -> if (list.isEmpty()) {
                Text("No sleep sessions found.", style = MaterialTheme.typography.bodySmall)
            } else {
                list.forEach { (name, n) ->
                    Text("• $name: $n session${if (n == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall)
                }
                if (list.size > 1) {
                    Text(
                        "Other apps' sessions show up next to ours in Health Connect and in apps that read it. " +
                            "This app can only change its own; turn off sleep writing in the other app if nights look split.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        OutlinedButton(
            onClick = { confirmRebuild = true },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Rebuilding…")
            } else {
                Text("Rebuild sleep history in Health Connect")
            }
        }
        message?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }

    if (confirmRebuild) {
        AlertDialog(
            onDismissRequest = { confirmRebuild = false },
            title = { Text("Rebuild sleep history?") },
            text = {
                Text(
                    "Deletes every sleep session this app has written to Health Connect and writes them again " +
                        "from the ring data stored on this phone, one session per night. Other apps' data isn't touched."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRebuild = false
                    busy = true
                    message = null
                    scope.launch {
                        message = withContext(Dispatchers.IO) {
                            try {
                                val n = HealthConnectExporter.rebuildSleepHistory(context, manager)
                                "Rebuilt: wrote $n sleep sessions."
                            } catch (e: Exception) {
                                "Rebuild failed: ${e.message ?: e.javaClass.simpleName}"
                            }
                        }
                        loadSources()
                        busy = false
                    }
                }) { Text("Rebuild") }
            },
            dismissButton = { TextButton(onClick = { confirmRebuild = false }) { Text("Cancel") } }
        )
    }
}

private fun appLabel(context: android.content.Context, pkg: String): String =
    if (pkg == context.packageName) "SleepAsRingConn (this app)" else try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, PackageManager.GET_META_DATA)).toString()
    } catch (e: Exception) {
        pkg
    }
