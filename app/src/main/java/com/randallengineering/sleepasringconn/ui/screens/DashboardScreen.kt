package com.randallengineering.sleepasringconn.ui.screens

import android.bluetooth.BluetoothDevice
import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import kotlinx.coroutines.delay

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
    var selectedModalMetric by remember { mutableStateOf<TelemetryMetricType?>(null) }

    val context = LocalContext.current
    val motionSensorManager = remember { com.randallengineering.sleepasringconn.sensor.MotionSensorManager.getInstance(context) }
    val rawAccel by motionSensorManager.rawAcceleration.collectAsState()
    val currentMagnitude by motionSensorManager.currentMagnitude.collectAsState()
    val last10sMaxAccel by motionSensorManager.last10sMaxAcceleration.collectAsState()
    val lastDataSource by motionSensorManager.lastDataSource.collectAsState()

    DisposableEffect(Unit) {
        motionSensorManager.start()
        onDispose {
            if (!com.randallengineering.sleepasringconn.sleepasandroid.SleepAsAndroidBridge.isTrackingActive) {
                motionSensorManager.stop()
            }
        }
    }

    // Auto-stream live telemetry and sync history whenever Dashboard is active and ring is connected
    LaunchedEffect(isConnected) {
        if (isConnected) {
            BleConnectionManager.startLiveMonitoring(hrMode = true)
            BleConnectionManager.syncHistory()
        }
    }

    // 60-second rolling history buffers for all telemetry cards
    val hrHistory = remember { mutableStateListOf<TelemetrySample>() }
    val hrvHistory = remember { mutableStateListOf<TelemetrySample>() }
    val spo2History = remember { mutableStateListOf<TelemetrySample>() }
    val tempHistory = remember { mutableStateListOf<TelemetrySample>() }
    val stepsHistory = remember { mutableStateListOf<TelemetrySample>() }
    val motionHistory = remember { mutableStateListOf<TelemetrySample>() }

    // Periodic 1-second ticker to maintain a clean rolling 60s buffer
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000L)
            val now = System.currentTimeMillis()
            val cutoff = now - 60_000L

            liveHr?.let { hrHistory.add(TelemetrySample(now, it.toFloat())) }
            liveHrv?.let { hrvHistory.add(TelemetrySample(now, it.toFloat())) }
            liveSpo2?.let { spo2History.add(TelemetrySample(now, it.toFloat())) }
            deviceStatus?.skinTemperature?.celsius?.let { tempHistory.add(TelemetrySample(now, it.toFloat())) }
            deviceStatus?.quarterHourSteps?.let { stepsHistory.add(TelemetrySample(now, it.toFloat())) }
            motionHistory.add(TelemetrySample(now, currentMagnitude))

            hrHistory.removeAll { it.timestamp < cutoff }
            hrvHistory.removeAll { it.timestamp < cutoff }
            spo2History.removeAll { it.timestamp < cutoff }
            tempHistory.removeAll { it.timestamp < cutoff }
            stepsHistory.removeAll { it.timestamp < cutoff }
            motionHistory.removeAll { it.timestamp < cutoff }
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

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Tap cards for 60s graph",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (isLiveMonitoring) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = StepsGreen.copy(alpha = 0.2f)
                        ) {
                            Text(
                                text = "Streaming",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = StepsGreen
                            )
                        }
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
                    isLive = isLiveMonitoring,
                    onClick = { selectedModalMetric = TelemetryMetricType.HEART_RATE }
                )

                PolishedMetricCard(
                    modifier = Modifier.weight(1f),
                    title = "HRV (RMSSD)",
                    value = liveHrv?.toString() ?: "--",
                    unit = "ms",
                    icon = Icons.Default.MonitorHeart,
                    color = Color(0xFF9C27B0),
                    isLive = isConnected,
                    onClick = { selectedModalMetric = TelemetryMetricType.HRV }
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
                    isLive = isLiveMonitoring,
                    onClick = { selectedModalMetric = TelemetryMetricType.SPO2 }
                )

                PolishedMetricCard(
                    modifier = Modifier.weight(1f),
                    title = "Skin Temp",
                    value = deviceStatus?.skinTemperature?.let { "%.1f".format(it.celsius) } ?: "--",
                    unit = "°C",
                    icon = Icons.Default.DeviceThermostat,
                    color = TempAmber,
                    isLive = isConnected,
                    onClick = { selectedModalMetric = TelemetryMetricType.SKIN_TEMP }
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
                    isLive = isConnected,
                    onClick = { selectedModalMetric = TelemetryMetricType.STEPS }
                )

                PolishedMetricCard(
                    modifier = Modifier.weight(1f),
                    title = "Ring Motion",
                    value = "%.2f".format(currentMagnitude),
                    unit = "m/s²",
                    icon = Icons.Default.Speed,
                    color = Color(0xFF29B6F6),
                    isLive = isConnected,
                    onClick = { selectedModalMetric = TelemetryMetricType.MOTION }
                )
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

    if (selectedModalMetric != null) {
        val metric = selectedModalMetric!!
        ModalBottomSheet(
            onDismissRequest = { selectedModalMetric = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            TelemetryModalContent(
                metric = metric,
                samples = when (metric) {
                    TelemetryMetricType.HEART_RATE -> hrHistory
                    TelemetryMetricType.HRV -> hrvHistory
                    TelemetryMetricType.SPO2 -> spo2History
                    TelemetryMetricType.SKIN_TEMP -> tempHistory
                    TelemetryMetricType.STEPS -> stepsHistory
                    TelemetryMetricType.MOTION -> motionHistory
                },
                currentValue = when (metric) {
                    TelemetryMetricType.HEART_RATE -> liveHr?.toString() ?: "--"
                    TelemetryMetricType.HRV -> liveHrv?.toString() ?: "--"
                    TelemetryMetricType.SPO2 -> liveSpo2?.toString() ?: "--"
                    TelemetryMetricType.SKIN_TEMP -> deviceStatus?.skinTemperature?.let { "%.1f".format(it.celsius) } ?: "--"
                    TelemetryMetricType.STEPS -> deviceStatus?.quarterHourSteps?.toString() ?: "--"
                    TelemetryMetricType.MOTION -> "%.2f".format(currentMagnitude)
                },
                rawAccel = rawAccel,
                peak10s = last10sMaxAccel,
                onClose = { selectedModalMetric = null }
            )
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
    isLive: Boolean,
    onClick: (() -> Unit)? = null
) {
    ElevatedCard(
        modifier = modifier.then(
            if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
        ),
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
                    if (onClick != null) {
                        Icon(
                            Icons.Default.ShowChart,
                            contentDescription = "Tap to view graph",
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
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
                    text = "Live Ring Motion & Actigraphy Seismograph",
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

enum class TelemetryMetricType(
    val title: String,
    val unit: String,
    val icon: ImageVector,
    val color: Color
) {
    HEART_RATE("Heart Rate", "BPM", Icons.Default.Favorite, HeartRateRed),
    HRV("HRV (RMSSD)", "ms", Icons.Default.MonitorHeart, Color(0xFF9C27B0)),
    SPO2("Blood Oxygen", "%", Icons.Default.Air, Spo2Blue),
    SKIN_TEMP("Skin Temp", "°C", Icons.Default.DeviceThermostat, TempAmber),
    STEPS("Steps (15m)", "steps", Icons.AutoMirrored.Filled.DirectionsWalk, StepsGreen),
    MOTION("Ring Motion", "m/s²", Icons.Default.Speed, Color(0xFF29B6F6))
}

data class TelemetrySample(val timestamp: Long, val value: Float)

private fun formatMetricVal(metric: TelemetryMetricType, value: Float): String {
    return when (metric) {
        TelemetryMetricType.HEART_RATE, TelemetryMetricType.HRV, TelemetryMetricType.STEPS -> value.toInt().toString()
        TelemetryMetricType.SPO2 -> "%.0f".format(value)
        TelemetryMetricType.SKIN_TEMP -> "%.1f".format(value)
        TelemetryMetricType.MOTION -> "%.2f".format(value)
    }
}

@Composable
private fun StatSummaryChip(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    unit: String,
    color: Color
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = color
                )
                if (value != "--") {
                    Text(
                        text = unit,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun TelemetryLineGraph(
    samples: List<TelemetrySample>,
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        val values = samples.map { it.value }
        var minV = values.minOrNull() ?: 0f
        var maxV = values.maxOrNull() ?: 1f
        if (maxV - minV < 0.001f) {
            maxV += 1f
            minV -= 1f
        }
        val range = maxV - minV

        val gridColor = Color.White.copy(alpha = 0.08f)
        drawLine(gridColor, Offset(0f, h * 0.25f), Offset(w, h * 0.25f), strokeWidth = 1f)
        drawLine(gridColor, Offset(0f, h * 0.50f), Offset(w, h * 0.50f), strokeWidth = 1f)
        drawLine(gridColor, Offset(0f, h * 0.75f), Offset(w, h * 0.75f), strokeWidth = 1f)

        val stepX = if (samples.size > 1) w / (samples.size - 1) else w
        val path = Path()
        val fillPath = Path()

        val firstNormY = 1f - ((samples[0].value - minV) / range).coerceIn(0f, 1f)
        val firstY = firstNormY * (h - 20.dp.toPx()) + 10.dp.toPx()
        path.moveTo(0f, firstY)
        fillPath.moveTo(0f, h)
        fillPath.lineTo(0f, firstY)

        for (i in 1 until samples.size) {
            val currX = i * stepX
            val normY = 1f - ((samples[i].value - minV) / range).coerceIn(0f, 1f)
            val currY = normY * (h - 20.dp.toPx()) + 10.dp.toPx()
            path.lineTo(currX, currY)
            fillPath.lineTo(currX, currY)
        }

        val lastX = (samples.size - 1) * stepX
        fillPath.lineTo(lastX, h)
        fillPath.close()

        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                listOf(color.copy(alpha = 0.35f), Color.Transparent)
            )
        )

        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
        )

        // Latest value indicator
        val latestNormY = 1f - ((samples.last().value - minV) / range).coerceIn(0f, 1f)
        val latestY = latestNormY * (h - 20.dp.toPx()) + 10.dp.toPx()
        val latestOffset = Offset(lastX, latestY)
        drawCircle(color.copy(alpha = 0.3f), radius = 8.dp.toPx(), center = latestOffset)
        drawCircle(color, radius = 4.dp.toPx(), center = latestOffset)
    }
}

@Composable
fun TelemetryModalContent(
    metric: TelemetryMetricType,
    samples: List<TelemetrySample>,
    currentValue: String,
    rawAccel: Triple<Float, Float, Float>,
    peak10s: Float,
    onClose: () -> Unit
) {
    val values = samples.map { it.value }
    val minVal = values.minOrNull()
    val maxVal = values.maxOrNull()
    val avgVal = if (values.isNotEmpty()) values.average().toFloat() else null

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Top Bar
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
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(metric.color.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(metric.icon, contentDescription = null, tint = metric.color, modifier = Modifier.size(24.dp))
                }
                Column {
                    Text(
                        text = metric.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(StepsGreen)
                        )
                        Text(
                            text = "Live Stream · Past 60 Seconds",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close")
            }
        }

        // Live Readout & Staging
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = currentValue,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = metric.unit,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }

            if (metric == TelemetryMetricType.MOTION) {
                val (stageText, stageColor) = when {
                    peak10s < 0.05f -> "Still (Deep/REM)" to StepsGreen
                    peak10s in 0.05f..0.30f -> "Light Motion" to TempAmber
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
        }

        // 60-second summary stat chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatSummaryChip(
                modifier = Modifier.weight(1f),
                label = "Current",
                value = currentValue,
                unit = metric.unit,
                color = metric.color
            )
            StatSummaryChip(
                modifier = Modifier.weight(1f),
                label = "60s Min",
                value = minVal?.let { formatMetricVal(metric, it) } ?: "--",
                unit = metric.unit,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            StatSummaryChip(
                modifier = Modifier.weight(1f),
                label = "60s Avg",
                value = avgVal?.let { formatMetricVal(metric, it) } ?: "--",
                unit = metric.unit,
                color = MaterialTheme.colorScheme.primary
            )
            StatSummaryChip(
                modifier = Modifier.weight(1f),
                label = "60s Max",
                value = maxVal?.let { formatMetricVal(metric, it) } ?: "--",
                unit = metric.unit,
                color = HeartRateRed
            )
        }

        // Live Line Chart Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp)
            ) {
                if (samples.size < 2) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "Accumulating live samples (${samples.size}/60s)...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    TelemetryLineGraph(
                        samples = samples,
                        color = metric.color,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        // Seismograph & 3D tilt reticle for Ring Motion
        if (metric == TelemetryMetricType.MOTION) {
            val magnitude = currentValue.toFloatOrNull() ?: 0f
            LiveMotionVisualizer(
                magnitude = magnitude,
                x = rawAccel.first,
                y = rawAccel.second,
                z = rawAccel.third,
                peak10s = peak10s
            )
        }

        // Explanatory note
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = when (metric) {
                        TelemetryMetricType.HEART_RATE -> "Streamed in real-time from the ring's PPG sensor and broadcast to Sleep as Android."
                        TelemetryMetricType.HRV -> "Calculated RMSSD heart rate variability reflecting autonomic nervous system balance."
                        TelemetryMetricType.SPO2 -> "Continuous blood oxygen saturation monitored via red and infrared photoplethysmography."
                        TelemetryMetricType.SKIN_TEMP -> "High-precision ring thermistor reading finger temperature across sleep periods."
                        TelemetryMetricType.STEPS -> "Cumulative steps recorded by the ring's internal step-counter in 15-minute intervals."
                        TelemetryMetricType.MOTION -> "Ring 3-axis accelerometer actigraphy with zero reliance on phone sensors."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
