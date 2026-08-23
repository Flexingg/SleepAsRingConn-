package com.randallengineering.sleepasringconn.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.randallengineering.sleepasringconn.ble.BleConnectionManager
import com.randallengineering.sleepasringconn.protocol.RingProtocol

@Composable
fun DiagnosticsScreen() {
    val recentLogs by BleConnectionManager.recentLogs.collectAsState()
    val isConnected by BleConnectionManager.isConnected.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "BLE Packet & Protocol Console",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }

        // Action Toolbar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalButton(
                onClick = { BleConnectionManager.sendCommand(RingProtocol.CMD_STATUS_0) },
                enabled = isConnected,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("01 00 (Status)")
            }

            FilledTonalButton(
                onClick = { BleConnectionManager.sendCommand(RingProtocol.CMD_STATUS_QUERY) },
                enabled = isConnected,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("D0 00 (Telemetry)")
            }

            FilledTonalButton(
                onClick = { BleConnectionManager.syncHistory() },
                enabled = isConnected,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Drain 0x00 & 0x03")
            }
        }

        // Terminal Log View
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = Color(0xFF1E1E2E)
            )
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
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
                            logEntry.contains("failed") || logEntry.contains("Disconnected") -> Color(0xFFF38BA8)
                            else -> Color(0xFFCDD6F4)
                        }
                    )
                }
            }
        }
    }
}
