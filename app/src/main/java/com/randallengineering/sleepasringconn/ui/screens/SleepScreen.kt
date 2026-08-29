package com.randallengineering.sleepasringconn.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
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
import com.randallengineering.sleepasringconn.analytics.SleepSession
import com.randallengineering.sleepasringconn.analytics.SleepStage
import com.randallengineering.sleepasringconn.analytics.SleepStagingEngine
import com.randallengineering.sleepasringconn.analytics.StagedEpoch
import com.randallengineering.sleepasringconn.data.AppDatabase
import com.randallengineering.sleepasringconn.protocol.BulkRecord
import com.randallengineering.sleepasringconn.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun SleepScreen() {
    val context = LocalContext.current
    val database = remember { AppDatabase.getDatabase(context) }
    var selectedEpoch by remember { mutableStateOf<StagedEpoch?>(null) }
    var selectedSessionIndex by remember { mutableIntStateOf(0) }

    val now = remember { System.currentTimeMillis() }
    val past30d = remember { now - 30 * 24 * 60 * 60 * 1000L }
    val epochEntities by database.epochDao().getEpochsSinceFlow(past30d).collectAsState(initial = emptyList())

    val allSessions = remember(epochEntities) {
        if (epochEntities.isEmpty()) emptyList()
        else {
            val bulkRecords = epochEntities.mapNotNull { BulkRecord.parseRecord(it.rawBytes) }
            SleepStagingEngine.extractAllSleepSessions(bulkRecords)
        }
    }

    val sleepSession = allSessions.getOrNull(selectedSessionIndex) ?: allSessions.firstOrNull()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "Sleep & Recovery Analysis",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        }

        // Night / Date Selector Chips
        if (allSessions.isNotEmpty()) {
            item {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(allSessions) { idx, sess ->
                        val isSelected = (idx == selectedSessionIndex)
                        val dateStr = if (idx == 0) {
                            "Last Night"
                        } else {
                            SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date(sess.startTimeMillis))
                        }
                        val durHours = sess.sleepDurationMinutes / 60
                        val durMins = sess.sleepDurationMinutes % 60

                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                selectedSessionIndex = idx
                                selectedEpoch = null
                            },
                            label = {
                                Column(modifier = Modifier.padding(vertical = 2.dp)) {
                                    Text(dateStr, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                                    Text(
                                        "${durHours}h ${durMins}m • Score ${sess.sleepScore}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isSelected) SleepPurple else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            leadingIcon = if (isSelected) {
                                { Icon(Icons.Default.Bedtime, contentDescription = null, modifier = Modifier.size(16.dp), tint = SleepPurple) }
                            } else null
                        )
                    }
                }
            }
        }

        if (sleepSession == null) {
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
                        Icon(
                            Icons.Default.Bedtime,
                            contentDescription = null,
                            tint = SleepPurple,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = "No sleep session recorded yet",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Wear your RingConn Gen 2 overnight. The app will sync your sleep stages, resting HR dip, HRV, and SpO2 trends locally.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            val session = sleepSession

            // 1. Sleep Hero Summary Card
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
                            Column {
                                Text("Sleep Score", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${session.sleepScore}", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold, color = SleepPurple)
                            }

                            Column(horizontalAlignment = Alignment.End) {
                                Text("Actual Sleep", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                val sleepHours = session.sleepDurationMinutes / 60
                                val sleepMins = session.sleepDurationMinutes % 60
                                Text("${sleepHours}h ${sleepMins}m", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                val bedHours = session.totalInBedMinutes / 60
                                val bedMins = session.totalInBedMinutes % 60
                                val efficiency = if (session.totalInBedMinutes > 0) ((session.sleepDurationMinutes.toFloat() / session.totalInBedMinutes) * 100).toInt() else 0
                                Text(
                                    "In Bed: ${bedHours}h ${bedMins}m • Eff: $efficiency%",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Sleep Stage Distribution Bar
                        StageDistributionBar(session)
                    }
                }
            }

            // 2. Stage Breakdown Badges
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    StageBadge("Deep", "${session.deepMinutes}m", DeepSleepBlue)
                    StageBadge("REM", "${session.remMinutes}m", RemSleepCyan)
                    StageBadge("Light", "${session.lightMinutes}m", LightSleepTeal)
                    StageBadge("Awake", "${session.awakeMinutes}m", AwakeSleepOrange)
                }
            }

            // 3. Interactive Hypnogram Timeline Chart
            item {
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
                            Text(
                                text = "Hypnogram (Sleep Stages)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
                            Text(
                                text = "${timeFmt.format(Date(session.startTimeMillis))} – ${timeFmt.format(Date(session.endTimeMillis))}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Text(
                            text = "Tap on the timeline to inspect individual 2.5-min sleep intervals.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        HypnogramCanvasChart(
                            epochs = session.epochs,
                            selectedEpoch = selectedEpoch,
                            onEpochSelected = { selectedEpoch = it }
                        )

                        // Epoch Inspector Tooltip
                        selectedEpoch?.let { epoch ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    val timeStr = SimpleDateFormat("h:mm:ss a", Locale.getDefault()).format(Date(epoch.timestampMillis))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(timeStr, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            text = epoch.stage.name,
                                            fontWeight = FontWeight.Bold,
                                            color = when (epoch.stage) {
                                                SleepStage.AWAKE -> AwakeSleepOrange
                                                SleepStage.REM -> RemSleepCyan
                                                SleepStage.LIGHT -> LightSleepTeal
                                                SleepStage.DEEP -> DeepSleepBlue
                                            }
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("HR: ${epoch.heartRate?.let { "$it BPM" } ?: "--"}", style = MaterialTheme.typography.bodySmall)
                                        Text("HRV: ${epoch.hrvRmssd?.let { "${it}ms" } ?: "--"}", style = MaterialTheme.typography.bodySmall)
                                        Text("SpO2: ${epoch.spo2?.let { "$it%" } ?: "--"}", style = MaterialTheme.typography.bodySmall)
                                        Text("RR: ${epoch.respiratoryRate?.let { "%.1f".format(it) } ?: "--"}", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 4. Overnight Heart Rate & HRV Curve
            item {
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
                            Text(
                                text = "Heart Rate & HRV Trends",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = HeartRateRed
                            )
                            session.averageHeartRate?.let { avg ->
                                Text("Avg: $avg BPM", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                            }
                        }

                        HeartRateVitalsChart(epochs = session.epochs)
                    }
                }
            }

            // 5. Overnight SpO2 & Respiration Rate Chart
            item {
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
                            Text(
                                text = "Blood Oxygen (SpO2) Stability",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Spo2Blue
                            )
                            session.averageSpo2?.let { avg ->
                                Text("Avg: $avg%", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                            }
                        }

                        Spo2VitalsChart(epochs = session.epochs)
                    }
                }
            }

            // 6. Detailed Stage Statistics Cards
            item {
                Text(
                    text = "Stage Breakdown & Quality",
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
                    val deepPct = if (session.totalInBedMinutes > 0) (session.deepMinutes * 100) / session.totalInBedMinutes else 0
                    StageCard(
                        modifier = Modifier.weight(1f),
                        title = "Deep Sleep",
                        duration = "${session.deepMinutes}m",
                        percentage = "$deepPct%",
                        target = "Goal: 15-25%",
                        color = DeepSleepBlue
                    )

                    val remPct = if (session.totalInBedMinutes > 0) (session.remMinutes * 100) / session.totalInBedMinutes else 0
                    StageCard(
                        modifier = Modifier.weight(1f),
                        title = "REM Sleep",
                        duration = "${session.remMinutes}m",
                        percentage = "$remPct%",
                        target = "Goal: 20-25%",
                        color = RemSleepCyan
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val lightPct = if (session.totalInBedMinutes > 0) (session.lightMinutes * 100) / session.totalInBedMinutes else 0
                    StageCard(
                        modifier = Modifier.weight(1f),
                        title = "Light Sleep",
                        duration = "${session.lightMinutes}m",
                        percentage = "$lightPct%",
                        target = "Goal: 45-55%",
                        color = LightSleepTeal
                    )

                    StageCard(
                        modifier = Modifier.weight(1f),
                        title = "Awake / Restless",
                        duration = "${session.awakeMinutes}m",
                        percentage = if (session.totalInBedMinutes > 0) "${(session.awakeMinutes * 100) / session.totalInBedMinutes}%" else "0%",
                        target = "Ideal: < 10%",
                        color = AwakeSleepOrange
                    )
                }
            }
        }
    }
}

@Composable
fun HypnogramCanvasChart(
    epochs: List<StagedEpoch>,
    selectedEpoch: StagedEpoch?,
    onEpochSelected: (StagedEpoch) -> Unit
) {
    if (epochs.isEmpty()) return

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
            .pointerInput(epochs) {
                detectTapGestures { offset ->
                    val index = ((offset.x / size.width) * epochs.size).toInt().coerceIn(0, epochs.size - 1)
                    onEpochSelected(epochs[index])
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val stepX = width / epochs.size

            // 4 Stage Levels:
            // Awake = Level 0 (Top: y = 0.12 * height)
            // REM   = Level 1 (y = 0.40 * height)
            // Light = Level 2 (y = 0.68 * height)
            // Deep  = Level 3 (y = 0.92 * height)
            val stageY = mapOf(
                SleepStage.AWAKE to height * 0.12f,
                SleepStage.REM to height * 0.40f,
                SleepStage.LIGHT to height * 0.68f,
                SleepStage.DEEP to height * 0.92f
            )

            // Draw Stage Reference Grid Lines
            listOf(0.12f, 0.40f, 0.68f, 0.92f).forEach { yRatio ->
                drawLine(
                    color = Color.Gray.copy(alpha = 0.2f),
                    start = Offset(0f, height * yRatio),
                    end = Offset(width, height * yRatio),
                    strokeWidth = 1.dp.toPx()
                )
            }

            // Draw Stepped Stage Blocks & Continuous Path
            val path = Path()
            var prevX = 0f
            var prevY = stageY[epochs.first().stage] ?: (height * 0.68f)
            path.moveTo(prevX, prevY)

            epochs.forEachIndexed { i, epoch ->
                val x = i * stepX
                val nextX = (i + 1) * stepX
                val y = stageY[epoch.stage] ?: (height * 0.68f)

                // Fill individual stage bar
                val stageColor = when (epoch.stage) {
                    SleepStage.AWAKE -> AwakeSleepOrange
                    SleepStage.REM -> RemSleepCyan
                    SleepStage.LIGHT -> LightSleepTeal
                    SleepStage.DEEP -> DeepSleepBlue
                }

                drawRect(
                    color = stageColor.copy(alpha = 0.55f),
                    topLeft = Offset(x, y - 6.dp.toPx()),
                    size = Size(nextX - x + 0.5f, height - y + 6.dp.toPx())
                )

                // Stepped path
                path.lineTo(x, y)
                path.lineTo(nextX, y)
                prevX = nextX
                prevY = y
            }

            // Draw step stroke
            drawPath(
                path = path,
                color = SleepPurple,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
            )

            // Highlight selected epoch vertical indicator
            selectedEpoch?.let { selected ->
                val selectedIndex = epochs.indexOf(selected)
                if (selectedIndex >= 0) {
                    val selX = selectedIndex * stepX + (stepX / 2)
                    drawLine(
                        color = Color.White,
                        start = Offset(selX, 0f),
                        end = Offset(selX, height),
                        strokeWidth = 2.dp.toPx()
                    )
                    drawCircle(
                        color = Color.White,
                        radius = 4.dp.toPx(),
                        center = Offset(selX, stageY[selected.stage] ?: (height * 0.68f))
                    )
                }
            }
        }
    }
}

@Composable
fun HeartRateVitalsChart(epochs: List<StagedEpoch>) {
    val hrList = epochs.mapNotNull { it.heartRate }
    if (hrList.isEmpty()) {
        Text("No heart rate telemetry during sleep.", style = MaterialTheme.typography.bodySmall)
        return
    }

    val minHr = (hrList.minOrNull() ?: 50) - 5
    val maxHr = (hrList.maxOrNull() ?: 100) + 5
    val range = (maxHr - minHr).coerceAtLeast(1)

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(110.dp)
    ) {
        val width = size.width
        val height = size.height

        // Draw baseline grid lines
        drawLine(
            color = Color.Gray.copy(alpha = 0.2f),
            start = Offset(0f, height * 0.5f),
            end = Offset(width, height * 0.5f),
            strokeWidth = 1.dp.toPx()
        )

        val path = Path()
        var firstPoint = true
        var minPoint: Offset? = null
        var minVal = Int.MAX_VALUE

        val stepX = width / epochs.size

        epochs.forEachIndexed { i, epoch ->
            val hr = epoch.heartRate
            if (hr != null) {
                val x = i * stepX
                val y = height - (((hr - minHr).toFloat() / range) * height)
                if (firstPoint) {
                    path.moveTo(x, y)
                    firstPoint = false
                } else {
                    path.lineTo(x, y)
                }

                if (hr < minVal) {
                    minVal = hr
                    minPoint = Offset(x, y)
                }
            }
        }

        drawPath(
            path = path,
            color = HeartRateRed,
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
        )

        // Draw Dip marker for lowest resting HR
        minPoint?.let { pt ->
            drawCircle(
                color = HeartRateRed,
                radius = 5.dp.toPx(),
                center = pt
            )
            drawCircle(
                color = Color.White,
                radius = 2.5.dp.toPx(),
                center = pt
            )
        }
    }
}

@Composable
fun Spo2VitalsChart(epochs: List<StagedEpoch>) {
    val spo2List = epochs.mapNotNull { it.spo2 }
    if (spo2List.isEmpty()) {
        Text("No SpO2 samples recorded.", style = MaterialTheme.typography.bodySmall)
        return
    }

    val minSpo2 = 85
    val maxSpo2 = 100
    val range = maxSpo2 - minSpo2

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp)
    ) {
        val width = size.width
        val height = size.height
        val stepX = width / epochs.size

        // 90% and 95% Reference Lines
        val y95 = height - (((95 - minSpo2).toFloat() / range) * height)
        val y90 = height - (((90 - minSpo2).toFloat() / range) * height)

        drawLine(
            color = Color.Green.copy(alpha = 0.3f),
            start = Offset(0f, y95),
            end = Offset(width, y95),
            strokeWidth = 1.dp.toPx()
        )
        drawLine(
            color = Color.Red.copy(alpha = 0.3f),
            start = Offset(0f, y90),
            end = Offset(width, y90),
            strokeWidth = 1.dp.toPx()
        )

        val path = Path()
        var firstPoint = true

        epochs.forEachIndexed { i, epoch ->
            val spo2 = epoch.spo2
            if (spo2 != null) {
                val x = i * stepX
                val y = height - (((spo2.coerceIn(minSpo2, maxSpo2) - minSpo2).toFloat() / range) * height)
                if (firstPoint) {
                    path.moveTo(x, y)
                    firstPoint = false
                } else {
                    path.lineTo(x, y)
                }
            }
        }

        drawPath(
            path = path,
            color = Spo2Blue,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}

@Composable
fun StageCard(
    modifier: Modifier = Modifier,
    title: String,
    duration: String,
    percentage: String,
    target: String,
    color: Color
) {
    ElevatedCard(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
                Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Text(duration, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(percentage, style = MaterialTheme.typography.bodySmall, color = color, fontWeight = FontWeight.Bold)
            }
            Text(target, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun StageDistributionBar(session: SleepSession) {
    val total = session.totalInBedMinutes.toFloat().coerceAtLeast(1f)
    val deepRatio = session.deepMinutes / total
    val remRatio = session.remMinutes / total
    val lightRatio = session.lightMinutes / total
    val awakeRatio = session.awakeMinutes / total

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(16.dp)
            .clip(RoundedCornerShape(8.dp))
    ) {
        if (deepRatio > 0) Box(modifier = Modifier.weight(deepRatio).fillMaxHeight().background(DeepSleepBlue))
        if (remRatio > 0) Box(modifier = Modifier.weight(remRatio).fillMaxHeight().background(RemSleepCyan))
        if (lightRatio > 0) Box(modifier = Modifier.weight(lightRatio).fillMaxHeight().background(LightSleepTeal))
        if (awakeRatio > 0) Box(modifier = Modifier.weight(awakeRatio).fillMaxHeight().background(AwakeSleepOrange))
    }
}

@Composable
fun StageBadge(label: String, time: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(color))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(time, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
    }
}
