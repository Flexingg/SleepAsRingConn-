package com.randallengineering.sleepasringconn.ui.screens

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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.randallengineering.sleepasringconn.data.AppDatabase
import com.randallengineering.sleepasringconn.goals.*
import com.randallengineering.sleepasringconn.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalsScreen() {
    val context = LocalContext.current
    val database = remember { AppDatabase.getDatabase(context) }

    val fourteenDaysAgo = remember { System.currentTimeMillis() - 14L * 24 * 60 * 60 * 1000 }
    val epochs by database.epochDao().getEpochsSinceFlow(fourteenDaysAgo).collectAsState(initial = emptyList())
    val sessions by database.sleepSessionDao().getAllSessions().collectAsState(initial = emptyList())
    val statusLogs by database.deviceStatusDao().getStatusLogsSinceFlow(fourteenDaysAgo).collectAsState(initial = emptyList())

    var refreshTrigger by remember { mutableIntStateOf(0) }
    var selectedGoalForEdit by remember { mutableStateOf<GoalProgress?>(null) }

    val evalResult = remember(epochs, sessions, statusLogs, refreshTrigger) {
        GoalEngine.evaluate(context, epochs, sessions, statusLogs)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Header Title
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Intelligent Goals",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Biometric Targets & Coaching",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                FilledTonalIconButton(
                    onClick = {
                        GoalPreferences.resetToDefaults(context)
                        refreshTrigger++
                    }
                ) {
                    Icon(Icons.Default.RestartAlt, contentDescription = "Reset Defaults")
                }
            }
        }

        // 2. Overall Compliance & Streak Summary Card
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
                        Box(
                            modifier = Modifier.size(100.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val strokeWidth = 10.dp.toPx()
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

                                val sweep = 270f * (evalResult.overallCompliancePct / 100f)
                                drawArc(
                                    brush = Brush.sweepGradient(
                                        listOf(StepsGreen, Color(0xFF00E676), SleepPurple)
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
                                    text = "${evalResult.overallCompliancePct}%",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Score",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Weekly Goal Performance",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )

                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = TempAmber.copy(alpha = 0.15f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(Icons.Default.LocalFireDepartment, contentDescription = null, tint = TempAmber, modifier = Modifier.size(18.dp))
                                    Text(
                                        text = "${evalResult.totalStreakDays}-Day Best Streak",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = TempAmber
                                    )
                                }
                            }

                            val onTrackCount = evalResult.goals.count { it.status == GoalStatus.ACHIEVED || it.status == GoalStatus.ON_TRACK }
                            Text(
                                text = "$onTrackCount of ${evalResult.goals.size} targets on track this week",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // 3. Dynamic Smart Coaching & Health Tips Section
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Psychology, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                    Text(
                        text = "Smart Coaching & Insights",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Text(
                    text = "${evalResult.tips.size} active",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                evalResult.tips.forEach { tip ->
                    HealthTipCard(tip = tip)
                }
            }
        }

        // 4. Active Health Goal Cards
        item {
            Text(
                text = "Target Biomarkers",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }

        items(evalResult.goals) { goal ->
            GoalCard(
                goal = goal,
                onEditClick = { selectedGoalForEdit = goal }
            )
        }

        // 5. Milestones & Achievement Badges
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.MilitaryTech, contentDescription = null, tint = TempAmber, modifier = Modifier.size(22.dp))
                    Text(
                        text = "Milestones & Badges",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                val unlockedCount = evalResult.badges.count { it.isUnlocked }
                Text(
                    text = "$unlockedCount / ${evalResult.badges.size} Unlocked",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = TempAmber
                )
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                evalResult.badges.chunked(2).forEach { rowBadges ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        rowBadges.forEach { badge ->
                            MilestoneBadgeCard(
                                modifier = Modifier.weight(1f),
                                badge = badge
                            )
                        }
                        if (rowBadges.size == 1) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }

    // Modal Bottom Sheet to Edit Goal Target
    if (selectedGoalForEdit != null) {
        val goal = selectedGoalForEdit!!
        var currentSliderVal by remember(goal) { mutableFloatStateOf(goal.targetValue) }

        ModalBottomSheet(
            onDismissRequest = { selectedGoalForEdit = null }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Adjust ${goal.goalType.title}",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = goal.goalType.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(goal.goalType.color.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(getGoalIcon(goal.goalType), contentDescription = null, tint = goal.goalType.color, modifier = Modifier.size(20.dp))
                    }
                }

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = goal.goalType.formatValue(currentSliderVal),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = goal.goalType.color
                        )
                        Text(
                            text = "Target ${goal.goalType.unit}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                val numSteps = (((goal.goalType.maxTarget - goal.goalType.minTarget) / goal.goalType.step).toInt() - 1).coerceAtLeast(0)
                Slider(
                    value = currentSliderVal,
                    onValueChange = { currentSliderVal = it },
                    valueRange = goal.goalType.minTarget..goal.goalType.maxTarget,
                    steps = numSteps
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = goal.goalType.formatValue(goal.goalType.minTarget),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Default: ${goal.goalType.formatValue(goal.goalType.defaultTarget)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = goal.goalType.formatValue(goal.goalType.maxTarget),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = { currentSliderVal = goal.goalType.defaultTarget },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Reset Default")
                    }

                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            GoalPreferences.setTarget(context, goal.goalType, currentSliderVal)
                            refreshTrigger++
                            selectedGoalForEdit = null
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Save Target")
                    }
                }
            }
        }
    }
}

@Composable
fun HealthTipCard(tip: HealthTip) {
    var isExpanded by remember { mutableStateOf(false) }

    val (accentColor, icon) = when (tip.severity) {
        TipSeverity.CELEBRATION -> StepsGreen to Icons.Default.EmojiEvents
        TipSeverity.WARNING -> HeartRateRed to Icons.Default.WarningAmber
        TipSeverity.ADVICE -> TempAmber to Icons.Default.TipsAndUpdates
        TipSeverity.INSIGHT -> SleepPurple to Icons.Default.Lightbulb
    }

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { isExpanded = !isExpanded },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(accentColor.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(icon, contentDescription = null, tint = accentColor, modifier = Modifier.size(18.dp))
                    }
                    Column {
                        Text(
                            text = tip.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = tip.category.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = accentColor,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                IconButton(onClick = { isExpanded = !isExpanded }) {
                    Icon(
                        if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = "Expand",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            AnimatedVisibility(visible = isExpanded) {
                Text(
                    text = tip.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
fun GoalCard(
    goal: GoalProgress,
    onEditClick: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEditClick() },
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
                            .background(goal.goalType.color.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            getGoalIcon(goal.goalType),
                            contentDescription = null,
                            tint = goal.goalType.color,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Column {
                        Text(
                            text = goal.goalType.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Target: ${goal.goalType.formatValue(goal.targetValue)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                val (badgeText, badgeBg, badgeTextColor) = when (goal.status) {
                    GoalStatus.ACHIEVED -> Triple("Achieved", StepsGreen.copy(alpha = 0.2f), StepsGreen)
                    GoalStatus.ON_TRACK -> Triple("On Track", MaterialTheme.colorScheme.primary.copy(alpha = 0.2f), MaterialTheme.colorScheme.primary)
                    GoalStatus.OFF_TRACK -> Triple("Needs Focus", HeartRateRed.copy(alpha = 0.2f), HeartRateRed)
                    GoalStatus.NO_DATA -> Triple("Sync Ring", MaterialTheme.colorScheme.outlineVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = badgeBg
                ) {
                    Text(
                        text = badgeText,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = badgeTextColor
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Column {
                    Text("Latest Actual", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = goal.goalType.formatValue(goal.todayValue),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = goal.goalType.color
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text("7-Day Average", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = goal.goalType.formatValue(goal.weeklyAverage),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for (day in goal.past7Days) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = day.dayLabel.take(1),
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Box(
                                modifier = Modifier
                                    .size(16.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when {
                                            day.isAchieved -> StepsGreen
                                            day.actualValue != null -> HeartRateRed.copy(alpha = 0.5f)
                                            else -> Color.Gray.copy(alpha = 0.2f)
                                        }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (day.isAchieved) {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(10.dp))
                                }
                            }
                        }
                    }

                    VerticalDivider(modifier = Modifier.height(24.dp).padding(horizontal = 4.dp))

                    Text(
                        text = "${goal.achievementRatePct}% Met",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (goal.achievementRatePct >= 70) StepsGreen else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun MilestoneBadgeCard(
    modifier: Modifier = Modifier,
    badge: MilestoneBadge
) {
    ElevatedCard(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (badge.isUnlocked) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(if (badge.isUnlocked) TempAmber.copy(alpha = 0.25f) else Color.Gray.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (badge.isUnlocked) Icons.Default.EmojiEvents else Icons.Default.Lock,
                        contentDescription = null,
                        tint = if (badge.isUnlocked) TempAmber else Color.Gray,
                        modifier = Modifier.size(18.dp)
                    )
                }

                if (badge.isUnlocked) {
                    SuggestionChip(
                        onClick = {},
                        label = { Text("Unlocked", fontSize = 10.sp, fontWeight = FontWeight.Bold) },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = TempAmber,
                            labelColor = Color.Black
                        ),
                        border = null,
                        modifier = Modifier.height(22.dp)
                    )
                }
            }

            Text(
                text = badge.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (badge.isUnlocked) MaterialTheme.colorScheme.onSurface else Color.Gray
            )

            Text(
                text = badge.description,
                style = MaterialTheme.typography.bodySmall,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 14.sp
            )

            LinearProgressIndicator(
                progress = { badge.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = if (badge.isUnlocked) TempAmber else MaterialTheme.colorScheme.primary,
                trackColor = Color.White.copy(alpha = 0.1f)
            )

            Text(
                text = badge.levelDescription,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

fun getGoalIcon(goalType: GoalType): ImageVector {
    return when (goalType) {
        GoalType.SLEEP_DURATION -> Icons.Default.Bedtime
        GoalType.DEEP_SLEEP_PCT -> Icons.Default.Psychology
        GoalType.RESTING_HR -> Icons.Default.Favorite
        GoalType.HRV_RECOVERY -> Icons.Default.MonitorHeart
        GoalType.DAILY_STEPS -> Icons.Default.DirectionsWalk
        GoalType.BEDTIME_CONSISTENCY -> Icons.Default.Schedule
        GoalType.SPO2_FLOOR -> Icons.Default.Air
    }
}
