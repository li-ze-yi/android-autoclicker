package com.autoclicker.core.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 全局操作与文本输入。服务未连接或执行失败时返回 false，不抛出异常。
 */
object GlobalActions {

    /** 返回键。 */
    fun back(): Boolean = performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)

    /** Home 键。 */
    fun home(): Boolean = performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)

    /** 最近任务键。 */
    fun recents(): Boolean = performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)

    /** 向当前获得输入焦点的节点写入文本。 */
    suspend fun inputText(text: String): Boolean {
        val service = AutoAccessService.instance ?: return false
        return try {
            val focusNode = service.rootInActiveWindow
                ?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: return false
            val arguments = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text
                )
            }
            focusNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        } catch (e: Exception) {
            false
        }
    }

    private fun performGlobalAction(action: Int): Boolean {
        val service = AutoAccessService.instance ?: return false
        return try {
            service.performGlobalAction(action)
        } catch (e: Exception) {
            false
        }
    }
}