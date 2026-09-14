package com.randallengineering.sleepasringconn.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.randallengineering.sleepasringconn.analytics.SleepBout
import com.randallengineering.sleepasringconn.analytics.SleepCycle
import com.randallengineering.sleepasringconn.analytics.SleepSession
import com.randallengineering.sleepasringconn.analytics.SleepStage
import com.randallengineering.sleepasringconn.analytics.SleepStagingEngine
import com.randallengineering.sleepasringconn.analytics.StagedEpoch
import com.randallengineering.sleepasringconn.analytics.VitalExtreme
import com.randallengineering.sleepasringconn.data.AppDatabase
import com.randallengineering.sleepasringconn.data.SleepSessionManager
import com.randallengineering.sleepasringconn.protocol.BulkRecord
import com.randallengineering.sleepasringconn.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepScreen() {
    val context = LocalContext.current
    val database = remember { AppDatabase.getDatabase(context) }
    var selectedEpoch by remember { mutableStateOf<StagedEpoch?>(null) }
    var selectedSessionIndex by remember { mutableIntStateOf(0) }
    var selectedViewMode by remember { mutableIntStateOf(0) } // 0 = Session, 1 = Trends

    var showEditDialog by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var sessionToEdit by remember { mutableStateOf<SleepSession?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }

    val now = remember { System.currentTimeMillis() }
    val past30d = remember { now - 30 * 24 * 60 * 60 * 1000L }
    val epochEntities by database.epochDao().getEpochsSinceFlow(past30d).collectAsState(initial = emptyList())

    var allSessions by remember { mutableStateOf<List<SleepSession>>(emptyList()) }

    LaunchedEffect(epochEntities, refreshKey) {
        withContext(Dispatchers.Default) {
            allSessions = SleepSessionManager.getProcessedSessions(context, epochEntities)
        }
    }

    val sleepSession = allSessions.getOrNull(selectedSessionIndex) ?: allSessions.firstOrNull()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Top Header & View Mode Switcher
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Sleep & Recovery Analysis",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = selectedViewMode == 0,
                        onClick = { selectedViewMode = 0 },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        icon = {
                            Icon(
                                Icons.Default.Bedtime,
                                contentDescription = null,
                                modifier = Modifier.size(SegmentedButtonDefaults.IconSize)
                            )
                        }
                    ) {
                        Text("Session Deep Dive")
                    }
                    SegmentedButton(
                        selected = selectedViewMode == 1,
                        onClick = { selectedViewMode = 1 },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        icon = {
                            Icon(
                                Icons.Default.ShowChart,
                                contentDescription = null,
                                modifier = Modifier.size(SegmentedButtonDefaults.IconSize)
                            )
                        }
                    ) {
                        Text("Longitudinal Trends")
                    }
                }
            }
        }

        if (allSessions.isEmpty()) {
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
                            text = "No sleep or nap recorded yet",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Wear your RingConn Gen 2 overnight or during naps. The app will sync and stage your sleep stages, resting HR dip, HRV, and SpO2 trends locally.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FilledTonalButton(onClick = { showAddDialog = true }) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Add Sleep / Nap Manually")
                        }
                    }
                }
            }
        } else if (selectedViewMode == 0) {
            // ==========================================
            // VIEW 0: SESSION DEEP DIVE
            // ==========================================
            val session = sleepSession ?: allSessions.first()

            // Session List Header with + Add Nap/Sleep button
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Sleep Sessions & Naps (${allSessions.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    TextButton(
                        onClick = { showAddDialog = true },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add Session", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            // Night & Nap / Date Selector Chips
            item {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(allSessions) { idx, sess ->
                        val isSelected = (idx == selectedSessionIndex)
                        val durHours = sess.sleepDurationMinutes / 60
                        val durMins = sess.sleepDurationMinutes % 60
                        val durText = if (durHours > 0) "${durHours}h ${durMins}m" else "${durMins}m"

                        val dateStr = when {
                            sess.isNap -> {
                                val timeStr = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(sess.startTimeMillis))
                                "$timeStr (${sess.sessionLabel})"
                            }
                            else -> {
                                SimpleDateFormat("EEE, MMM d (h:mm a)", Locale.getDefault()).format(Date(sess.startTimeMillis))
                            }
                        }

                        val chipTint = if (sess.isNap) Color(0xFFFFA000) else SleepPurple

                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                selectedSessionIndex = idx
                                selectedEpoch = null
                            },
                            label = {
                                Column(modifier = Modifier.padding(vertical = 2.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(dateStr, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                                        if (sess.isUserEdited) {
                                            Spacer(Modifier.width(4.dp))
                                            Text(
                                                "• Edited",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                    Text(
                                        "$durText • Score ${sess.sleepScore}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isSelected) chipTint else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (sess.isNap) Icons.Default.WbSunny else Icons.Default.Bedtime,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = if (isSelected) chipTint else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        )
                    }
                }
            }

            // 1. Sleep / Nap Hero Summary Card
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
                            // Circular Sleep Score Dial
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

                                    val sweep = 270f * (session.sleepScore / 100f)
                                    val scoreColor = if (session.isNap) Color(0xFFFFA000) else if (session.sleepScore >= 80) StepsGreen else if (session.sleepScore >= 70) SleepPurple else TempAmber

                                    drawArc(
                                        brush = Brush.sweepGradient(
                                            listOf(scoreColor, Color(0xFF00E676), DeepSleepBlue)
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
                                    Text(
                                        text = "${session.sleepScore}",
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = if (session.isNap) "Recovery" else "Score",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            // Info Column
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = (if (session.isNap) Color(0xFFFFA000) else SleepPurple).copy(alpha = 0.15f)
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Icon(
                                                    imageVector = if (session.isNap) Icons.Default.WbSunny else Icons.Default.Bedtime,
                                                    contentDescription = null,
                                                    tint = if (session.isNap) Color(0xFFFFA000) else SleepPurple,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                                Text(
                                                    text = session.sessionLabel.uppercase(),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (session.isNap) Color(0xFFFFA000) else SleepPurple
                                                )
                                            }
                                        }

                                        if (session.isUserEdited) {
                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                color = MaterialTheme.colorScheme.primaryContainer
                                            ) {
                                                Text(
                                                    text = "EDITED",
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                                )
                                            }
                                        }
                                    }

                                    IconButton(
                                        onClick = {
                                            sessionToEdit = session
                                            showEditDialog = true
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Edit,
                                            contentDescription = "Edit Sleep Times",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }

                                val sleepHours = session.sleepDurationMinutes / 60
                                val sleepMins = session.sleepDurationMinutes % 60
                                val sleepText = if (sleepHours > 0) "${sleepHours}h ${sleepMins}m" else "${sleepMins}m"

                                Text(
                                    text = sleepText,
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )

                                val bedHours = session.totalInBedMinutes / 60
                                val bedMins = session.totalInBedMinutes % 60
                                val bedText = if (bedHours > 0) "${bedHours}h ${bedMins}m" else "${bedMins}m"
                                Text(
                                    text = "In Bed: $bedText • Efficiency: ${session.sleepEfficiencyPercent}%",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Sleep Stage Distribution Bar
                        StageDistributionBar(session)
                    }
                }
            }

            // 2. Exact Timings & Architecture Metrics Grid
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Sleep Architecture & Timings",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    OutlinedButton(
                        onClick = {
                            sessionToEdit = session
                            showEditDialog = true
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Edit Times", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            item {
                val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        MetricTile(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Default.Bedtime,
                            label = "In Bed / Out of Bed",
                            value = "${timeFmt.format(Date(session.startTimeMillis))} – ${timeFmt.format(Date(session.endTimeMillis))}",
                            subtext = "${session.totalInBedMinutes} min total window",
                            tint = SleepPurple
                        )
                        MetricTile(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Default.AirlineSeatIndividualSuite,
                            label = "Sleep Onset & Latency",
                            value = timeFmt.format(Date(session.sleepOnsetMillis)),
                            subtext = "Fell asleep in ${session.sleepLatencyMinutes} min",
                            tint = RemSleepCyan
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        MetricTile(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Default.AccessTime,
                            label = "Mid-Sleep Point",
                            value = timeFmt.format(Date(session.midSleepMillis)),
                            subtext = "Circadian midpoint",
                            tint = LightSleepTeal
                        )
                        MetricTile(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Default.WbSunny,
                            label = "Final Wake-up",
                            value = timeFmt.format(Date(session.finalWakeMillis)),
                            subtext = "Efficiency ${session.sleepEfficiencyPercent}%",
                            tint = AwakeSleepOrange
                        )
                    }
                }
            }

            // 3. Telemetry Extremes Grid (HR Floor, Peak HRV, SpO2 Trough, Restlessness)
            item {
                Text(
                    text = "Biometric Extremes & Stability",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            item {
                val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val hrFloorStr = session.lowestHr?.let { "${it.value.toInt()} BPM" } ?: (session.averageHeartRate?.let { "$it BPM" } ?: "--")
                        val hrFloorTime = session.lowestHr?.let { "@ ${timeFmt.format(Date(it.timestampMillis))}" } ?: "Avg ${session.averageHeartRate ?: "--"} BPM"
                        MetricTile(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Default.Favorite,
                            label = "Resting HR Floor",
                            value = hrFloorStr,
                            subtext = hrFloorTime,
                            tint = HeartRateRed
                        )

                        val peakHrvStr = session.peakHrv?.let { "${it.value.toInt()} ms" } ?: (session.averageHrvRmssd?.let { "${it} ms" } ?: "--")
                        val peakHrvTime = session.peakHrv?.let { "@ ${timeFmt.format(Date(it.timestampMillis))}" } ?: "Avg ${session.averageHrvRmssd ?: "--"} ms"
                        MetricTile(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Default.Timeline,
                            label = "Peak HRV Recovery",
                            value = peakHrvStr,
                            subtext = peakHrvTime,
                            tint = Color(0xFF00E676)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val lowestSpo2Str = session.lowestSpo2?.let { "${it.value.toInt()}%" } ?: (session.averageSpo2?.let { "$it%" } ?: "--")
                        val lowestSpo2Time = session.lowestSpo2?.let { "@ ${timeFmt.format(Date(it.timestampMillis))}" } ?: "Avg ${session.averageSpo2 ?: "--"}%"
                        MetricTile(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Default.Air,
                            label = "Lowest SpO2 Trough",
                            value = lowestSpo2Str,
                            subtext = lowestSpo2Time,
                            tint = Spo2Blue
                        )

                        val respStr = session.averageRespiratoryRate?.let { "%.1f br/m".format(it) } ?: "--"
                        val respRange = if (session.minRespiratoryRate != null && session.maxRespiratoryRate != null) {
                            "Range: %.1f - %.1f".format(session.minRespiratoryRate, session.maxRespiratoryRate)
                        } else {
                            "${session.stillEpochsPercent}% still body"
                        }
                        MetricTile(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Default.GraphicEq,
                            label = "Respiratory Rate",
                            value = respStr,
                            subtext = respRange,
                            tint = Color(0xFF80D8FF)
                        )
                    }
                }
            }

            // 4. Stage Breakdown Badges
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

            // 5. Interactive Hypnogram Timeline Chart
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
                                        Text("Motion: ${epoch.motionIntensity}", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 6. Sleep Cycles List (if any completed)
            if (session.cycles.isNotEmpty()) {
                item {
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "Ultradian Sleep Cycles (${session.cycles.size} Complete)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = DeepSleepBlue
                            )
                            val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
                            session.cycles.forEach { cycle ->
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text(
                                                "Cycle ${cycle.cycleIndex} (${cycle.durationMinutes}m)",
                                                fontWeight = FontWeight.Bold,
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                            Text(
                                                "${timeFmt.format(Date(cycle.startTimeMillis))} – ${timeFmt.format(Date(cycle.endTimeMillis))}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            if (cycle.deepMinutes > 0) Text("${cycle.deepMinutes}m Deep", color = DeepSleepBlue, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                            if (cycle.remMinutes > 0) Text("${cycle.remMinutes}m REM", color = RemSleepCyan, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                            if (cycle.lightMinutes > 0) Text("${cycle.lightMinutes}m Light", color = LightSleepTeal, style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 7. Nocturnal Awakenings Log
            if (session.awakenings.isNotEmpty()) {
                item {
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Nocturnal Awakenings (${session.awakenings.size})",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = AwakeSleepOrange
                                )
                                val totalAwake = session.awakenings.sumOf { it.durationMinutes }
                                Text("$totalAwake min total", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
                            session.awakenings.forEach { awake ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(AwakeSleepOrange))
                                        Text(
                                            "${timeFmt.format(Date(awake.startTimeMillis))} – ${timeFmt.format(Date(awake.endTimeMillis))}",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    Text(
                                        "${awake.durationMinutes}m (Peak mot: ${awake.peakMotion})",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 8. Overnight Heart Rate & HRV Curve
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

            // 9. Overnight SpO2 & Respiration Rate Chart
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

            // 10. Detailed Stage Breakdown Cards
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
        } else {
            // ==========================================
            // VIEW 1: MULTI-NIGHT LONGITUDINAL TRENDS
            // ==========================================
            val overnightSessions = allSessions.filter { !it.isNap }

            // 1. Longitudinal Summary Overview Card
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
                        Text(
                            text = "Multi-Night Overview (${overnightSessions.size} Nights Recorded)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = SleepPurple
                        )

                        val avgScore = if (overnightSessions.isNotEmpty()) overnightSessions.map { it.sleepScore }.average().toInt() else 0
                        val avgDuration = if (overnightSessions.isNotEmpty()) overnightSessions.map { it.sleepDurationMinutes }.average().toInt() else 0
                        val avgEff = if (overnightSessions.isNotEmpty()) overnightSessions.map { it.sleepEfficiencyPercent }.average().toInt() else 0
                        val avgDeep = if (overnightSessions.isNotEmpty()) overnightSessions.map { it.deepMinutes }.average().toInt() else 0
                        val avgRem = if (overnightSessions.isNotEmpty()) overnightSessions.map { it.remMinutes }.average().toInt() else 0

                        val durH = avgDuration / 60
                        val durM = avgDuration % 60

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("Avg Sleep Score", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("$avgScore", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = SleepPurple)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("Avg Sleep Time", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${durH}h ${durM}m", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                Text("Avg Efficiency: $avgEff%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Avg Deep: ${avgDeep}m", color = DeepSleepBlue, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                            Text("Avg REM: ${avgRem}m", color = RemSleepCyan, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                            val avgHr = overnightSessions.mapNotNull { it.averageHeartRate }.let { if (it.isNotEmpty()) it.average().toInt() else null }
                            Text("Avg HR: ${avgHr ?: "--"} BPM", color = HeartRateRed, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            // 2. Multi-Night Stacked Sleep Duration Bar Chart
            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Sleep Duration & Stages by Night",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Stacked bars show Deep, REM, Light, and Awake composition across recorded nights.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        MultiNightStackedBarChart(sessions = overnightSessions.reversed())
                    }
                }
            }

            // 3. Multi-Night Resting HR Floor & Peak HRV Trend
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
                                text = "Resting HR Floor & HRV Trends",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = HeartRateRed
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("● HR Floor", color = HeartRateRed, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                Text("● Peak HRV", color = Color(0xFF00E676), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            }
                        }

                        MultiNightVitalsTrendChart(sessions = overnightSessions.reversed())
                    }
                }
            }

            // 4. Multi-Night Sleep Score Progression
            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Sleep Score Progression",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = SleepPurple
                        )

                        MultiNightScoreTrendChart(sessions = overnightSessions.reversed())
                    }
                }
            }

            // 5. Historical Sessions List
            item {
                Text(
                    text = "Recorded Sessions History",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    allSessions.forEachIndexed { idx, sess ->
                        val dateFmt = SimpleDateFormat("EEEE, MMM d", Locale.getDefault())
                        val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
                        val durH = sess.sleepDurationMinutes / 60
                        val durM = sess.sleepDurationMinutes % 60

                        ElevatedCard(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            onClick = {
                                selectedSessionIndex = idx
                                selectedViewMode = 0
                            }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(44.dp)
                                            .clip(CircleShape)
                                            .background(if (sess.isNap) Color(0xFFFFA000).copy(alpha = 0.15f) else SleepPurple.copy(alpha = 0.15f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = if (sess.isNap) Icons.Default.WbSunny else Icons.Default.Bedtime,
                                            contentDescription = null,
                                            tint = if (sess.isNap) Color(0xFFFFA000) else SleepPurple,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }

                                    Column {
                                        Text(
                                            if (sess.isNap) "${dateFmt.format(Date(sess.startTimeMillis))} (${sess.sessionLabel})" else dateFmt.format(Date(sess.startTimeMillis)),
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            "${timeFmt.format(Date(sess.startTimeMillis))} – ${timeFmt.format(Date(sess.endTimeMillis))}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        if (durH > 0) "${durH}h ${durM}m" else "${durM}m",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        "Score: ${sess.sleepScore}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (sess.isNap) Color(0xFFFFA000) else SleepPurple
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showEditDialog && sessionToEdit != null) {
        val sess = sessionToEdit!!
        EditSleepSessionDialog(
            session = sess,
            onDismiss = {
                showEditDialog = false
                sessionToEdit = null
            },
            onSave = { newStart, newEnd, isNap ->
                SleepSessionManager.saveSessionEdit(
                    context = context,
                    originalStart = sess.originalStartTimeMillis,
                    newStart = newStart,
                    newEnd = newEnd,
                    isNap = isNap
                )
                showEditDialog = false
                sessionToEdit = null
                refreshKey++
            },
            onReset = {
                SleepSessionManager.resetSessionEdit(context, sess.originalStartTimeMillis)
                showEditDialog = false
                sessionToEdit = null
                refreshKey++
            },
            onDelete = {
                SleepSessionManager.deleteSession(context, sess.originalStartTimeMillis)
                showEditDialog = false
                sessionToEdit = null
                if (selectedSessionIndex >= allSessions.size - 1) {
                    selectedSessionIndex = maxOf(0, allSessions.size - 2)
                }
                refreshKey++
            }
        )
    }

    if (showAddDialog) {
        AddSleepSessionDialog(
            onDismiss = { showAddDialog = false },
            onSave = { newStart, newEnd, isNap ->
                SleepSessionManager.addManualSession(
                    context = context,
                    startTime = newStart,
                    endTime = newEnd,
                    isNap = isNap
                )
                showAddDialog = false
                selectedSessionIndex = 0
                refreshKey++
            }
        )
    }
}

@Composable
fun MetricTile(
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    subtext: String,
    tint: Color
) {
    ElevatedCard(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(subtext, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun MultiNightStackedBarChart(sessions: List<SleepSession>) {
    if (sessions.isEmpty()) {
        Text("No overnight records available.", style = MaterialTheme.typography.bodySmall)
        return
    }

    val maxDuration = sessions.maxOfOrNull { it.totalInBedMinutes }?.coerceAtLeast(480) ?: 480

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
    ) {
        val width = size.width
        val height = size.height
        val barCount = sessions.size
        val barSpacing = 16.dp.toPx()
        val totalSpacing = barSpacing * (barCount + 1)
        val barWidth = ((width - totalSpacing) / barCount).coerceIn(12.dp.toPx(), 40.dp.toPx())

        // 8h baseline line
        val y8h = height - ((480f / maxDuration.toFloat()) * height)
        drawLine(
            color = Color.Gray.copy(alpha = 0.25f),
            start = Offset(0f, y8h),
            end = Offset(width, y8h),
            strokeWidth = 1.dp.toPx()
        )

        sessions.forEachIndexed { i, sess ->
            val x = barSpacing + i * (barWidth + barSpacing)
            val totalMin = sess.totalInBedMinutes.toFloat().coerceAtLeast(1f)
            val deepHeight = (sess.deepMinutes / maxDuration.toFloat()) * height
            val remHeight = (sess.remMinutes / maxDuration.toFloat()) * height
            val lightHeight = (sess.lightMinutes / maxDuration.toFloat()) * height
            val awakeHeight = (sess.awakeMinutes / maxDuration.toFloat()) * height

            var currentY = height

            // Draw Deep
            if (deepHeight > 0) {
                drawRoundRect(
                    color = DeepSleepBlue,
                    topLeft = Offset(x, currentY - deepHeight),
                    size = Size(barWidth, deepHeight),
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                )
                currentY -= deepHeight
            }

            // Draw REM
            if (remHeight > 0) {
                drawRect(
                    color = RemSleepCyan,
                    topLeft = Offset(x, currentY - remHeight),
                    size = Size(barWidth, remHeight)
                )
                currentY -= remHeight
            }

            // Draw Light
            if (lightHeight > 0) {
                drawRect(
                    color = LightSleepTeal,
                    topLeft = Offset(x, currentY - lightHeight),
                    size = Size(barWidth, lightHeight)
                )
                currentY -= lightHeight
            }

            // Draw Awake
            if (awakeHeight > 0) {
                drawRoundRect(
                    color = AwakeSleepOrange,
                    topLeft = Offset(x, currentY - awakeHeight),
                    size = Size(barWidth, awakeHeight),
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                )
            }
        }
    }
}

@Composable
fun MultiNightVitalsTrendChart(sessions: List<SleepSession>) {
    if (sessions.isEmpty()) return

    val hrFloors = sessions.mapNotNull { it.lowestHr?.value?.toInt() ?: it.averageHeartRate }
    val peakHrvs = sessions.mapNotNull { it.peakHrv?.value?.toInt() ?: it.averageHrvRmssd }

    if (hrFloors.isEmpty() && peakHrvs.isEmpty()) {
        Text("No vitals telemetry available.", style = MaterialTheme.typography.bodySmall)
        return
    }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(130.dp)
    ) {
        val width = size.width
        val height = size.height

        // HR floor curve (range 40-90)
        val minHr = 40
        val maxHr = 90
        val hrRange = maxHr - minHr
        val stepX = width / maxOf(sessions.size - 1, 1)

        val hrPath = Path()
        val hrvPath = Path()

        sessions.forEachIndexed { i, sess ->
            val x = i * stepX
            val hr = sess.lowestHr?.value?.toInt() ?: sess.averageHeartRate
            if (hr != null) {
                val y = height - (((hr.coerceIn(minHr, maxHr) - minHr).toFloat() / hrRange) * height)
                if (i == 0) hrPath.moveTo(x, y) else hrPath.lineTo(x, y)
                drawCircle(color = HeartRateRed, radius = 3.dp.toPx(), center = Offset(x, y))
            }

            val hrv = sess.peakHrv?.value?.toInt() ?: sess.averageHrvRmssd
            if (hrv != null) {
                val minHrv = 10
                val maxHrv = 120
                val y = height - (((hrv.coerceIn(minHrv, maxHrv) - minHrv).toFloat() / (maxHrv - minHrv)) * height)
                if (i == 0) hrvPath.moveTo(x, y) else hrvPath.lineTo(x, y)
                drawCircle(color = Color(0xFF00E676), radius = 3.dp.toPx(), center = Offset(x, y))
            }
        }

        drawPath(path = hrPath, color = HeartRateRed, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        drawPath(path = hrvPath, color = Color(0xFF00E676), style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
fun MultiNightScoreTrendChart(sessions: List<SleepSession>) {
    if (sessions.isEmpty()) return

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp)
    ) {
        val width = size.width
        val height = size.height
        val stepX = width / maxOf(sessions.size - 1, 1)

        val path = Path()
        sessions.forEachIndexed { i, sess ->
            val x = i * stepX
            val y = height - ((sess.sleepScore.toFloat() / 100f) * height)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            drawCircle(color = SleepPurple, radius = 4.dp.toPx(), center = Offset(x, y))
        }

        drawPath(
            path = path,
            color = SleepPurple,
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
        )
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

                path.lineTo(x, y)
                path.lineTo(nextX, y)
                prevX = nextX
                prevY = y
            }

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

        minPoint?.let { pt ->
            drawCircle(color = HeartRateRed, radius = 5.dp.toPx(), center = pt)
            drawCircle(color = Color.White, radius = 2.5.dp.toPx(), center = pt)
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

@Composable
fun EditSleepSessionDialog(
    session: SleepSession,
    onDismiss: () -> Unit,
    onSave: (newStart: Long, newEnd: Long, isNap: Boolean) -> Unit,
    onReset: () -> Unit,
    onDelete: () -> Unit
) {
    var startMillis by remember { mutableLongStateOf(session.startTimeMillis) }
    var endMillis by remember { mutableLongStateOf(session.endTimeMillis) }
    var isNap by remember { mutableStateOf(session.isNap) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val durMinutes = maxOf(0, ((endMillis - startMillis) / 60_000L).toInt())
    val durHours = durMinutes / 60
    val durMinsRem = durMinutes % 60
    val durText = if (durHours > 0) "${durHours}h ${durMinsRem}m" else "${durMinsRem}m"

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Session?") },
            text = { Text("Are you sure you want to remove this sleep session? It will no longer appear in your sleep or nap history.") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Edit Sleep Times",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Session Type Selector: Nap vs Overnight
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Session Category",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = !isNap,
                            onClick = { isNap = false },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                            icon = {
                                Icon(
                                    Icons.Default.Bedtime,
                                    contentDescription = null,
                                    modifier = Modifier.size(SegmentedButtonDefaults.IconSize)
                                )
                            }
                        ) {
                            Text("Overnight")
                        }
                        SegmentedButton(
                            selected = isNap,
                            onClick = { isNap = true },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                            icon = {
                                Icon(
                                    Icons.Default.WbSunny,
                                    contentDescription = null,
                                    modifier = Modifier.size(SegmentedButtonDefaults.IconSize)
                                )
                            }
                        ) {
                            Text("Nap")
                        }
                    }
                }

                // Window duration summary preview
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = (if (isNap) Color(0xFFFFA000) else SleepPurple).copy(alpha = 0.12f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                "Total Sleep Window",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(durText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        if (endMillis <= startMillis) {
                            Text(
                                "⚠️ End must be after start",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }

                // Start Time Section
                TimeAdjustSection(
                    label = "Sleep Start (Bedtime / Onset)",
                    timestampMillis = startMillis,
                    onAdjust = { deltaMin ->
                        startMillis += deltaMin * 60_000L
                    },
                    onPickTime = { hour, minute ->
                        val cal = Calendar.getInstance().apply {
                            timeInMillis = startMillis
                            set(Calendar.HOUR_OF_DAY, hour)
                            set(Calendar.MINUTE, minute)
                        }
                        startMillis = cal.timeInMillis
                    },
                    onPickDate = { year, month, day ->
                        val cal = Calendar.getInstance().apply {
                            timeInMillis = startMillis
                            set(Calendar.YEAR, year)
                            set(Calendar.MONTH, month)
                            set(Calendar.DAY_OF_MONTH, day)
                        }
                        startMillis = cal.timeInMillis
                    },
                    accentColor = if (isNap) Color(0xFFFFA000) else SleepPurple
                )

                // End Time Section
                TimeAdjustSection(
                    label = "Sleep End (Wake-up Time)",
                    timestampMillis = endMillis,
                    onAdjust = { deltaMin ->
                        endMillis += deltaMin * 60_000L
                    },
                    onPickTime = { hour, minute ->
                        val cal = Calendar.getInstance().apply {
                            timeInMillis = endMillis
                            set(Calendar.HOUR_OF_DAY, hour)
                            set(Calendar.MINUTE, minute)
                        }
                        endMillis = cal.timeInMillis
                    },
                    onPickDate = { year, month, day ->
                        val cal = Calendar.getInstance().apply {
                            timeInMillis = endMillis
                            set(Calendar.YEAR, year)
                            set(Calendar.MONTH, month)
                            set(Calendar.DAY_OF_MONTH, day)
                        }
                        endMillis = cal.timeInMillis
                    },
                    accentColor = AwakeSleepOrange
                )

                // Action buttons: Reset Auto / Delete
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (session.isUserEdited) {
                        TextButton(
                            onClick = onReset,
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Reset Auto")
                        }
                    } else {
                        Spacer(Modifier.width(1.dp))
                    }

                    TextButton(
                        onClick = { showDeleteConfirm = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Delete")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (endMillis > startMillis) {
                        onSave(startMillis, endMillis, isNap)
                    }
                },
                enabled = endMillis > startMillis
            ) {
                Text("Save Changes")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun AddSleepSessionDialog(
    onDismiss: () -> Unit,
    onSave: (startTime: Long, endTime: Long, isNap: Boolean) -> Unit
) {
    val now = remember { System.currentTimeMillis() }
    val oneHourAgo = remember { now - 60 * 60 * 1000L }

    val syntheticSession = remember {
        SleepSession(
            startTimeMillis = oneHourAgo,
            endTimeMillis = now,
            totalInBedMinutes = 60,
            sleepDurationMinutes = 55,
            awakeMinutes = 5,
            lightMinutes = 35,
            deepMinutes = 10,
            remMinutes = 10,
            averageHeartRate = null,
            averageHrvRmssd = null,
            averageSpo2 = null,
            averageRespiratoryRate = null,
            sleepScore = 80,
            epochs = emptyList(),
            isNap = true,
            sessionLabel = "Daytime Nap",
            isUserEdited = false
        )
    }

    EditSleepSessionDialog(
        session = syntheticSession,
        onDismiss = onDismiss,
        onSave = onSave,
        onReset = onDismiss,
        onDelete = onDismiss
    )
}

@Composable
private fun TimeAdjustSection(
    label: String,
    timestampMillis: Long,
    onAdjust: (Int) -> Unit,
    onPickTime: (Int, Int) -> Unit,
    onPickDate: (Int, Int, Int) -> Unit,
    accentColor: Color
) {
    val context = LocalContext.current
    val timeFmt = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    val dateFmt = remember { SimpleDateFormat("EEE, MMM d", Locale.getDefault()) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = timeFmt.format(Date(timestampMillis)),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = accentColor
                        )
                        Text(
                            text = dateFmt.format(Date(timestampMillis)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = {
                                val cal = Calendar.getInstance().apply { timeInMillis = timestampMillis }
                                android.app.TimePickerDialog(
                                    context,
                                    { _, h, m -> onPickTime(h, m) },
                                    cal.get(Calendar.HOUR_OF_DAY),
                                    cal.get(Calendar.MINUTE),
                                    android.text.format.DateFormat.is24HourFormat(context)
                                ).show()
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.AccessTime, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Time", style = MaterialTheme.typography.labelSmall)
                        }

                        OutlinedButton(
                            onClick = {
                                val cal = Calendar.getInstance().apply { timeInMillis = timestampMillis }
                                android.app.DatePickerDialog(
                                    context,
                                    { _, y, m, d -> onPickDate(y, m, d) },
                                    cal.get(Calendar.YEAR),
                                    cal.get(Calendar.MONTH),
                                    cal.get(Calendar.DAY_OF_MONTH)
                                ).show()
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.CalendarToday, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Date", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                // Quick Nudge Buttons: -30m, -15m, +15m, +30m
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf(-30, -15, 15, 30).forEach { delta ->
                        FilledTonalButton(
                            onClick = { onAdjust(delta) },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = if (delta > 0) "+${delta}m" else "${delta}m",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

