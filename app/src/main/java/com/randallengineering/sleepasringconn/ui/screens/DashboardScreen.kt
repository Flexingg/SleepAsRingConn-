package com.randallengineering.sleepasringconn.ui.screens

import android.bluetooth.BluetoothDevice
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.randallengineering.sleepasringconn.ble.BleConnectionManager
import com.randallengineering.sleepasringconn.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onNavigateToDiagnostics: () -> Unit
) {
    val isConnected by BleConnectionManager.isConnected.collectAsState()
    val connectionState by BleConnectionManager.connectionState.collectAsState()
    val deviceStatus by BleConnectionManager.latestDeviceStatus.collectAsState()
    val liveHr by BleConnectionManager.liveHeartRate.collectAsState()
    val liveSpo2 by BleConnectionManager.liveSpo2.collectAsState()
    val liveHrv by BleConnectionManager.liveHrv.collectAsState()
    val isLiveMonitoring by BleConnectionManager.isLiveMonitoring.collectAsState()
    val isSyncing by BleConnectionManager.isSyncing.collectAsState()
    val isRingLedOn by BleConnectionManager.isRingLedOn.collectAsState()
    val discoveredDevices by BleConnectionManager.discoveredDevices.collectAsState()

    var showDeviceSheet by remember { mutableStateOf(false) }

    val context = androidx.compose.ui.platform.LocalContext.current
    val motionSensorManager = remember { com.randallengineering.sleepasringconn.sensor.MotionSensorManager.getInstance(context) }
    val rawAccel by motionSensorManager.rawAcceleration.collectAsState()
    val currentMagnitude by motionSensorManager.currentMagnitude.collectAsState()
    val last10sMaxAccel by motionSensorManager.last10sMaxAcceleration.collectAsState()

    DisposableEffect(Unit) {
        motionSensorManager.start()
        onDispose {
            if (!com.randallengineering.sleepasringconn.sleepasandroid.SleepAsAndroidBridge.isTrackingActive) {
                motionSensorManager.stop()
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Connection Header & Ring Status Card
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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(if (isConnected) StepsGreen else HeartRateRed)
                            )
                            Text(
                                text = "RingConn Gen 2",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (!isConnected) {
                            FilledTonalButton(
                                onClick = {
                                    BleConnectionManager.startScan()
                                    showDeviceSheet = true
                                },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.BluetoothSearching, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Scan & Connect")
                            }
                        } else {
                            OutlinedButton(
                                onClick = { BleConnectionManager.disconnect() },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Disconnect")
                            }
                        }
                    }

                    Text(
                        text = connectionState,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Battery & Voltage indicators
                    if (isConnected && deviceStatus != null) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            BatteryBadge(
                                title = "Ring Battery",
                                percent = deviceStatus!!.batteryPercent,
                                isCharging = deviceStatus!!.isOnCharger,
                                extra = deviceStatus!!.batteryVoltageMv?.let { "${it}mV" }
                            )

                            deviceStatus!!.caseBattery?.let { case ->
                                BatteryBadge(
                                    title = "Case Battery",
                                    percent = case.percent,
                                    isCharging = case.isCharging,
                                    extra = if (case.isCharging) "Charging" else "In case"
                                )
                            }
                        }
                    }
                }
            }
        }

        // 2. Live Vitals Grid
        item {
            Text(
                text = "Live Metrics",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MetricCard(
                    modifier = Modifier.weight(1f),
                    title = "Heart Rate",
                    value = liveHr?.toString() ?: "--",
                    unit = "BPM",
                    icon = Icons.Default.Favorite,
                    color = HeartRateRed,
                    isLive = isLiveMonitoring
                )

                MetricCard(
                    modifier = Modifier.weight(1f),
                    title = "HRV (RMSSD)",
                    value = liveHrv?.toString() ?: "--",
                    unit = "ms",
                    icon = Icons.Default.MonitorHeart,
                    color = Color(0xFF9C27B0),
                    isLive = isConnected
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MetricCard(
                    modifier = Modifier.weight(1f),
                    title = "Blood Oxygen",
                    value = liveSpo2?.toString() ?: "--",
                    unit = "%",
                    icon = Icons.Default.Air,
                    color = Spo2Blue,
                    isLive = isLiveMonitoring
                )

                MetricCard(
                    modifier = Modifier.weight(1f),
                    title = "Skin Temp",
                    value = deviceStatus?.skinTemperature?.let { "%.1f".format(it.celsius) } ?: "--",
                    unit = "°C",
                    icon = Icons.Default.DeviceThermostat,
                    color = TempAmber,
                    isLive = isConnected
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MetricCard(
                    modifier = Modifier.weight(1f),
                    title = "Steps (15m)",
                    value = deviceStatus?.quarterHourSteps?.toString() ?: "--",
                    unit = "steps",
                    icon = Icons.Default.DirectionsWalk,
                    color = StepsGreen,
                    isLive = isConnected
                )
            }
        }

        // 3. Accelerometer & Motion Telemetry (Sleep as Android Actigraphy)
        item {
            Text(
                text = "Accelerometer & Actigraphy",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }

        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Speed, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text(
                                text = "Motion & Accelerometer",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // Sleep stage classification badge based on actigraphy
                        val (stageText, stageColor) = when {
                            last10sMaxAccel < 0.05f -> "Still (Deep/REM Stage)" to StepsGreen
                            last10sMaxAccel in 0.05f..0.30f -> "Light Motion" to TempAmber
                            else -> "Active (Awake Stage)" to HeartRateRed
                        }

                        Surface(
                            color = stageColor.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = stageText,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = stageColor
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surface
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text("Live Δa", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("%.2f m/s²".format(currentMagnitude), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            }
                        }

                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surface
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text("10s Peak (SaA)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("%.2f m/s²".format(last10sMaxAccel), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "3-Axis: X: %.2f · Y: %.2f · Z: %.2f m/s²".format(rawAccel.first, rawAccel.second, rawAccel.third),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // 4. Quick Action Controls
        item {
            Text(
                text = "Controls & Actions",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = { BleConnectionManager.syncHistory() },
                        enabled = isConnected && !isSyncing,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(if (isSyncing) "Syncing..." else "Sync Now")
                    }

                    FilledTonalButton(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            if (isLiveMonitoring) {
                                BleConnectionManager.stopLiveMonitoring()
                            } else {
                                BleConnectionManager.startLiveMonitoring(hrMode = true)
                            }
                        },
                        enabled = isConnected,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(
                            if (isLiveMonitoring) Icons.Default.Stop else Icons.Default.Favorite,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(if (isLiveMonitoring) "Stop Live" else "Live Pulse")
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    FilledTonalButton(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            if (isLiveMonitoring) {
                                BleConnectionManager.stopLiveMonitoring()
                            } else {
                                BleConnectionManager.startLiveMonitoring(hrMode = false)
                            }
                        },
                        enabled = isConnected,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Default.Air, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Live SpO2")
                    }
                    if (isRingLedOn) {
                        FilledTonalButton(
                            modifier = Modifier.weight(1f),
                            onClick = { BleConnectionManager.toggleFindRingLed(false) },
                            enabled = isConnected,
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        ) {
                            Icon(Icons.Default.Highlight, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text("LED On (Tap: Off)")
                        }
                    } else {
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = { BleConnectionManager.toggleFindRingLed(true) },
                            enabled = isConnected,
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(Icons.Default.Highlight, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Light Ring LED")
                        }
                    }

                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = onNavigateToDiagnostics,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("BLE Console")
                    }
                }
            }
        }
    }

    // Modal Sheet for Discovered Bluetooth Devices
    if (showDeviceSheet) {
        ModalBottomSheet(
            onDismissRequest = {
                BleConnectionManager.stopScan()
                showDeviceSheet = false
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Nearby Devices",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(onClick = { BleConnectionManager.startScan() }) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Rescan")
                    }
                }

                if (discoveredDevices.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator()
                            Text("Searching for RingConn Gen 2...")
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(discoveredDevices) { device ->
                            val name = device.name ?: "Unknown Device"
                            val isRingConn = name.contains("RingConn", ignoreCase = true)

                            if (isRingConn) {
                                OutlinedCard(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            BleConnectionManager.connect(device)
                                            showDeviceSheet = false
                                        },
                                    shape = RoundedCornerShape(16.dp),
                                    colors = CardDefaults.outlinedCardColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                                    ),
                                    border = androidx.compose.foundation.BorderStroke(
                                        width = 1.5.dp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(40.dp)
                                                    .clip(CircleShape)
                                                    .background(MaterialTheme.colorScheme.primary),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    Icons.Default.Watch,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onPrimary,
                                                    modifier = Modifier.size(22.dp)
                                                )
                                            }
                                            Column {
                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    Text(name, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
                                                    SuggestionChip(
                                                        onClick = {},
                                                        label = { Text("RingConn", fontSize = 10.sp, fontWeight = FontWeight.Bold) },
                                                        colors = SuggestionChipDefaults.suggestionChipColors(
                                                            containerColor = MaterialTheme.colorScheme.primary,
                                                            labelColor = MaterialTheme.colorScheme.onPrimary
                                                        ),
                                                        border = null,
                                                        modifier = Modifier.height(22.dp)
                                                    )
                                                }
                                                Text(device.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }

                                        Button(
                                            onClick = {
                                                BleConnectionManager.connect(device)
                                                showDeviceSheet = false
                                            },
                                            shape = RoundedCornerShape(12.dp)
                                        ) {
                                            Text("Connect")
                                        }
                                    }
                                }
                            } else {
                                ListItem(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable {
                                            BleConnectionManager.connect(device)
                                            showDeviceSheet = false
                                        },
                                    headlineContent = { Text(name, fontWeight = FontWeight.Medium) },
                                    supportingContent = { Text(device.address) },
                                    leadingContent = { Icon(Icons.Default.Bluetooth, contentDescription = null) },
                                    trailingContent = {
                                        OutlinedButton(
                                            onClick = {
                                                BleConnectionManager.connect(device)
                                                showDeviceSheet = false
                                            },
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Text("Pair")
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BatteryBadge(
    title: String,
    percent: Int,
    isCharging: Boolean,
    extra: String? = null
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            if (isCharging) Icons.Default.BatteryChargingFull else Icons.Default.BatteryFull,
            contentDescription = null,
            tint = if (isCharging) StepsGreen else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Column {
            Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "$percent%" + (extra?.let { " ($it)" } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun MetricCard(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    unit: String,
    icon: ImageVector,
    color: Color,
    isLive: Boolean
) {
    ElevatedCard(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            }

            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = unit,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
        }
    }
}
