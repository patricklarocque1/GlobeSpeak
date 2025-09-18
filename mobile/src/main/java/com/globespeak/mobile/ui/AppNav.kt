package com.globespeak.mobile.ui

import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.globespeak.mobile.ui.dashboard.DashboardScreen
import com.globespeak.mobile.ui.languages.LanguagesScreen
import com.globespeak.mobile.ui.logs.LogsScreen
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Info
import com.globespeak.mobile.ui.about.AboutScreen
import com.globespeak.mobile.ui.bench.BenchScreen

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    data object Dashboard : Screen("dashboard", "Dashboard", Icons.Filled.Home)
    data object Languages : Screen("languages", "Languages", Icons.Filled.Language)
    data object Logs : Screen("logs", "Logs", Icons.Filled.List)
    data object About : Screen("about", "About", Icons.Filled.Info)
    data object Bench : Screen("bench", "Bench", Icons.Filled.Info)
}

@Composable
fun AppNav() {
    val navController = rememberNavController()
    val isDebug = (androidx.compose.ui.platform.LocalContext.current.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    val items = buildList {
        add(Screen.Dashboard)
        add(Screen.Languages)
        add(Screen.Logs)
        if (isDebug) add(Screen.Bench)
        add(Screen.About)
    }

    Scaffold(
        bottomBar = {
            NavigationBar(tonalElevation = 0.dp) {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route
                items.forEach { screen ->
                    NavigationBarItem(
                        selected = currentRoute == screen.route,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(screen.icon, contentDescription = screen.label) },
                        label = { Text(screen.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Dashboard.route,
            modifier = androidx.compose.ui.Modifier.padding(padding)
        ) {
            composable(Screen.Dashboard.route) {
                DashboardScreen(onImportWhisper = {
                    navController.navigate(Screen.Languages.route) {
                        launchSingleTop = true
                    }
                })
            }
            composable(Screen.Languages.route) { LanguagesScreen() }
            composable(Screen.Logs.route) { LogsScreen() }
            composable(Screen.About.route) { AboutScreen() }
            if (isDebug) composable(Screen.Bench.route) { BenchScreen() }
        }
    }
}
