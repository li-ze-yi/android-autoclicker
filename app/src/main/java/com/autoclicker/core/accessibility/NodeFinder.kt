package com.autoclicker.core.accessibility

import android.graphics.PointF
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs
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
    @Suppress("DEPRECATION")
    fun findNode(selector: NodeSelector): AccessibilityNodeInfo? {
        val root = AutoAccessService.instance?.rootInActiveWindow ?: return null
        val matches = ArrayList<AccessibilityNodeInfo>()
        // 遍历过程中取到的全部节点，函数结束时统一回收，避免无障碍节点池泄漏。
        val traversed = ArrayList<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        var visited = 0
        try {
            queue.addLast(root)
            while (queue.isNotEmpty() && visited < MAX_NODES) {
                val node = queue.removeFirst()
                traversed.add(node)
                visited++
                if (matchesSelector(selector, node)) {
                    // 保留匹配节点：拷贝独立实例，遍历用节点可安全回收。
                    matches.add(AccessibilityNodeInfo.obtain(node))
                }
                val childCount = node.childCount
                for (i in 0 until childCount) {
                    node.getChild(i)?.let { queue.addLast(it) }
                }
            }

            val result = if (selector.index in matches.indices) matches[selector.index] else null
            // 只保留要返回的匹配项，其余匹配副本回收。
            for (node in matches) {
                if (node !== result) {
                    recycleQuietly(node)
                }
            }
            return result
        } finally {
            for (node in traversed) {
                recycleQuietly(node)
            }
            for (node in queue) {
                recycleQuietly(node)
            }
        }
    }

    /** 安全回收无障碍节点，重复回收或已回收时忽略异常。 */
    @Suppress("DEPRECATION")
    fun recycleQuietly(node: AccessibilityNodeInfo) {
        try {
            node.recycle()
        } catch (e: Exception) {
            // 忽略回收异常
        }
    }

    /**
     * 按包围盒查找控件（P1）。返回与给定矩形最匹配的可见节点；匹配度过低返回 null。
     *
     * 匹配规则：四边差值都在 [tolerancePx] 内视为完全命中；否则用交并比（IoU），低于 0.5 视为未命中。
     * 返回的节点是独立副本，调用方用后需自行 recycle。
     */
    @Suppress("DEPRECATION")
    fun findByBounds(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        tolerancePx: Int = 24
    ): AccessibilityNodeInfo? {
        val root = AutoAccessService.instance?.rootInActiveWindow ?: return null
        val target = Rect(left, top, right, bottom)
        if (target.width() <= 0 || target.height() <= 0) return null

        var best: AccessibilityNodeInfo? = null
        var bestScore = 0f
        val traversed = ArrayList<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val candidateRect = Rect()
        var visited = 0
        try {
            queue.addLast(root)
            while (queue.isNotEmpty() && visited < MAX_NODES) {
                val node = queue.removeFirst()
                traversed.add(node)
                visited++
                if (node.isVisibleToUser) {
                    node.getBoundsInScreen(candidateRect)
                    if (candidateRect.width() > 0 && candidateRect.height() > 0) {
                        val score = boundsScore(target, candidateRect, tolerancePx)
                        if (score > bestScore) {
                            bestScore = score
                            best?.let { recycleQuietly(it) }
                            best = AccessibilityNodeInfo.obtain(node)
                        }
                    }
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.addLast(it) }
                }
            }
        } finally {
            for (node in traversed) recycleQuietly(node)
            for (node in queue) recycleQuietly(node)
        }

        if (bestScore < 0.5f) {
            best?.let { recycleQuietly(it) }
            return null
        }
        return best
    }

    private fun boundsScore(target: Rect, candidate: Rect, tolerancePx: Int): Float {
        val near = abs(target.left - candidate.left) <= tolerancePx &&
            abs(target.top - candidate.top) <= tolerancePx &&
            abs(target.right - candidate.right) <= tolerancePx &&
            abs(target.bottom - candidate.bottom) <= tolerancePx
        if (near) return 1f

        val iw = minOf(target.right, candidate.right) - maxOf(target.left, candidate.left)
        val ih = minOf(target.bottom, candidate.bottom) - maxOf(target.top, candidate.top)
        if (iw <= 0 || ih <= 0) return 0f
        val inter = iw.toLong() * ih
        val union = target.width().toLong() * target.height() +
            candidate.width().toLong() * candidate.height() - inter
        return if (union <= 0L) 0f else inter.toFloat() / union
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