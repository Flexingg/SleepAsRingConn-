package com.randallengineering.sleepasringconn.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.randallengineering.sleepasringconn.data.AppDatabase
import com.randallengineering.sleepasringconn.data.DeviceStatusEntity
import com.randallengineering.sleepasringconn.data.EpochEntity
import com.randallengineering.sleepasringconn.protocol.BulkRecord
import com.randallengineering.sleepasringconn.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

enum class TimeRange(val label: String, val durationMillis: Long) {
    LAST_24_HOURS("24 Hours", 24 * 60 * 60 * 1000L),
    LAST_NIGHT("Last Night", 12 * 60 * 60 * 1000L),
    LAST_7_DAYS("7 Days", 7 * 24 * 60 * 60 * 1000L)
}

enum class MetricCategory(val label: String) {
    ALL("All Metrics"),
    HEART_RATE("Heart & HRV"),
    SPO2("SpO2 & Breathing"),
    TEMPERATURE("Temperature"),
    ACTIVITY("Activity & Motion"),
    BATTERY("Battery & Voltage")
}

@Composable
fun AnalyticsScreen() {
    val context = LocalContext.current
    val database = remember { AppDatabase.getDatabase(context) }

    var selectedRange by remember { mutableStateOf(TimeRange.LAST_24_HOURS) }
    var selectedCategory by remember { mutableStateOf(MetricCategory.ALL) }

    val now = remember { System.currentTimeMillis() }
    val start = now - selectedRange.durationMillis

    val rawEpochs by database.epochDao().getEpochsSinceFlow(start).collectAsState(initial = emptyList())
    val statusLogs by database.deviceStatusDao().getStatusLogsSinceFlow(start).collectAsState(initial = emptyList())

    val epochList = remember(rawEpochs) {
        rawEpochs.mapNotNull { BulkRecord.parseRecord(it.rawBytes) }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "Biometric & Health Trends",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        }

        // Time Range Filter Chips
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TimeRange.entries.forEach { range ->
                    FilterChip(
                        selected = selectedRange == range,
                        onClick = { selectedRange = range },
                        label = { Text(range.label) },
                        leadingIcon = if (selectedRange == range) {
                            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else null
                    )
                }
            }
        }

        // Category Filter Chips
        item {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(MetricCategory.entries.toTypedArray()) { cat ->
                    FilterChip(
                        selected = selectedCategory == cat,
                        onClick = { selectedCategory = cat },
                        label = { Text(cat.label) }
                    )
                }
            }
        }

        if (epochList.isEmpty() && statusLogs.isEmpty()) {
            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(Icons.Default.ShowChart, contentDescription = null, tint = SleepPurple, modifier = Modifier.size(48.dp))
                        Text("No historical data in this time range", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "Sync your RingConn Gen 2 from the Dashboard to pull the latest 2.5-minute health epochs and live descriptors.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            // 1. Heart Rate & HRV Chart
            if (selectedCategory == MetricCategory.ALL || selectedCategory == MetricCategory.HEART_RATE) {
                item {
                    HeartRateAnalyticsCard(epochList)
                }
            }

            // 2. SpO2 & Respiration Rate Chart
            if (selectedCategory == MetricCategory.ALL || selectedCategory == MetricCategory.SPO2) {
                item {
                    Spo2AnalyticsCard(epochList)
                }
            }

            // 3. Skin Temperature Trends
            if (selectedCategory == MetricCategory.ALL || selectedCategory == MetricCategory.TEMPERATURE) {
                item {
                    TemperatureAnalyticsCard(statusLogs)
                }
            }

            // 4. Activity & Motion Intensity Chart
            if (selectedCategory == MetricCategory.ALL || selectedCategory == MetricCategory.ACTIVITY) {
                item {
                    ActivityMotionAnalyticsCard(epochList, statusLogs)
                }
            }

            // 5. Battery & Voltage Telemetry Chart
            if (selectedCategory == MetricCategory.ALL || selectedCategory == MetricCategory.BATTERY) {
                item {
                    BatteryVoltageAnalyticsCard(statusLogs)
                }
            }
        }
    }
}

@Composable
fun HeartRateAnalyticsCard(epochs: List<BulkRecord>) {
    val hrRecords = epochs.filter { it.heartRate != null }
    val hrvRecords = epochs.filter { it.hrvRmssd != null }

    val hrValues = hrRecords.mapNotNull { it.heartRate }
    val hrvValues = hrvRecords.mapNotNull { it.hrvRmssd }

    var selectedRecord by remember { mutableStateOf<BulkRecord?>(null) }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Favorite, contentDescription = null, tint = HeartRateRed)
                    Text("Heart Rate & HRV (RMSSD)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }

            // Metrics Summary Badges
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (hrValues.isNotEmpty()) {
                    MetricStatBadge("Min HR", "${hrValues.minOrNull()} BPM", HeartRateRed)
                    MetricStatBadge("Avg HR", "${hrValues.average().toInt()} BPM", HeartRateRed)
                    MetricStatBadge("Max HR", "${hrValues.maxOrNull()} BPM", HeartRateRed)
                }
            }

            if (hrvValues.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    MetricStatBadge("Min HRV", "${hrvValues.minOrNull()} ms", SleepPurple)
                    MetricStatBadge("Avg HRV", "${hrvValues.average().toInt()} ms", SleepPurple)
                    MetricStatBadge("Max HRV", "${hrvValues.maxOrNull()} ms", SleepPurple)
                }
            }

            if (hrRecords.isEmpty() && hrvRecords.isEmpty()) {
                Text("No heart rate or HRV points in this window.", style = MaterialTheme.typography.bodySmall)
            } else {
                Text("Tap the chart to inspect points (Red: HR, Purple: HRV).", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                InteractiveLineChart(
                    dataPoints = hrRecords.map { it.timestampMillis to it.heartRate!!.toFloat() },
                    secondaryPoints = hrvRecords.map { it.timestampMillis to it.hrvRmssd!!.toFloat() },
                    primaryColor = HeartRateRed,
                    secondaryColor = SleepPurple,
                    minVal = ((hrValues + hrvValues).minOrNull() ?: 40).toFloat() - 5f,
                    maxVal = ((hrValues + hrvValues).maxOrNull() ?: 120).toFloat() + 5f,
                    onPointSelected = { ts ->
                        selectedRecord = epochs.minByOrNull { kotlin.math.abs(it.timestampMillis - ts) }
                    }
                )

                selectedRecord?.let { rec ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            val timeStr = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(rec.timestampMillis))
                            Text(timeStr, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                            rec.heartRate?.let { Text("HR: $it BPM", color = HeartRateRed, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall) }
                            rec.hrvRmssd?.let { Text("HRV: ${it} ms", color = SleepPurple, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun Spo2AnalyticsCard(epochs: List<BulkRecord>) {
    val spo2Records = epochs.filter { it.spo2Percent != null }
    val respRecords = epochs.filter { it.respiratoryRate != null }

    val spo2Values = spo2Records.mapNotNull { it.spo2Percent }
    val respValues = respRecords.mapNotNull { it.respiratoryRate }

    val desaturations = spo2Values.count { it < 95 }
    val dropsBelow90 = spo2Values.count { it < 90 }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Air, contentDescription = null, tint = Spo2Blue)
                    Text("Blood Oxygen (SpO2) & Respiration", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (spo2Values.isNotEmpty()) {
                    MetricStatBadge("Avg SpO2", "${"%.1f".format(spo2Values.average())}%", Spo2Blue)
                    MetricStatBadge("Min SpO2", "${spo2Values.minOrNull()}%", if ((spo2Values.minOrNull() ?: 100) < 90) Color.Red else Spo2Blue)
                    MetricStatBadge("<95% Dips", "$desaturations", if (desaturations > 0) AwakeSleepOrange else Color.Gray)
                }
                if (respValues.isNotEmpty()) {
                    MetricStatBadge("Avg RR", "%.1f brpm".format(respValues.average()), LightSleepTeal)
                }
            }

            if (spo2Records.isEmpty()) {
                Text("No SpO2 readings in this window.", style = MaterialTheme.typography.bodySmall)
            } else {
                InteractiveLineChart(
                    dataPoints = spo2Records.map { it.timestampMillis to it.spo2Percent!!.toFloat() },
                    primaryColor = Spo2Blue,
                    minVal = 80f,
                    maxVal = 100f,
                    referenceLines = listOf(95f to Color.Green.copy(alpha = 0.4f), 90f to Color.Red.copy(alpha = 0.4f))
                )
            }
        }
    }
}

@Composable
fun TemperatureAnalyticsCard(logs: List<DeviceStatusEntity>) {
    val tempLogs = logs.filter { it.skinTemperatureC != null }
    val tempValues = tempLogs.mapNotNull { it.skinTemperatureC }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Thermostat, contentDescription = null, tint = AwakeSleepOrange)
                    Text("Skin Temperature & Circadian Rhythm", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }

            if (tempValues.isNotEmpty()) {
                val avgC = tempValues.average()
                val avgF = (avgC * 9.0 / 5.0) + 32.0
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    MetricStatBadge("Avg Temp", "%.1f °C".format(avgC), AwakeSleepOrange)
                    MetricStatBadge("Avg Temp (°F)", "%.1f °F".format(avgF), AwakeSleepOrange)
                    MetricStatBadge("Min / Max", "%.1f / %.1f".format(tempValues.minOrNull() ?: 0.0, tempValues.maxOrNull() ?: 0.0), Color.Gray)
                }

                InteractiveLineChart(
                    dataPoints = tempLogs.map { it.timestampMillis to it.skinTemperatureC!!.toFloat() },
                    primaryColor = AwakeSleepOrange,
                    minVal = (tempValues.minOrNull()?.toFloat() ?: 30f) - 0.5f,
                    maxVal = (tempValues.maxOrNull()?.toFloat() ?: 38f) + 0.5f
                )
            } else {
                Text("No skin temperature descriptor frames recorded in this time range.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun ActivityMotionAnalyticsCard(epochs: List<BulkRecord>, logs: List<DeviceStatusEntity>) {
    val motionEpochs = epochs.map { it.timestampMillis to it.motionMagnitude }
    val totalMotion = motionEpochs.sumOf { it.second }

    val stepLogs = logs.filter { it.quarterHourSteps > 0 }
    val totalSteps = stepLogs.sumOf { it.quarterHourSteps }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.DirectionsWalk, contentDescription = null, tint = LightSleepTeal)
                    Text("Activity & Motion Intensity", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MetricStatBadge("Total Steps", "$totalSteps", LightSleepTeal)
                MetricStatBadge("Motion Index", "$totalMotion", RemSleepCyan)
                val activeEpochs = motionEpochs.count { it.second > 2 }
                val activeHours = (activeEpochs * 2.5) / 60.0
                MetricStatBadge("Active Time", "%.1fh".format(activeHours), DeepSleepBlue)
            }

            if (motionEpochs.isNotEmpty()) {
                Text("2.5-Minute Epoch Motion Intensity", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                MotionBarChart(motionEpochs)
            }
        }
    }
}

@Composable
fun BatteryVoltageAnalyticsCard(logs: List<DeviceStatusEntity>) {
    val validLogs = logs.filter { it.batteryVoltageMv != null }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.BatteryChargingFull, contentDescription = null, tint = BatteryGreen)
                    Text("Battery & Voltage Telemetry", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }

            if (validLogs.isNotEmpty()) {
                val latest = validLogs.last()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    MetricStatBadge("Current Level", "${latest.batteryPercent}%", BatteryGreen)
                    MetricStatBadge("Raw Voltage", "${latest.batteryVoltageMv} mV", BatteryGreen)
                    MetricStatBadge("Status", if (latest.isOnCharger) "On Charger" else "Worn / Normal", Color.Gray)
                }

                InteractiveLineChart(
                    dataPoints = validLogs.map { it.timestampMillis to it.batteryVoltageMv!!.toFloat() },
                    primaryColor = BatteryGreen,
                    minVal = 3700f,
                    maxVal = 4400f
                )
            } else {
                Text("No battery voltage logs recorded yet.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun InteractiveLineChart(
    dataPoints: List<Pair<Long, Float>>,
    secondaryPoints: List<Pair<Long, Float>> = emptyList(),
    primaryColor: Color,
    secondaryColor: Color = Color.Cyan,
    minVal: Float,
    maxVal: Float,
    referenceLines: List<Pair<Float, Color>> = emptyList(),
    onPointSelected: ((Long) -> Unit)? = null
) {
    if (dataPoints.isEmpty()) return

    val range = (maxVal - minVal).coerceAtLeast(1f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(130.dp)
            .pointerInput(dataPoints) {
                if (onPointSelected != null) {
                    detectTapGestures { offset ->
                        val index = ((offset.x / size.width) * dataPoints.size).toInt().coerceIn(0, dataPoints.size - 1)
                        onPointSelected(dataPoints[index].first)
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height

            // Reference Lines
            referenceLines.forEach { (refVal, refColor) ->
                val y = height - (((refVal - minVal) / range) * height)
                drawLine(
                    color = refColor,
                    start = Offset(0f, y),
                    end = Offset(width, y),
                    strokeWidth = 1.dp.toPx()
                )
            }

            // Primary Line Path
            val stepX = width / dataPoints.size.coerceAtLeast(1)
            val path = Path()
            var first = true

            dataPoints.forEachIndexed { i, pt ->
                val x = i * stepX
                val y = height - (((pt.second.coerceIn(minVal, maxVal) - minVal) / range) * height)
                if (first) {
                    path.moveTo(x, y)
                    first = false
                } else {
                    path.lineTo(x, y)
                }
            }

            drawPath(
                path = path,
                color = primaryColor,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
            )

            // Secondary Points (if any)
            if (secondaryPoints.isNotEmpty()) {
                val secStepX = width / secondaryPoints.size.coerceAtLeast(1)
                val secPath = Path()
                var secFirst = true
                secondaryPoints.forEachIndexed { i, pt ->
                    val x = i * secStepX
                    val y = height - (((pt.second.coerceIn(minVal, maxVal) - minVal) / range) * height)
                    if (secFirst) {
                        secPath.moveTo(x, y)
                        secFirst = false
                    } else {
                        secPath.lineTo(x, y)
                    }
                }
                drawPath(
                    path = secPath,
                    color = secondaryColor,
                    style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }
    }
}

@Composable
fun MotionBarChart(motionList: List<Pair<Long, Int>>) {
    if (motionList.isEmpty()) return

    val maxMotion = (motionList.maxOfOrNull { it.second } ?: 10).coerceAtLeast(5)

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(90.dp)
    ) {
        val width = size.width
        val height = size.height
        val stepX = width / motionList.size.coerceAtLeast(1)

        motionList.forEachIndexed { i, item ->
            val x = i * stepX
            val barHeight = ((item.second.toFloat() / maxMotion) * height).coerceAtLeast(2f)
            val barColor = when {
                item.second > 15 -> AwakeSleepOrange
                item.second > 5 -> RemSleepCyan
                item.second > 0 -> LightSleepTeal
                else -> Color.Gray.copy(alpha = 0.2f)
            }

            drawRect(
                color = barColor,
                topLeft = Offset(x, height - barHeight),
                size = Size((stepX * 0.85f).coerceAtLeast(1f), barHeight)
            )
        }
    }
}

@Composable
fun MetricStatBadge(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color)
    }
}
