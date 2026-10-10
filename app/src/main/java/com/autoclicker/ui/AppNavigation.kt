package com.autoclicker.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.autoclicker.ui.console.ConsoleScreen
import com.autoclicker.ui.editor.ScriptEditorScreen
import com.autoclicker.ui.home.HomeScreen
import com.autoclicker.ui.packages.PackageEditorScreen
import com.autoclicker.ui.packages.PackagesScreen
import com.autoclicker.ui.permission.PermissionScreen
import com.autoclicker.ui.record.RecordingScreen
import com.autoclicker.ui.settings.SettingsScreen
import com.autoclicker.ui.templates.TemplatesScreen

/** 路由常量。 */
object Routes {
    const val HOME = "home"
    const val PACKAGES = "packages"
    const val TEMPLATES = "templates"
    const val SETTINGS = "settings"

    const val EDITOR = "editor/{scriptId}"
    fun editor(scriptId: String) = "editor/$scriptId"

    const val PACKAGE_EDITOR = "packageEditor/{packageId}"
    fun packageEditor(packageId: String) = "packageEditor/$packageId"

    const val RECORD = "record"
    const val CONSOLE = "console"
    const val PERMISSION = "permission"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab(Routes.HOME, "首页", Icons.Filled.Home),
    Tab(Routes.PACKAGES, "函数包", Icons.Filled.Extension),
    Tab(Routes.TEMPLATES, "模板", Icons.Filled.Apps),
    Tab(Routes.SETTINGS, "我的", Icons.Filled.Person),
)

@Composable
fun AppNavigation(navController: NavHostController = rememberNavController()) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = TABS.any { it.route == currentRoute }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    TABS.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                if (currentRoute != tab.route) {
                                    navController.navigate(tab.route) {
                                        popUpTo(Routes.HOME) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    onOpenScript = { navController.navigate(Routes.editor(it)) },
                    onOpenRecord = { navController.navigate(Routes.RECORD) },
                    onOpenConsole = { navController.navigate(Routes.CONSOLE) },
                    onOpenPermissions = { navController.navigate(Routes.PERMISSION) },
                )
            }
            composable(Routes.PACKAGES) {
                PackagesScreen(
                    onOpenPackage = { navController.navigate(Routes.packageEditor(it)) },
                )
            }
            composable(Routes.TEMPLATES) {
                TemplatesScreen()
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onOpenPermissions = { navController.navigate(Routes.PERMISSION) },
                )
            }
            composable(Routes.EDITOR) { entry ->
                ScriptEditorScreen(
                    scriptId = entry.arguments?.getString("scriptId").orEmpty(),
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.PACKAGE_EDITOR) { entry ->
                PackageEditorScreen(
                    packageId = entry.arguments?.getString("packageId").orEmpty(),
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.RECORD) {
                RecordingScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.CONSOLE) {
                ConsoleScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.PERMISSION) {
                PermissionScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}