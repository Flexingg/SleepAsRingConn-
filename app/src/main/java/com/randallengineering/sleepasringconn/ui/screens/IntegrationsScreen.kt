package com.randallengineering.sleepasringconn.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import com.randallengineering.sleepasringconn.data.AppDatabase
import com.randallengineering.sleepasringconn.healthconnect.HealthConnectManager
import com.randallengineering.sleepasringconn.sleepasandroid.SleepAsAndroidBridge
import com.randallengineering.sleepasringconn.ui.theme.SleepPurple
import com.randallengineering.sleepasringconn.ui.theme.StepsGreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun IntegrationsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val healthConnectManager = remember { HealthConnectManager(context) }
    val database = remember { AppDatabase.getDatabase(context) }

    var hasHCPermissions by remember { mutableStateOf(false) }
    var hcStatusMessage by remember { mutableStateOf("") }
    var unsyncedCount by remember { mutableStateOf(0) }
    var isSyncingToHC by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        scope.launch {
            hasHCPermissions = healthConnectManager.hasAllPermissions()
            hcStatusMessage = if (hasHCPermissions) "All permissions granted" else "Some permissions missing"
        }
    }

    LaunchedEffect(Unit) {
        hasHCPermissions = healthConnectManager.hasAllPermissions()
        withContext(Dispatchers.IO) {
            val unsynced = database.epochDao().getUnsyncedEpochs()
            unsyncedCount = unsynced.size
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "Integrations & Bridges",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        }

        // 1. Health Connect Integration Card
        item {
            var isAutoSync by remember { mutableStateOf(com.randallengineering.sleepasringconn.healthconnect.HealthConnectSyncScheduler.isAutoSyncEnabled(context)) }
            var syncInterval by remember { mutableIntStateOf(com.randallengineering.sleepasringconn.healthconnect.HealthConnectSyncScheduler.getSyncIntervalMinutes(context)) }
            var lastSyncTime by remember { mutableLongStateOf(com.randallengineering.sleepasringconn.healthconnect.HealthConnectSyncScheduler.getLastSyncMillis(context)) }
            var lastStatus by remember { mutableStateOf(com.randallengineering.sleepasringconn.healthconnect.HealthConnectSyncScheduler.getLastSyncStatus(context)) }

            val intervals = listOf(
                15 to "15m",
                30 to "30m",
                60 to "1h",
                180 to "3h",
                360 to "6h",
                720 to "12h",
                1440 to "24h"
            )

            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Default.HealthAndSafety, contentDescription = null, tint = StepsGreen, modifier = Modifier.size(28.dp))
                            Column {
                                Text("Google Health Connect", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(
                                    if (hasHCPermissions) "Connected & Authorized" else "Permissions required",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (hasHCPermissions) StepsGreen else MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }

                    Text(
                        text = "Exports Heart Rate, HRV (RMSSD), SpO2, Skin Temp, Respiratory Rate, and Sleep Stages into Google Health Connect entirely on-device.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (!hasHCPermissions) {
                        Button(
                            onClick = {
                                permissionLauncher.launch(healthConnectManager.requiredPermissions)
                            },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Grant Health Connect Permissions")
                        }
                    } else {
                        // Background Auto-Sync Settings
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Background Auto-Sync", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    if (isAutoSync) "Runs periodically in background via WorkManager" else "Disabled (Manual export only)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = isAutoSync,
                                onCheckedChange = { enabled ->
                                    isAutoSync = enabled
                                    com.randallengineering.sleepasringconn.healthconnect.HealthConnectSyncScheduler.setAutoSyncEnabled(context, enabled)
                                }
                            )
                        }

                        if (isAutoSync) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    "Sync Interval Frequency",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.SemiBold
                                )
                                androidx.compose.foundation.lazy.LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(intervals.size) { i ->
                                        val (mins, label) = intervals[i]
                                        FilterChip(
                                            selected = syncInterval == mins,
                                            onClick = {
                                                syncInterval = mins
                                                com.randallengineering.sleepasringconn.healthconnect.HealthConnectSyncScheduler.setSyncIntervalMinutes(context, mins)
                                            },
                                            label = { Text(label) }
                                        )
                                    }
                                }
                            }
                        }

                        // Last Sync Info & Manual Export
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("Last Background Sync", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    val timeStr = if (lastSyncTime > 0) {
                                        java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault()).format(java.util.Date(lastSyncTime))
                                    } else "Never"
                                    Text(timeStr, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                    Text(lastStatus, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                FilledTonalButton(
                                    onClick = {
                                        scope.launch {
                                            isSyncingToHC = true
                                            withContext(Dispatchers.IO) {
                                                val unsynced = database.epochDao().getUnsyncedEpochs()
                                                val written = healthConnectManager.writeEpochs(unsynced)
                                                val bulk = unsynced.mapNotNull { com.randallengineering.sleepasringconn.protocol.BulkRecord.parseRecord(it.rawBytes) }
                                                val sessions = com.randallengineering.sleepasringconn.analytics.SleepStagingEngine.extractAllSleepSessions(bulk)
                                                for (s in sessions) {
                                                    healthConnectManager.writeSleepSession(s)
                                                }
                                                if (written > 0) {
                                                    database.epochDao().markSynced(unsynced.map { it.counter })
                                                }
                                                val recentStatus = database.deviceStatusDao().getRecentStatusLogsList(50)
                                                val tempCount = healthConnectManager.writeSkinTemperatures(recentStatus)
                                                val total = written + tempCount
                                                com.randallengineering.sleepasringconn.healthconnect.HealthConnectSyncScheduler.recordSyncResult(
                                                    context,
                                                    "Exported $total records",
                                                    total
                                                )
                                                unsyncedCount = database.epochDao().getUnsyncedEpochs().size
                                                lastSyncTime = System.currentTimeMillis()
                                                lastStatus = "Exported $total records"
                                            }
                                            isSyncingToHC = false
                                        }
                                    },
                                    enabled = !isSyncingToHC,
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    if (isSyncingToHC) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                        Spacer(Modifier.width(6.dp))
                                    }
                                    Text("Sync Now ($unsyncedCount)")
                                }
                            }
                        }
                    }
                }
            }
        }

        // 2. Sleep as Android DIY Wearable Bridge
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Default.Bedtime, contentDescription = null, tint = SleepPurple, modifier = Modifier.size(28.dp))
                            Column {
                                Text("Sleep as Android Wearable API", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(
                                    if (SleepAsAndroidBridge.isTrackingActive) "Active Tracking Session" else "Standby (Listening for broadcasts)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (SleepAsAndroidBridge.isTrackingActive) SleepPurple else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Text(
                        text = "Connect RingConn Gen 2 with Sleep as Android:",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )

                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(start = 4.dp)
                    ) {
                        Text("1. Open Sleep as Android → Settings → Wearables", style = MaterialTheme.typography.bodySmall)
                        Text("2. Set 'Sleep tracking' to 'Gear, Galaxy Gear, DIY or other'", style = MaterialTheme.typography.bodySmall)
                        Text("3. Scroll to 'Wearable integration (DIY)' → tap 'Custom package name'", style = MaterialTheme.typography.bodySmall)
                        Text("4. Paste the package name below and hit OK", style = MaterialTheme.typography.bodySmall)
                        Text("5. Tap 'Test sensor' in SaA to verify real-time movement and HR telemetry!", style = MaterialTheme.typography.bodySmall)
                    }

                    // Package Name Card with Copy Button
                    val packageName = context.packageName
                    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current

                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Package Name (for SaA / storage setup)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = packageName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            IconButton(
                                onClick = {
                                    clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(packageName))
                                    android.widget.Toast.makeText(context, "Copied package name: $packageName", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy Package Name", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                SleepAsAndroidBridge.sendConfirmConnected(context)
                                SleepAsAndroidBridge.sendExtraSensorData(
                                    context = context,
                                    hr = 70f,
                                    spo2 = 98f,
                                    respirationRate = 15f
                                )
                            },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Send Test Ping to SaA")
                        }
                    }
                }
            }
        }
    }
}
