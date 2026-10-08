package com.autoclicker.core.accessibility

import android.graphics.PointF
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay

/**
 * 节点查找条件。为 null 的字段表示不参与过滤；index 表示取第几个匹配项（从 0 开始）。
 */
data class NodeSelector(
    val text: String? = null,
    val viewId: String? = null,
    val contentDesc: String? = null,
    val className: String? = null,
    val index: Int = 0
)

/**
 * 无障碍节点查找工具：遍历活动窗口节点树进行匹配。
 */
object NodeFinder {

    /** BFS 遍历的最大节点数，防止节点过多导致卡死。 */
    private const val MAX_NODES = 3000

    /** 在当前活动窗口中查找匹配的节点，未找到返回 null。 */
    fun findNode(selector: NodeSelector): AccessibilityNodeInfo? {
        val root = AutoAccessService.instance?.rootInActiveWindow ?: return null
        val matches = ArrayList<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        var visited = 0
        queue.addLast(root)
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val node = queue.removeFirst()
            visited++
            if (matchesSelector(selector, node)) {
                matches.add(node)
            }
            val childCount = node.childCount
            for (i in 0 until childCount) {
                val child = node.getChild(i) ?: continue
                queue.addLast(child)
            }
        }
        if (selector.index < 0 || selector.index >= matches.size) {
            return null
        }
        return matches[selector.index]
    }

    /** 轮询查找节点，直到超时返回 null。 */
    suspend fun awaitNode(
        selector: NodeSelector,
        timeoutMs: Long = 10000L,
        pollIntervalMs: Long = 200L
    ): AccessibilityNodeInfo? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val node = findNode(selector)
            if (node != null) {
                return node
            }
            if (System.currentTimeMillis() >= deadline) {
                return null
            }
            delay(pollIntervalMs)
        }
    }

    /** 返回节点在屏幕上的包围矩形中心点。 */
    fun centerOf(node: AccessibilityNodeInfo): PointF {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return PointF(rect.exactCenterX(), rect.exactCenterY())
    }

    /** 节点是否对用户可见（可见且包围矩形宽高均大于 0）。 */
    fun isVisible(node: AccessibilityNodeInfo): Boolean {
        if (!node.isVisibleToUser) {
            return false
        }
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return rect.width() > 0 && rect.height() > 0
    }

    private fun matchesSelector(selector: NodeSelector, node: AccessibilityNodeInfo): Boolean {
        selector.text?.let { value ->
            val nodeText = node.text?.toString()
            if (nodeText == null || !nodeText.contains(value)) {
                return false
            }
        }
        selector.viewId?.let { value ->
            if (node.viewIdResourceName != value) {
                return false
            }
        }
        selector.contentDesc?.let { value ->
            val desc = node.contentDescription?.toString()
            if (desc == null || !desc.contains(value)) {
                return false
            }
        }
        selector.className?.let { value ->
            if (node.className?.toString() != value) {
                return false
            }
        }
        return true
    }
}