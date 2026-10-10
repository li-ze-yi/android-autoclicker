package com.autoclicker.service.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
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
        val matched = collectNodes { matches(it, selector) }
        val withBounds = matched.map { it to boundsOf(it) }
        val ordered = withBounds.sortedWith(
            compareByDescending<Pair<AccessibilityNodeInfo, Rect>> { it.first.isClickable }
                .thenBy { it.second.width() * it.second.height() }
                .thenBy { it.second.top },
        )
        return ordered.map { toInfo(it.first, it.second) }
    }

    override suspend fun readAllText(): List<NodeInfo> {
        val matched = collectNodes { !it.text.isNullOrBlank() }
        return matched.map { toInfo(it, boundsOf(it)) }
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

    private fun collectNodes(predicate: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val service = serviceProvider() ?: return emptyList()
        val result = ArrayList<AccessibilityNodeInfo>()
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
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val node = queue.removeFirst()
            visited++
            if (predicate(node)) result.add(node)
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