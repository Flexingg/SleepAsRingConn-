package com.randallengineering.sleepasringconn.ui.screens

import android.bluetooth.BluetoothDevice
import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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

                    // Live Graphical Motion Visualizer (Seismograph Waveform & 3D Tilt Reticle)
                    LiveMotionVisualizer(
                        magnitude = currentMagnitude,
                        x = rawAccel.first,
                        y = rawAccel.second,
                        z = rawAccel.third,
                        peak10s = last10sMaxAccel
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "3-Axis: X: %.2f · Y: %.2f · Z: %.2f m/s²".format(rawAccel.first, rawAccel.second, rawAccel.third),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Ring Motion Sensor",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
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

@Composable
fun LiveMotionVisualizer(
    magnitude: Float,
    x: Float,
    y: Float,
    z: Float,
    peak10s: Float
) {
    // Rolling buffer of the last 60 live magnitude samples for the seismograph
    val history = remember { mutableStateListOf<Float>() }

    LaunchedEffect(magnitude) {
        history.add(magnitude)
        if (history.size > 60) {
            history.removeAt(0)
        }
    }

    val motionColor = when {
        magnitude < 0.05f -> StepsGreen
        magnitude < 0.30f -> TempAmber
        else -> HeartRateRed
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(motionColor)
                )
                Text(
                    text = "Live Seismograph & Ring Orientation",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Text(
                text = "%.2f m/s²".format(magnitude),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = motionColor
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. Live Waveform / Seismograph Canvas
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.3f))
            ) {
                Canvas(modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 6.dp)) {
                    val w = size.width
                    val h = size.height
                    val maxScale = 2.0f // max 2.0 m/s^2 for full height

                    // Draw grid lines
                    val gridColor = Color.White.copy(alpha = 0.08f)
                    drawLine(gridColor, Offset(0f, h * 0.25f), Offset(w, h * 0.25f), strokeWidth = 1f)
                    drawLine(gridColor, Offset(0f, h * 0.50f), Offset(w, h * 0.50f), strokeWidth = 1f)
                    drawLine(gridColor, Offset(0f, h * 0.75f), Offset(w, h * 0.75f), strokeWidth = 1f)

                    if (history.size >= 2) {
                        val stepX = w / 59f
                        val path = Path()
                        val fillPath = Path()

                        val startIdx = (60 - history.size).coerceAtLeast(0)
                        val startX = startIdx * stepX
                        val startY = h - (history[0] / maxScale).coerceIn(0f, 1f) * h

                        path.moveTo(startX, startY)
                        fillPath.moveTo(startX, h)
                        fillPath.lineTo(startX, startY)

                        for (i in 1 until history.size) {
                            val curX = (startIdx + i) * stepX
                            val curY = h - (history[i] / maxScale).coerceIn(0f, 1f) * (h - 4f)
                            path.lineTo(curX, curY)
                            fillPath.lineTo(curX, curY)
                        }

                        fillPath.lineTo(w, h)
                        fillPath.close()

                        drawPath(
                            path = fillPath,
                            brush = Brush.verticalGradient(
                                colors = listOf(motionColor.copy(alpha = 0.35f), motionColor.copy(alpha = 0.02f)),
                                startY = 0f,
                                endY = h
                            )
                        )

                        drawPath(
                            path = path,
                            color = motionColor,
                            style = Stroke(width = 2.5f, cap = StrokeCap.Round)
                        )
                    }
                }
            }

            // 2. 3D Tilt Reticle & Motion Halo
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val r = size.minDimension / 2f - 8f

                    // Outer Target Rings
                    drawCircle(Color.White.copy(alpha = 0.12f), radius = r, center = Offset(cx, cy), style = Stroke(width = 1.5f))
                    drawCircle(Color.White.copy(alpha = 0.06f), radius = r * 0.5f, center = Offset(cx, cy), style = Stroke(width = 1f))

                    // Crosshairs
                    drawLine(Color.White.copy(alpha = 0.15f), Offset(cx - r, cy), Offset(cx + r, cy), strokeWidth = 1f)
                    drawLine(Color.White.copy(alpha = 0.15f), Offset(cx, cy - r), Offset(cx, cy + r), strokeWidth = 1f)

                    // Dynamic Pulsating Motion Halo
                    val haloRadius = (r * 0.3f + (magnitude / 2.0f) * r * 0.7f).coerceIn(r * 0.3f, r)
                    drawCircle(
                        color = motionColor.copy(alpha = 0.25f),
                        radius = haloRadius,
                        center = Offset(cx, cy)
                    )

                    // Tilt Bubble based on X, Y pitch/roll (-9.8 to +9.8 m/s^2 mapped to radius)
                    val bubbleOffsetX = (x / 9.8f).coerceIn(-1f, 1f) * (r * 0.75f)
                    val bubbleOffsetY = -(y / 9.8f).coerceIn(-1f, 1f) * (r * 0.75f)
                    val bubbleCenter = Offset(cx + bubbleOffsetX, cy + bubbleOffsetY)

                    // Draw Tilt Center Bubble
                    drawCircle(motionColor, radius = 6.dp.toPx(), center = bubbleCenter)
                    drawCircle(Color.White, radius = 2.5.dp.toPx(), center = bubbleCenter)
                }
            }
        }
    }
}
