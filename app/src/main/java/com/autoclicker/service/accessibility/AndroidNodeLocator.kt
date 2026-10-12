package com.autoclicker.service.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.autoclicker.core.bus.LogLevel
import com.autoclicker.core.bus.RuntimeBus
import com.autoclicker.domain.model.NodeSelector
import com.autoclicker.domain.model.PixelRect
import com.autoclicker.platform.NodeInfo
import com.autoclicker.platform.NodeLocator

/**
 * 无障碍控件树查询：遍历当前所有窗口与活动窗口根节点，按 [NodeSelector] 匹配。
 * 命中集合按点击优先级排序（可点击优先，其次更小区域，再按自上而下）。
 */
class AndroidNodeLocator(
    private val serviceProvider: () -> AccessibilityService?,
) : NodeLocator {

    override fun isReady(): Boolean = serviceProvider() != null

    override suspend fun findNodes(selector: NodeSelector): List<NodeInfo> {
        val matched = collectInfos { matches(it, selector) }
        // 排序依据全部来自 NodeInfo，遍历结束后不再持有任何无障碍节点对象。
        return matched.sortedWith(
            compareByDescending<NodeInfo> { it.clickable }
                .thenBy { it.bounds.width * it.bounds.height }
                .thenBy { it.bounds.t },
        )
    }

    override suspend fun readAllText(): List<NodeInfo> =
        collectInfos { !it.text.isNullOrBlank() }

    override suspend fun setText(selector: NodeSelector, text: String, clearFirst: Boolean): Boolean {
        if (serviceProvider() == null) return false
        // 只遍历一次：拿到全部可编辑控件后按「匹配选择器 → 已聚焦 → 第一个」优先挑选。
        val editables = collect({ it.isEditable }, { it })
        val target = editables.firstOrNull { matches(it, selector) }
            ?: editables.firstOrNull { it.isFocused }
            ?: editables.firstOrNull()
            ?: return false
        return try {
            target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            if (clearFirst) target.performAction(AccessibilityNodeInfo.ACTION_CLEAR_TEXT)
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (t: Throwable) {
            false
        }
    }

    private fun matches(node: AccessibilityNodeInfo, selector: NodeSelector): Boolean {
        if (selector.clickableOnly && !node.isClickable) return false

        val text = selector.text
        if (!text.isNullOrEmpty()) {
            val nodeText = node.text?.toString() ?: return false
            if (!nodeText.contains(text, ignoreCase = true)) return false
        }

        val viewId = selector.viewId
        if (!viewId.isNullOrEmpty()) {
            val resourceName = node.viewIdResourceName ?: return false
            val shortId = resourceName.substringAfterLast('/')
            if (resourceName != viewId && shortId != viewId) return false
        }

        val className = selector.className
        if (!className.isNullOrEmpty()) {
            val nodeClass = node.className?.toString() ?: return false
            if (nodeClass != className) return false
        }

        val contentDesc = selector.contentDesc
        if (!contentDesc.isNullOrEmpty()) {
            val nodeDesc = node.contentDescription?.toString() ?: return false
            if (!nodeDesc.contains(contentDesc, ignoreCase = true)) return false
        }

        return true
    }

    /** 命中项当场转成轻量 [NodeInfo]。 */
    private fun collectInfos(predicate: (AccessibilityNodeInfo) -> Boolean): List<NodeInfo> =
        collect(predicate) { toInfo(it, boundsOf(it)) }

    /**
     * 广度优先遍历控件树，命中项经 [transform] 转换后收集。
     * 调用方若只需转换后的结果（如 [NodeInfo]），遍历结束就不再持有 [AccessibilityNodeInfo]，
     * 避免复杂界面下同时驻留数千个节点对象抬高堆占用。
     */
    private fun <T> collect(
        predicate: (AccessibilityNodeInfo) -> Boolean,
        transform: (AccessibilityNodeInfo) -> T,
    ): List<T> {
        val service = serviceProvider() ?: return emptyList()
        val result = ArrayList<T>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val roots = LinkedHashSet<AccessibilityNodeInfo>()
        try {
            service.rootInActiveWindow?.let { roots.add(it) }
            service.windows.forEach { window -> window.root?.let { roots.add(it) } }
        } catch (t: Throwable) {
            // 窗口枚举失败时退回活动窗口（可能为空）。
        }
        queue.addAll(roots)

        var visited = 0
        while (queue.isNotEmpty()) {
            if (visited >= MAX_NODES) {
                RuntimeBus.log(LogLevel.WARN, "控件树节点超过 $MAX_NODES，本次遍历已截断")
                break
            }
            val node = queue.removeFirst()
            visited++
            if (predicate(node)) result.add(transform(node))
            val childCount = node.childCount
            for (i in 0 until childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return result
    }

    private fun boundsOf(node: AccessibilityNodeInfo): Rect {
        val rect = Rect()
        try {
            node.getBoundsInScreen(rect)
        } catch (t: Throwable) {
            // 保持空矩形。
        }
        return rect
    }

    private fun toInfo(node: AccessibilityNodeInfo, rect: Rect): NodeInfo = NodeInfo(
        text = node.text?.toString(),
        viewId = node.viewIdResourceName,
        className = node.className?.toString(),
        contentDesc = node.contentDescription?.toString(),
        bounds = PixelRect(rect.left, rect.top, rect.right, rect.bottom),
        clickable = node.isClickable,
    )

    private companion object {
        const val MAX_NODES = 8000
    }
}