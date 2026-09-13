package com.randallengineering.sleepasringconn.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.randallengineering.sleepasringconn.ble.BleConnectionManager
import com.randallengineering.sleepasringconn.protocol.RingProtocol
import com.randallengineering.sleepasringconn.ui.theme.HeartRateRed
import com.randallengineering.sleepasringconn.ui.theme.SleepPurple
import com.randallengineering.sleepasringconn.ui.theme.StepsGreen
import com.randallengineering.sleepasringconn.ui.theme.TempAmber

@Composable
fun DiagnosticsScreen() {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val recentLogs by BleConnectionManager.recentLogs.collectAsState()
    val isConnected by BleConnectionManager.isConnected.collectAsState()
    val connectionState by BleConnectionManager.connectionState.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "BLE Protocol Console",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Raw Protocol Frames & Packet Inspector",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalIconButton(
                        onClick = {
                            val allLogs = recentLogs.joinToString("\n")
                            clipboardManager.setText(AnnotatedString(allLogs))
                            Toast.makeText(context, "Copied ${recentLogs.size} log entries", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy Logs")
                    }
                }
            }
        }

        // 2. Status Hero Card
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
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
                                .background(if (isConnected) StepsGreen.copy(alpha = 0.2f) else HeartRateRed.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Terminal,
                                contentDescription = null,
                                tint = if (isConnected) StepsGreen else HeartRateRed,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Column {
                            Text("GATT Channel State", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(connectionState, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = (if (isConnected) StepsGreen else HeartRateRed).copy(alpha = 0.2f)
                    ) {
                        Text(
                            text = if (isConnected) "Active MTU 512" else "Disconnected",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (isConnected) StepsGreen else HeartRateRed
                        )
                    }
                }
            }
        }

        // 3. Command Action Chips
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    modifier = Modifier.weight(1f),
                    onClick = { BleConnectionManager.sendCommand(RingProtocol.CMD_STATUS_0) },
                    enabled = isConnected,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("01 00 (Status)", fontSize = 12.sp)
                }

                FilledTonalButton(
                    modifier = Modifier.weight(1f),
                    onClick = { BleConnectionManager.sendCommand(RingProtocol.CMD_STATUS_QUERY) },
                    enabled = isConnected,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("D0 00 (Telemetry)", fontSize = 12.sp)
                }

                FilledTonalButton(
                    modifier = Modifier.weight(1f),
                    onClick = { BleConnectionManager.syncHistory() },
                    enabled = isConnected,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Drain 0x00 & 0x03", fontSize = 12.sp)
                }
            }
        }

        // 4. Monospace Terminal View
        item {
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(420.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = Color(0xFF13141F)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(StepsGreen))
                            Text("Real-Time BLE Packet Stream", style = MaterialTheme.typography.labelSmall, color = Color.LightGray, fontWeight = FontWeight.Bold)
                        }
                        Text("${recentLogs.size} packets", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    }

                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        reverseLayout = true
                    ) {
                        items(recentLogs.reversed()) { logEntry ->
                            Text(
                                text = logEntry,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = when {
                                    logEntry.contains("Live HR") || logEntry.contains("Live SpO2") -> Color(0xFF89DCEB)
                                    logEntry.contains("Auth Challenge") || logEntry.contains("SM3") -> Color(0xFFF9E2AF)
                                    logEntry.contains("0x4C") || logEntry.contains("epochs") -> Color(0xFFA6E3A1)
                                    logEntry.contains("Disconnect") || logEntry.contains("error") || logEntry.contains("failed") -> Color(0xFFF38BA8)
                                    logEntry.contains("Connected") || logEntry.contains("ACK") -> Color(0xFFCBA6F7)
                                    else -> Color(0xFFCDD6F4)
                                },
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
