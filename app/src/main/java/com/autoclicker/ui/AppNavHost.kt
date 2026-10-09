package com.autoclicker.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.autoclicker.ui.editor.EditorScreen
import com.autoclicker.ui.home.HomeScreen
import com.autoclicker.ui.permission.PermissionScreen
import com.autoclicker.ui.scripts.ScriptListScreen
import com.autoclicker.ui.vision.TemplateScreen

/**
 * 全局路由常量。后续新增页面时在此登记，避免散落的魔法字符串。
 */
object Routes {
    const val HOME = "home"
    const val SCRIPTS = "scripts"
    const val EDITOR = "editor/{scriptId}"
    const val PERMISSIONS = "permissions"
    const val VISION = "vision"

    const val ARG_SCRIPT_ID = "scriptId"

    fun editor(scriptId: String): String = "editor/$scriptId"
}

@Composable
fun AppNavHost(
    startRoute: String = Routes.HOME,
    navController: NavHostController = rememberNavController()
) {
    NavHost(
        navController = navController,
        startDestination = startRoute
    ) {
        composable(Routes.HOME) {
            HomeScreen(onNavigate = { route -> navController.navigate(route) })
        }

        composable(Routes.SCRIPTS) {
            ScriptListScreen(
                onOpenScript = { scriptId -> navController.navigate(Routes.editor(scriptId)) },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.EDITOR,
            arguments = listOf(navArgument(Routes.ARG_SCRIPT_ID) { type = NavType.StringType })
        ) { backStackEntry ->
            val scriptId = backStackEntry.arguments?.getString(Routes.ARG_SCRIPT_ID).orEmpty()
            EditorScreen(
                scriptId = scriptId,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.PERMISSIONS) {
            PermissionScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.VISION) {
            TemplateScreen(onBack = { navController.popBackStack() })
        }
    }
}