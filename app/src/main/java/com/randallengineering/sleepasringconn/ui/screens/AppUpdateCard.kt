package com.randallengineering.sleepasringconn.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.randallengineering.sleepasringconn.update.AppUpdater
import kotlinx.coroutines.launch
import java.io.File

private sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data class UpToDate(val latestTitle: String?) : UpdateUiState
    data class Available(val release: AppUpdater.Release) : UpdateUiState
    data class Downloading(val release: AppUpdater.Release, val progress: Float) : UpdateUiState
    data class ReadyToInstall(val release: AppUpdater.Release, val apk: File) : UpdateUiState
    data class Error(val message: String, val release: AppUpdater.Release?) : UpdateUiState
}

@Composable
fun AppUpdateCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<UpdateUiState>(UpdateUiState.Idle) }
    var awaitingInstallPermission by remember { mutableStateOf(false) }
    val installedName = remember { AppUpdater.installedVersionName(context) }
    val installedCode = remember { AppUpdater.installedVersionCode(context) }

    fun check() {
        state = UpdateUiState.Checking
        scope.launch {
            state = when (val r = AppUpdater.check(context)) {
                is AppUpdater.CheckResult.UpdateAvailable -> UpdateUiState.Available(r.release)
                is AppUpdater.CheckResult.UpToDate -> UpdateUiState.UpToDate(r.release?.title)
                is AppUpdater.CheckResult.Failed -> UpdateUiState.Error(r.message, null)
            }
        }
    }

    fun install(release: AppUpdater.Release, apk: File) {
        if (AppUpdater.canInstall(context)) {
            AppUpdater.launchInstaller(context, apk)
        } else {
            // The user grants "Install unknown apps" in Settings; we continue on resume.
            awaitingInstallPermission = true
            AppUpdater.openInstallPermissionSettings(context)
        }
        state = UpdateUiState.ReadyToInstall(release, apk)
    }

    fun downloadAndInstall(release: AppUpdater.Release) {
        state = UpdateUiState.Downloading(release, 0f)
        scope.launch {
            state = try {
                val apk = AppUpdater.download(context, release) { p ->
                    state = UpdateUiState.Downloading(release, p)
                }
                install(release, apk)
                UpdateUiState.ReadyToInstall(release, apk)
            } catch (e: Exception) {
                UpdateUiState.Error(e.message ?: "Download failed", release)
            }
        }
    }

    LaunchedEffect(Unit) { check() }

    // Coming back from the "Install unknown apps" screen: continue the install if now allowed.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            val s = state
            if (event == Lifecycle.Event.ON_RESUME && awaitingInstallPermission &&
                s is UpdateUiState.ReadyToInstall && AppUpdater.canInstall(context) && s.apk.exists()
            ) {
                awaitingInstallPermission = false
                AppUpdater.launchInstaller(context, s.apk)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(modifier = Modifier.weight(1f)) {
                    Text("App Updates", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Installed: v$installedName (build code $installedCode)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = { check() },
                    enabled = state !is UpdateUiState.Checking && state !is UpdateUiState.Downloading
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Check for updates")
                }
            }

            when (val s = state) {
                UpdateUiState.Idle, UpdateUiState.Checking -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("Checking GitHub for a newer release…", style = MaterialTheme.typography.bodySmall)
                }

                is UpdateUiState.UpToDate -> Text(
                    if (s.latestTitle != null) "You're up to date (latest: ${s.latestTitle})." else "No releases published yet.",
                    style = MaterialTheme.typography.bodySmall
                )

                is UpdateUiState.Available -> {
                    Text("${s.release.title} is available.", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    ReleaseNotes(s.release.notes)
                    Button(onClick = { downloadAndInstall(s.release) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Download & install")
                    }
                }

                is UpdateUiState.Downloading -> {
                    Text("Downloading ${s.release.title}…", style = MaterialTheme.typography.bodySmall)
                    if (s.progress >= 0f) {
                        LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }

                is UpdateUiState.ReadyToInstall -> {
                    Text(
                        if (AppUpdater.canInstall(context)) "Downloaded. Confirm the install in the system prompt."
                        else "Allow \"Install unknown apps\" for SleepAsRingConn, then tap Install.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Button(onClick = { install(s.release, s.apk) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Install ${s.release.title}")
                    }
                }

                is UpdateUiState.Error -> {
                    Text("Update failed: ${s.message}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (s.release != null) {
                            OutlinedButton(onClick = { downloadAndInstall(s.release) }) { Text("Retry") }
                        }
                        TextButton(onClick = { AppUpdater.openReleasesPage(context) }) { Text("Open releases page") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReleaseNotes(notes: String) {
    val trimmed = notes.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("**Full Changelog") }
    if (trimmed.isEmpty()) return
    Text(
        trimmed.take(8).joinToString("\n"),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
