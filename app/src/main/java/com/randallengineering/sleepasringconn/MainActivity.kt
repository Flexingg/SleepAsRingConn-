package com.randallengineering.sleepasringconn

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.ContextCompat
import com.randallengineering.sleepasringconn.service.RingSyncService
import com.randallengineering.sleepasringconn.ui.screens.AnalyticsScreen
import com.randallengineering.sleepasringconn.ui.screens.DashboardScreen
import com.randallengineering.sleepasringconn.ui.screens.DiagnosticsScreen
import com.randallengineering.sleepasringconn.ui.screens.IntegrationsScreen
import com.randallengineering.sleepasringconn.ui.screens.SleepScreen
import com.randallengineering.sleepasringconn.ui.theme.SleepAsRingConnTheme

enum class Screen(val title: String, val icon: ImageVector) {
    Dashboard("Dashboard", Icons.Default.Dashboard),
    Sleep("Sleep", Icons.Default.Bedtime),
    Analytics("Analytics", Icons.Default.Insights),
    Integrations("Integrations", Icons.Default.HealthAndSafety),
    Diagnostics("Console", Icons.Default.Terminal)
}

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Start background service once permissions are handled
        RingSyncService.startService(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestRequiredPermissions()

        setContent {
            SleepAsRingConnTheme {
                var currentScreen by remember { mutableStateOf(Screen.Dashboard) }

                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            Screen.entries.forEach { screen ->
                                NavigationBarItem(
                                    selected = currentScreen == screen,
                                    onClick = { currentScreen = screen },
                                    icon = { Icon(screen.icon, contentDescription = screen.title) },
                                    label = { Text(screen.title) }
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {
                        when (currentScreen) {
                            Screen.Dashboard -> DashboardScreen(
                                onNavigateToDiagnostics = { currentScreen = Screen.Diagnostics }
                            )
                            Screen.Sleep -> SleepScreen()
                            Screen.Analytics -> AnalyticsScreen()
                            Screen.Integrations -> IntegrationsScreen()
                            Screen.Diagnostics -> DiagnosticsScreen()
                        }
                    }
                }
            }
        }
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        } else {
            RingSyncService.startService(this)
        }
    }
}
