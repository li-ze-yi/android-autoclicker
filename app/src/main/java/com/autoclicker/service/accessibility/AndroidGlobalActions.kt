package com.autoclicker.service.accessibility

import android.accessibilityservice.AccessibilityService
import com.autoclicker.core.engine.GlobalActions

/**
 * 基于无障碍服务的 [GlobalActions] 实现：performGlobalAction。
 */
class AndroidGlobalActions : GlobalActions {

    override suspend fun goHome(): Boolean =
        perform(AccessibilityService.GLOBAL_ACTION_HOME)

    override suspend fun goBack(): Boolean =
        perform(AccessibilityService.GLOBAL_ACTION_BACK)

    private fun perform(action: Int): Boolean {
        val service = AccessibilityServiceHolder.get() ?: return false
        return runCatching { service.performGlobalAction(action) }.getOrDefault(false)
    }
}
