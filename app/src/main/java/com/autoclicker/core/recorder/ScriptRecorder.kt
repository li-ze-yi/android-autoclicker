package com.autoclicker.core.recorder

import android.graphics.Rect
import android.os.Build
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent
import com.autoclicker.core.accessibility.NodeFinder
import com.autoclicker.core.script.Script
import com.autoclicker.core.script.Step
import com.autoclicker.core.script.newId
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.hypot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 录制器：把用户操作转换为脚本步骤。
 *
 * 当前生效的是无障碍事件路径（[onAccessibilityEvent]）：点击/长按取控件包围盒中心作为坐标，
 * 滚动换算为近似滑动，并用系统时钟测量动作之间的间隔写入下一步的 `delayBeforeMs`。
 * [onRawTouch] 为原始触点入口，当前无调用方（`AccessibilityService.onMotionEvent` 不在公开 SDK 中，
 * 无法在第三方 App 内覆盖），保留以备后续接入。
 */
object ScriptRecorder {

    private const val SELF_PACKAGE = "com.autoclicker"

    /** 触点移动小于该距离（像素）视为未滑动。 */
    private const val TAP_SLOP_PX = 20.0

    /** 按住超过该时长（毫秒）视为长按。 */
    private const val LONG_PRESS_MS = 500L

    /** 步骤间延时的上限与忽略门限（毫秒）。 */
    private const val MAX_DELAY_MS = 60000L
    private const val MIN_DELAY_MS = 50L

    /** 回退路径下滑动的默认时长与连续滚动去抖窗口（毫秒）。 */
    private const val SWIPE_DURATION_MS = 300L
    private const val SWIPE_DEBOUNCE_MS = 400L

    /** 回退路径下长按默认时长（毫秒）。 */
    private const val LONG_CLICK_DURATION_MS = 800L

    /** 滑动偏移量上下限（像素）。 */
    private const val OFFSET_MIN = 120f
    private const val OFFSET_MAX = 400f

    private val buffer = mutableListOf<Step>()
    private var recording = false

    // 原始触点状态。
    private var tracking = false
    private var skipCurrent = false
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downTimeMs = 0L
    private var lastGestureEndMs = 0L
    private var pendingDelayMs = 0L

    // 回退路径状态。
    private var lastEventEndMs = 0L
    private var lastSwipeAt = 0L

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _stepCount = MutableStateFlow(0)
    val stepCount: StateFlow<Int> = _stepCount.asStateFlow()

    /**
     * 悬浮球/面板的屏幕矩形。录制时落在其中的触点要忽略，避免录到自己的悬浮窗。
     * 由 OverlayService 设置。
     */
    @Volatile
    var ignoredRegion: Rect? = null

    /** 开始录制，清空已有缓冲并重置全部状态（不清空 [ignoredRegion]）。 */
    fun start() {
        buffer.clear()
        recording = true
        tracking = false
        skipCurrent = false
        lastGestureEndMs = 0L
        lastEventEndMs = 0L
        lastSwipeAt = 0L
        _isRecording.value = true
        _stepCount.value = 0
    }

    /** 结束录制并返回生成的脚本（不落盘）；未在录制返回 null；缓冲为空返回 null。 */
    fun stop(): Script? {
        if (!recording) {
            return null
        }
        recording = false
        tracking = false
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
        tracking = false
        skipCurrent = false
        lastGestureEndMs = 0L
        lastEventEndMs = 0L
        lastSwipeAt = 0L
        _isRecording.value = false
        _stepCount.value = 0
    }

    /** 原始触点入口。当前无调用方，保留备用（见类注释）。 */
    fun onRawTouch(event: MotionEvent) {
        if (!recording) return

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 多指手势整体跳过。
                if (event.pointerCount > 1) {
                    skipCurrent = true
                    tracking = false
                    return
                }
                downX = event.rawX
                downY = event.rawY
                lastX = downX
                lastY = downY
                downTimeMs = event.eventTime
                pendingDelayMs = if (lastGestureEndMs > 0L) {
                    (event.eventTime - lastGestureEndMs).coerceIn(0L, MAX_DELAY_MS)
                } else {
                    0L
                }
                skipCurrent = ignoredRegion?.contains(downX.toInt(), downY.toInt()) == true
                tracking = true
            }

            MotionEvent.ACTION_MOVE -> {
                if (tracking) {
                    lastX = event.rawX
                    lastY = event.rawY
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                skipCurrent = true
            }

            MotionEvent.ACTION_UP -> {
                if (!tracking) return
                tracking = false
                lastGestureEndMs = event.eventTime

                if (skipCurrent) {
                    skipCurrent = false
                    return
                }

                val upX = event.rawX
                val upY = event.rawY
                val distance = hypot((upX - downX).toDouble(), (upY - downY).toDouble())
                val duration = (event.eventTime - downTimeMs).coerceAtLeast(1L)

                when {
                    distance < TAP_SLOP_PX && duration >= LONG_PRESS_MS ->
                        append(
                            Step.LongPress(
                                delayBeforeMs = pendingDelayMs,
                                x = downX,
                                y = downY,
                                durationMs = duration
                            )
                        )

                    distance < TAP_SLOP_PX ->
                        append(Step.Tap(delayBeforeMs = pendingDelayMs, x = downX, y = downY))

                    else ->
                        append(
                            Step.Swipe(
                                delayBeforeMs = pendingDelayMs,
                                x1 = downX,
                                y1 = downY,
                                x2 = upX,
                                y2 = upY,
                                durationMs = duration
                            )
                        )
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                // 取消不更新 lastGestureEndMs，不计入间隔。
                tracking = false
                skipCurrent = false
            }
        }
    }

    /**
     * 无障碍事件入口：录制点击、长按与滚动，并测量与上一次动作的间隔。
     */
    fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!recording) return
        if (event == null) return
        if (event.packageName?.toString() == SELF_PACKAGE) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> recordClick(event)
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> recordLongClick(event)
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> recordScroll(event)
        }
    }

    private fun append(step: Step) {
        buffer.add(step)
        _stepCount.value = buffer.size
    }

    /** 计算与上一步产出时间的间隔（毫秒），并刷新 lastEventEndMs。 */
    private fun delayFromLastEvent(): Long {
        val now = System.currentTimeMillis()
        val gap = if (lastEventEndMs > 0L) {
            val delta = (now - lastEventEndMs).coerceIn(0L, MAX_DELAY_MS)
            if (delta < MIN_DELAY_MS) 0L else delta
        } else {
            0L
        }
        lastEventEndMs = now
        return gap
    }

    private fun recordClick(event: AccessibilityEvent) {
        val source = event.source ?: return
        if (!NodeFinder.isVisible(source)) return

        val center = NodeFinder.centerOf(source)
        val gap = delayFromLastEvent()
        append(
            Step.Tap(
                delayBeforeMs = gap,
                x = center.x,
                y = center.y,
                note = source.text?.toString() ?: ""
            )
        )
    }

    private fun recordLongClick(event: AccessibilityEvent) {
        val source = event.source ?: return
        if (!NodeFinder.isVisible(source)) return

        val center = NodeFinder.centerOf(source)
        val gap = delayFromLastEvent()
        append(
            Step.LongPress(
                delayBeforeMs = gap,
                x = center.x,
                y = center.y,
                durationMs = LONG_CLICK_DURATION_MS,
                note = source.text?.toString() ?: ""
            )
        )
    }

    private fun recordScroll(event: AccessibilityEvent) {
        // scrollDeltaX / scrollDeltaY 需要 API 28；更低版本忽略滚动事件。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return

        val source = event.source ?: return
        val rect = Rect().also { source.getBoundsInScreen(it) }
        if (rect.width() <= 0 || rect.height() <= 0) return

        // 去抖动：紧邻上一次滑动且间隔过短时丢弃。
        val now = System.currentTimeMillis()
        val last = buffer.lastOrNull()
        if (last is Step.Swipe && now - lastSwipeAt < SWIPE_DEBOUNCE_MS) {
            return
        }

        val deltaY = event.scrollDeltaY
        val deltaX = event.scrollDeltaX
        val cx = rect.exactCenterX()
        val cy = rect.exactCenterY()
        val gap = delayFromLastEvent()

        val swipe: Step.Swipe = when {
            deltaY > 0f -> {
                // 内容下移（手指向上滑）：从下往上。
                val offset = offsetFor(rect.height())
                Step.Swipe(
                    delayBeforeMs = gap,
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
                    delayBeforeMs = gap,
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
                    delayBeforeMs = gap,
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
                    delayBeforeMs = gap,
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

        append(swipe)
        lastSwipeAt = now
    }

    /** 偏移量取区域长/宽的四分之一，并限制在 [120, 400] px。 */
    private fun offsetFor(extent: Int): Float {
        return (extent / 4f).coerceIn(OFFSET_MIN, OFFSET_MAX)
    }
}