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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
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

    const val EDITOR = "editor/{scriptId}?editAction={editAction}"
    fun editor(scriptId: String, action: String = EditorAction.NONE) =
        if (action.isBlank()) "editor/$scriptId" else "editor/$scriptId?editAction=$action"

    const val PACKAGE_EDITOR = "packageEditor/{packageId}"
    fun packageEditor(packageId: String) = "packageEditor/$packageId"

    const val RECORD = "record"
    const val CONSOLE = "console"
    const val PERMISSION = "permission"
}

/** 编辑器可直达的初始动作（由悬浮球控制台等入口传入）。 */
object EditorAction {
    /** 仅打开步骤列表。 */
    const val NONE = ""

    /** 打开后直接弹出「新建步骤组」。 */
    const val GROUP = "group"

    /** 打开后直接弹出「重命名任务」。 */
    const val RENAME = "rename"
}

/** 外部请求打开编辑器：指定任务 + 初始动作。 */
data class EditorRequest(val scriptId: String, val action: String = EditorAction.NONE)

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab(Routes.HOME, "首页", Icons.Filled.Home),
    Tab(Routes.PACKAGES, "函数包", Icons.Filled.Extension),
    Tab(Routes.TEMPLATES, "模板", Icons.Filled.Apps),
    Tab(Routes.SETTINGS, "我的", Icons.Filled.Person),
)

@Composable
fun AppNavigation(
    navController: NavHostController = rememberNavController(),
    editorRequest: EditorRequest? = null,
    onEditorRequestHandled: () -> Unit = {},
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = TABS.any { it.route == currentRoute }

    // 悬浮球控制台等外部入口请求打开编辑器时，直接导航到对应任务的编辑页。
    LaunchedEffect(editorRequest) {
        val request = editorRequest ?: return@LaunchedEffect
        navController.navigate(Routes.editor(request.scriptId, request.action)) {
            launchSingleTop = true
        }
        onEditorRequestHandled()
    }

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
            composable(
                route = Routes.EDITOR,
                arguments = listOf(
                    navArgument("scriptId") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                    navArgument("editAction") {
                        type = NavType.StringType
                        defaultValue = EditorAction.NONE
                    },
                ),
            ) { entry ->
                ScriptEditorScreen(
                    scriptId = entry.arguments?.getString("scriptId").orEmpty(),
                    initialAction = entry.arguments?.getString("editAction").orEmpty(),
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