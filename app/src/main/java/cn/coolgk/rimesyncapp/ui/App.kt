package cn.coolgk.rimesyncapp.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import cn.coolgk.rimesyncapp.ui.screens.LogScreen
import cn.coolgk.rimesyncapp.ui.screens.SettingsScreen
import cn.coolgk.rimesyncapp.ui.screens.SyncScreen
import cn.coolgk.rimesyncapp.ui.theme.RimeSyncTheme

private sealed class Destination(
    val route: String,
    val label: String,
    val filledIcon: androidx.compose.ui.graphics.vector.ImageVector,
    val outlinedIcon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    data object Sync : Destination("sync", "同步", Icons.Filled.Sync, Icons.Outlined.Sync)
    data object Settings : Destination("settings", "设置", Icons.Filled.Settings, Icons.Outlined.Settings)
    data object Logs : Destination("logs", "日志", Icons.Filled.Terminal, Icons.Outlined.Terminal)
}

@Composable
fun RimeSyncApp() {
    RimeSyncTheme {
        val viewModel: MainViewModel = viewModel()
        val state by viewModel.state.collectAsState()
        val navController = rememberNavController()
        val snackbarHostState = remember { SnackbarHostState() }

        LaunchedEffect(state.error) {
            state.error?.let {
                snackbarHostState.showSnackbar(it)
                viewModel.dismissError()
            }
        }

        val destinations = listOf(
            Destination.Sync, Destination.Settings, Destination.Logs
        )

        Scaffold(
            bottomBar = {
                NavigationBar {
                    val backStackEntry by navController.currentBackStackEntryAsState()
                    val currentRoute = backStackEntry?.destination?.route
                    destinations.forEach { dest ->
                        NavigationBarItem(
                            selected = currentRoute == dest.route,
                            onClick = {
                                navController.navigate(dest.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = if (currentRoute == dest.route) dest.filledIcon else dest.outlinedIcon,
                                    contentDescription = dest.label,
                                )
                            },
                            label = { Text(dest.label) },
                        )
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = Destination.Sync.route,
                modifier = Modifier.padding(innerPadding),
            ) {
                composable(Destination.Sync.route) { SyncScreen(viewModel) }
                composable(Destination.Settings.route) { SettingsScreen(viewModel) }
                composable(Destination.Logs.route) { LogScreen() }
            }
        }
    }
}