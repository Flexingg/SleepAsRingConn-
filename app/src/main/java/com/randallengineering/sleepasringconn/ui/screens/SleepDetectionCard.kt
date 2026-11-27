package com.randallengineering.sleepasringconn.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.randallengineering.sleepasringconn.analytics.SleepDetectionMode
import com.randallengineering.sleepasringconn.analytics.SleepDetectionSensitivity
import com.randallengineering.sleepasringconn.data.SleepDetectionPreferences
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sleep detection settings (mode + sensitivity) and the manual Start/Stop sleep control.
 * [onSessionsChanged] is called whenever the list of sessions may have changed.
 */
@Composable
fun SleepDetectionCard(onSessionsChanged: () -> Unit) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(SleepDetectionPreferences.getMode(context)) }
    var sensitivity by remember { mutableStateOf(SleepDetectionPreferences.getSensitivity(context)) }
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { SleepDetectionPreferences.refreshManualStart(context) }
    val manualStart by SleepDetectionPreferences.manualStart.collectAsState()

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(manualStart) {
        while (manualStart != null) {
            now = System.currentTimeMillis()
            delay(30_000L)
        }
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Sleep detection", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        if (mode == SleepDetectionMode.MANUAL) mode.label else "${mode.label} • ${sensitivity.label}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Done" else "Settings")
                }
            }

            // Manual Start/Stop
            val start = manualStart
            if (start != null) {
                val mins = ((now - start) / 60_000L).coerceAtLeast(0)
                val startText = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(start))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Bedtime, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Recording sleep since $startText",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                "${mins / 60}h ${mins % 60}m",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val saved = SleepDetectionPreferences.stopManualSession(context)
                            Toast.makeText(
                                context,
                                if (saved) "Sleep session saved" else "Under 10 minutes, so it wasn't saved",
                                Toast.LENGTH_SHORT
                            ).show()
                            onSessionsChanged()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Stop sleep")
                    }
                    OutlinedButton(onClick = { SleepDetectionPreferences.cancelManualSession(context) }) {
                        Text("Discard")
                    }
                }
            } else {
                FilledTonalButton(
                    onClick = { SleepDetectionPreferences.startManualSession(context) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Start sleep now")
                }
            }

            if (expanded) {
                HorizontalDivider()
                Text("Mode", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                SleepDetectionMode.entries.forEach { option ->
                    OptionRow(
                        selected = mode == option,
                        title = option.label,
                        description = option.description,
                        onClick = {
                            mode = option
                            SleepDetectionPreferences.setMode(context, option)
                            onSessionsChanged()
                        }
                    )
                }
                if (mode != SleepDetectionMode.MANUAL) {
                    Text("Sensitivity", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    SleepDetectionSensitivity.entries.forEach { option ->
                        OptionRow(
                            selected = sensitivity == option,
                            title = option.label,
                            description = option.description,
                            onClick = {
                                sensitivity = option
                                SleepDetectionPreferences.setSensitivity(context, option)
                                onSessionsChanged()
                            }
                        )
                    }
                }
                Text(
                    if (mode == SleepDetectionMode.MANUAL)
                        "When Sleep as Android starts and stops tracking, a session is recorded for that time."
                    else
                        "Changing these updates your past sessions and, on the next sync, Health Connect.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun OptionRow(selected: Boolean, title: String, description: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
