package com.autoclicker.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.autoclicker.ui.home.HomeScreen
import com.autoclicker.ui.permission.PermissionScreen

/**
 * 应用导航骨架：底部导航（首页/脚本库/函数包/权限）+ NavHost。
 * 未实现的页面先显示占位，后续任务接入。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    Scaffold(
        bottomBar = {
            NavigationBar {
                val backStack by navController.currentBackStackEntryAsState()
                val current = backStack?.destination
                TopDestination.entries.forEach { dest ->
                    NavigationBarItem(
                        selected = current?.hierarchy?.any { it.route == dest.route } == true,
                        onClick = {
                            navController.navigate(dest.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(dest.icon, contentDescription = dest.label) },
                        label = { Text(dest.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    onNavigateToPermissions = {
                        navController.navigate(Routes.PERMISSIONS) {
                            launchSingleTop = true
                        }
                    },
                    onNavigateToRecording = {
                        navController.navigate(Routes.RECORD) {
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable(Routes.SCRIPTS) {
                Placeholder("脚本库")
            }
            composable(Routes.PACKAGES) {
                com.autoclicker.ui.packages.PackagesScreen()
            }
            composable(Routes.PERMISSIONS) {
                PermissionScreen()
            }
            composable(Routes.RECORD) {
                com.autoclicker.ui.record.RecordingScreen(
                    onFinished = {
                        navController.popBackStack(Routes.HOME, inclusive = false)
                    },
                )
            }
        }
    }
}

/** 占位页面 */
@Composable
private fun Placeholder(title: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("$title · 即将上线")
    }
}
