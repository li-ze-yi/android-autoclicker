package com.autoclicker.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 底部导航目的地。
 */
enum class TopDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    HOME("home", "首页", Icons.Filled.Home),
    SCRIPTS("scripts", "脚本库", Icons.AutoMirrored.Filled.List),
    PACKAGES("packages", "函数包", Icons.Filled.TouchApp),
    PERMISSIONS("permissions", "权限", Icons.Filled.Shield),
}

/** 各页面路由常量 */
object Routes {
    const val HOME = "home"
    const val SCRIPTS = "scripts"
    const val PACKAGES = "packages"
    const val PERMISSIONS = "permissions"
    const val RECORD = "record"
    const val VISION_TEMPLATES = "vision_templates"
    const val SCRIPT_EDITOR = "script_editor?scriptId={scriptId}"
    fun scriptEditor(scriptId: String) = "script_editor?scriptId=$scriptId"
}
