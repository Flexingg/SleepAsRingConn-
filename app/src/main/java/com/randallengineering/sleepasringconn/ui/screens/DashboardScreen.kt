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
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.randallengineering.sleepasringconn.ble.BleConnectionManager
import com.randallengineering.sleepasringconn.ble.HrBroadcastManager
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
    val isBroadcasting by HrBroadcastManager.isBroadcasting.collectAsState()
    val broadcastStatus by HrBroadcastManager.statusMessage.collectAsState()
    val connectedReceiver by HrBroadcastManager.connectedDeviceName.collectAsState()
    val broadcastBpm by HrBroadcastManager.lastBroadcastBpm.collectAsState()

    var showDeviceSheet by remember { mutableStateOf(false) }

    val context = LocalContext.current
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
        // 1. Header Title & Subtitle
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "RingConn Gen 2",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Live Telemetry & Smart Actigraphy",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
                        Icon(Icons.AutoMirrored.Filled.BluetoothSearching, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Connect")
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
        }

        // 2. Hero Ring Status & Battery Dial Card
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
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Circular Battery / Connection Dial
                        val primaryColor = MaterialTheme.colorScheme.primary
                        Box(
                            modifier = Modifier.size(96.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val strokeWidth = 9.dp.toPx()
                                val diameter = size.minDimension - strokeWidth
                                val topLeft = Offset(strokeWidth / 2, strokeWidth / 2)
                                val arcSize = Size(diameter, diameter)

                                drawArc(
                                    color = Color.White.copy(alpha = 0.1f),
                                    startAngle = 135f,
                                    sweepAngle = 270f,
                                    useCenter = false,
                                    topLeft = topLeft,
                                    size = arcSize,
                                    style = Stroke(strokeWidth, cap = StrokeCap.Round)
                                )

                                val batteryPct = deviceStatus?.batteryPercent ?: if (isConnected) 100 else 0
                                val sweep = 270f * (batteryPct / 100f)
                                val dialColor = if (batteryPct > 20) StepsGreen else HeartRateRed

                                drawArc(
                                    brush = Brush.sweepGradient(
                                        listOf(dialColor, Color(0xFF00E676), primaryColor)
                                    ),
                                    startAngle = 135f,
                                    sweepAngle = sweep,
                                    useCenter = false,
                                    topLeft = topLeft,
                                    size = arcSize,
                                    style = Stroke(strokeWidth, cap = StrokeCap.Round)
                                )
                            }

                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                if (isConnected && deviceStatus != null) {
                                    Text(
                                        text = "${deviceStatus!!.batteryPercent}%",
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = if (deviceStatus!!.isOnCharger) "Charging" else "Battery",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    Icon(
                                        if (isConnected) Icons.Default.BluetoothConnected else Icons.Default.BluetoothDisabled,
                                        contentDescription = null,
                                        tint = if (isConnected) StepsGreen else HeartRateRed,
                                        modifier = Modifier.size(28.dp)
                                    )
                                    Text(
                                        text = if (isConnected) "Active" else "Offline",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        // Status Info Column
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(if (isConnected) StepsGreen else HeartRateRed)
                                )
                                Text(
                                    text = if (isConnected) "Smart Ring Connected" else "Not Connected",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surface
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                    Text(
                                        text = connectionState,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    deviceStatus?.batteryVoltageMv?.let { mv ->
                                        Text(
                                            text = "Bus Voltage: ${mv}mV",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }

                            deviceStatus?.caseBattery?.let { case ->
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = TempAmber.copy(alpha = 0.15f)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(Icons.Default.BatteryChargingFull, contentDescription = null, tint = TempAmber, modifier = Modifier.size(14.dp))
                                        Text(
                                            text = "Charging Case: ${case.percent}%",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = TempAmber
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. Peloton & Fitness Workout Bluetooth HR Broadcast Card
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = if (isBroadcasting) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(if (isBroadcasting) HeartRateRed.copy(alpha = 0.2f) else MaterialTheme.colorScheme.outlineVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.DirectionsBike,
                                    contentDescription = null,
                                    tint = if (isBroadcasting) HeartRateRed else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = "Peloton HR Broadcast",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    if (isBroadcasting && broadcastBpm != null) {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = HeartRateRed.copy(alpha = 0.2f)
                                        ) {
                                            Text(
                                                text = "$broadcastBpm BPM",
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = HeartRateRed
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = if (isBroadcasting) (connectedReceiver?.let { "Connected to $it" } ?: broadcastStatus) else "Standard BLE HRS 0x180D",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isBroadcasting) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Switch(
                            checked = isBroadcasting,
                            onCheckedChange = { enable ->
                                if (enable) {
                                    com.randallengineering.sleepasringconn.service.HrBroadcastService.start(context)
                                } else {
                                    com.randallengineering.sleepasringconn.service.HrBroadcastService.stop(context)
                                }
                            }
                        )
                    }
                }
            }
        }

        // 4. Live Vitals Grid
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Speed, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                    Text(
                        text = "Live Telemetry",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                if (isLiveMonitoring) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = StepsGreen.copy(alpha = 0.2f)
                    ) {
                        Text(
                            text = "Streaming Active",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = StepsGreen
                        )
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                PolishedMetricCard(
                    modifier = Modifier.weight(1f),
                    title = "Heart Rate",
                    value = liveHr?.toString() ?: "--",
                    unit = "BPM",
                    icon = Icons.Default.Favorite,
                    color = HeartRateRed,
                    isLive = isLiveMonitoring
                )

                PolishedMetricCard(
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
                PolishedMetricCard(
                    modifier = Modifier.weight(1f),
                    title = "Blood Oxygen",
                    value = liveSpo2?.toString() ?: "--",
                    unit = "%",
                    icon = Icons.Default.Air,
                    color = Spo2Blue,
                    isLive = isLiveMonitoring
                )

                PolishedMetricCard(
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
                PolishedMetricCard(
                    modifier = Modifier.weight(1f),
                    title = "Steps (15m)",
                    value = deviceStatus?.quarterHourSteps?.toString() ?: "--",
                    unit = "steps",
                    icon = Icons.AutoMirrored.Filled.DirectionsWalk,
                    color = StepsGreen,
                    isLive = isConnected
                )
            }
        }

        // 5. Accelerometer & Actigraphy Card (Sleep as Android Motion)
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.ShowChart, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Text(
                    text = "Motion & Actigraphy",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
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
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Speed, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            }
                            Column {
                                Text(
                                    text = "3-Axis Ring Accelerometer",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "X: %.2f · Y: %.2f · Z: %.2f m/s²".format(rawAccel.first, rawAccel.second, rawAccel.third),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        val (stageText, stageColor) = when {
                            last10sMaxAccel < 0.05f -> "Still (Deep/REM)" to StepsGreen
                            last10sMaxAccel in 0.05f..0.30f -> "Light Motion" to TempAmber
                            else -> "Active (Awake)" to HeartRateRed
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

                    LiveMotionVisualizer(
                        magnitude = currentMagnitude,
                        x = rawAccel.first,
                        y = rawAccel.second,
                        z = rawAccel.third,
                        peak10s = last10sMaxAccel
                    )
                }
            }
        }

        // 6. Controls & Actions
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Text(
                    text = "Controls & Actions",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
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
                            Text("Light LED")
                        }
                    }

                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = onNavigateToDiagnostics,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Console")
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
fun PolishedMetricCard(
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
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(color.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
                }
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
            .clip(RoundedCornerShape(14.dp))
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
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.35f))
            ) {
                Canvas(modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 6.dp)) {
                    val w = size.width
                    val h = size.height

                    val gridColor = Color.White.copy(alpha = 0.08f)
                    drawLine(gridColor, Offset(0f, h * 0.25f), Offset(w, h * 0.25f), strokeWidth = 1f)
                    drawLine(gridColor, Offset(0f, h * 0.50f), Offset(w, h * 0.50f), strokeWidth = 1f)
                    drawLine(gridColor, Offset(0f, h * 0.75f), Offset(w, h * 0.75f), strokeWidth = 1f)

                    if (history.size >= 2) {
                        val stepX = w / 59f
                        val path = Path()
                        val fillPath = Path()

                        val firstY = h - (history[0] / 2.0f).coerceIn(0f, 1f) * h
                        path.moveTo(0f, firstY)
                        fillPath.moveTo(0f, h)
                        fillPath.lineTo(0f, firstY)

                        for (i in 1 until history.size) {
                            val currX = i * stepX
                            val currY = h - (history[i] / 2.0f).coerceIn(0f, 1f) * h
                            path.lineTo(currX, currY)
                            fillPath.lineTo(currX, currY)
                        }

                        val lastX = (history.size - 1) * stepX
                        fillPath.lineTo(lastX, h)
                        fillPath.close()

                        drawPath(
                            path = fillPath,
                            brush = Brush.verticalGradient(
                                listOf(motionColor.copy(alpha = 0.35f), Color.Transparent)
                            )
                        )

                        drawPath(
                            path = path,
                            color = motionColor,
                            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }
                }
            }

            // 3D Tilt Reticle
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                    val center = Offset(size.width / 2, size.height / 2)
                    val radius = size.minDimension / 2

                    drawCircle(Color.White.copy(alpha = 0.1f), radius = radius, center = center, style = Stroke(1.dp.toPx()))
                    drawCircle(Color.White.copy(alpha = 0.06f), radius = radius * 0.5f, center = center, style = Stroke(1.dp.toPx()))

                    val normX = (x / 9.81f).coerceIn(-1f, 1f)
                    val normY = (y / 9.81f).coerceIn(-1f, 1f)
                    val bubbleOffset = Offset(
                        center.x + normX * (radius * 0.75f),
                        center.y - normY * (radius * 0.75f)
                    )

                    drawCircle(motionColor.copy(alpha = 0.3f), radius = 10.dp.toPx(), center = bubbleOffset)
                    drawCircle(motionColor, radius = 5.dp.toPx(), center = bubbleOffset)
                }
            }
        }
    }
}
