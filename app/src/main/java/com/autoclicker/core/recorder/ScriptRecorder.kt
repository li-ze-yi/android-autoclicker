package com.autoclicker.core.recorder

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import com.autoclicker.core.accessibility.NodeFinder
import com.autoclicker.core.script.Script
import com.autoclicker.core.script.Step
import com.autoclicker.core.script.newId
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 录制器：把无障碍事件（点击 / 滚动）转换为脚本步骤。
 * 由 [com.autoclicker.core.accessibility.AutoAccessService] 在事件回调中驱动。
 */
object ScriptRecorder {

    private const val SELF_PACKAGE = "com.autoclicker"
    private const val SWIPE_DURATION_MS = 300L

    /** 连续滚动的去抖动窗口。 */
    private const val SWIPE_DEBOUNCE_MS = 400L

    /** 滑动偏移量上下限（像素）。 */
    private const val OFFSET_MIN = 120f
    private const val OFFSET_MAX = 400f

    private val buffer = mutableListOf<Step>()
    private var recording = false
    private var lastSwipeAt = 0L

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _stepCount = MutableStateFlow(0)
    val stepCount: StateFlow<Int> = _stepCount.asStateFlow()

    /** 开始录制，清空已有缓冲。 */
    fun start() {
        buffer.clear()
        recording = true
        lastSwipeAt = 0L
        _isRecording.value = true
        _stepCount.value = 0
    }

    /** 结束录制并返回生成的脚本（不落盘）。未在录制返回 null，缓冲为空也返回 null。 */
    fun stop(): Script? {
        if (!recording) {
            return null
        }
        recording = false
        _isRecording.value = false

        if (buffer.isEmpty()) {
            _stepCount.value = 0
            return null
        }

        val name = "录制 " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        return Script(id = newId(), name = name, steps = buffer.toList())
    }

    /** 结束录制并丢弃结果。 */
    fun cancel() {
        recording = false
        buffer.clear()
        _isRecording.value = false
        _stepCount.value = 0
    }

    /** 无障碍事件入口。非录制状态、本应用自身事件或无关事件一律忽略。 */
    fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!recording) return
        if (event == null) return
        if (event.packageName?.toString() == SELF_PACKAGE) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> recordClick(event)
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> recordScroll(event)
        }
    }

    private fun recordClick(event: AccessibilityEvent) {
        val source = event.source ?: return
        if (!NodeFinder.isVisible(source)) return

        val center = NodeFinder.centerOf(source)
        buffer.add(
            Step.Tap(
                x = center.x,
                y = center.y,
                note = source.text?.toString() ?: ""
            )
        )
        _stepCount.value = buffer.size
    }

    private fun recordScroll(event: AccessibilityEvent) {
        // scrollDeltaX / scrollDeltaY 需要 API 28；更低版本忽略滚动事件。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return

        val source = event.source ?: return
        val rect = Rect().also { source.getBoundsInScreen(it) }
        if (rect.width() <= 0 || rect.height() <= 0) return

        val deltaY = event.scrollDeltaY
        val deltaX = event.scrollDeltaX
        val cx = rect.exactCenterX()
        val cy = rect.exactCenterY()

        val swipe: Step.Swipe = when {
            deltaY > 0f -> {
                // 内容下移（手指向上滑）：从下往上
                val offset = offsetFor(rect.height())
                Step.Swipe(
                    x1 = cx,
                    y1 = cy + offset,
                    x2 = cx,
                    y2 = cy - offset,
                    durationMs = SWIPE_DURATION_MS,
                    note = "竖直滚动"
                )
            }

            deltaY < 0f -> {
                val offset = offsetFor(rect.height())
                Step.Swipe(
                    x1 = cx,
                    y1 = cy - offset,
                    x2 = cx,
                    y2 = cy + offset,
                    durationMs = SWIPE_DURATION_MS,
                    note = "竖直滚动"
                )
            }

            deltaX > 0f -> {
                val offset = offsetFor(rect.width())
                Step.Swipe(
                    x1 = cx + offset,
                    y1 = cy,
                    x2 = cx - offset,
                    y2 = cy,
                    durationMs = SWIPE_DURATION_MS,
                    note = "水平滚动"
                )
            }

            deltaX < 0f -> {
                val offset = offsetFor(rect.width())
                Step.Swipe(
                    x1 = cx - offset,
                    y1 = cy,
                    x2 = cx + offset,
                    y2 = cy,
                    durationMs = SWIPE_DURATION_MS,
                    note = "水平滚动"
                )
            }

            else -> return
        }

        // 去抖动：紧邻上一次滑动且间隔过短时丢弃。
        val now = System.currentTimeMillis()
        val last = buffer.lastOrNull()
        if (last is Step.Swipe && now - lastSwipeAt < SWIPE_DEBOUNCE_MS) {
            return
        }

        buffer.add(swipe)
        lastSwipeAt = now
        _stepCount.value = buffer.size
    }

    /** 偏移量取区域长/宽的四分之一，并限制在 [120, 400] px。 */
    private fun offsetFor(extent: Int): Float {
        return (extent / 4f).coerceIn(OFFSET_MIN, OFFSET_MAX)
    }
}