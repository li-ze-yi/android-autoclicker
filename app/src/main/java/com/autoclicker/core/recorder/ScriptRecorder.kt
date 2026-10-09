package com.autoclicker.core.recorder

import android.graphics.Rect
import android.os.Build
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.autoclicker.core.accessibility.GestureExecutor
import com.autoclicker.core.accessibility.NodeFinder
import com.autoclicker.core.script.Script
import com.autoclicker.core.script.Step
import com.autoclicker.core.script.newId
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.hypot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 录制器：把用户操作转换为脚本步骤。
 *
 * 默认走无障碍事件路径（[onAccessibilityEvent]）：点击/长按取控件包围盒中心作为坐标，
 * 滚动换算为近似滑动，并用系统时钟测量动作之间的间隔写入下一步的 `delayBeforeMs`。
 * [onRawTouch] 为原始触点入口，当前无调用方（`AccessibilityService.onMotionEvent` 不在公开 SDK 中，
 * 无法在第三方 App 内覆盖），保留以备后续接入。
 *
 * 精确录制模式（[preciseMode]）下改为走采集层路径（[onCaptureTouch]）：由覆盖在屏幕上的全屏可触摸
 * 采集层把触摸事件吞下来，逐点精确记录，并在抬手后回放同款手势给下方的目标 App。此模式下
 * [onAccessibilityEvent] 不再录入，避免同一动作被双路径重复记录。
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

    /** 回放手势的时长下限（毫秒）：过短的时长在部分设备上会被系统手势识别丢弃。 */
    private const val REPLAY_MIN_DURATION_MS = 16L

    /**
     * 回放专用协程作用域。
     *
     * 回放调用 [GestureExecutor] 的挂起函数（内部需要等待系统手势回调），必须异步执行，
     * 否则会阻塞采集层的触摸回调（在主线程分发），导致掉帧甚至 ANR。
     * 用 [SupervisorJob] 让单次回放失败不影响后续回放。
     */
    private val replayScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * 采集层触摸透传控制器。回放注入手势前把它置为 true（采集层临时不可触摸，手势透传给目标 App），
     * 回放结束后置回 false。若不透传，`dispatchGesture` 注入的手势会落在采集层上被再次录入，
     * 形成"录制→回放→再录制"的自我循环。由 OverlayService 在显示/隐藏采集层时设置，参数在主线程生效。
     */
    @Volatile
    var capturePassthrough: ((Boolean) -> Unit)? = null

    /** 正在回放的手势计数：只有从 0→1 才开启透传、1→0 才关闭，避免并发回放提前恢复。 */
    private val replayInFlight = AtomicInteger(0)

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

    private val _preciseMode = MutableStateFlow(false)

    /**
     * 是否启用精确录制模式。默认 false，保持既有的无障碍事件录制行为不变。
     * 启用后录制改由采集层（[onCaptureTouch]）负责，且 [onAccessibilityEvent] 不再录入。
     */
    val preciseMode: StateFlow<Boolean> = _preciseMode.asStateFlow()

    private val _recordPaused = MutableStateFlow(false)

    /**
     * 录制是否处于暂停。精确模式下用于打字等无法回放的场景：暂停期间采集层的触摸既不记录也不回放，
     * 直接放行；这样用户仍能正常操作目标 App，只是这些动作不会被录进脚本。
     */
    val recordPaused: StateFlow<Boolean> = _recordPaused.asStateFlow()

    /**
     * 悬浮球/面板的屏幕矩形。录制时落在其中的触点要忽略，避免录到自己的悬浮窗。
     * 由 OverlayService 设置。
     */
    @Volatile
    var ignoredRegion: Rect? = null

    /**
     * 设置是否启用精确录制模式。由用户选择决定，切换时不影响进行中的录制缓冲与手势状态。
     */
    fun setPreciseMode(enabled: Boolean) {
        _preciseMode.value = enabled
    }

    /**
     * 设置录制暂停状态。暂停时采集层手势既不记录也不回放；恢复前会重置进行中的手势状态，
     * 避免暂停期间残留的半程手势（如下按时被暂停、抬手时被恢复）在恢复后被误判成一次点击。
     */
    fun setRecordPaused(paused: Boolean) {
        if (paused) {
            resetGestureState()
        }
        _recordPaused.value = paused
    }

    /** 开始录制，清空已有缓冲并重置全部状态（不清空 [ignoredRegion]）。 */
    fun start() {
        buffer.clear()
        recording = true
        resetGestureState()
        lastEventEndMs = 0L
        lastSwipeAt = 0L
        _recordPaused.value = false
        _isRecording.value = true
        _stepCount.value = 0
    }

    /** 结束录制并返回生成的脚本（不落盘）；未在录制返回 null；缓冲为空返回 null。 */
    fun stop(): Script? {
        if (!recording) {
            return null
        }
        recording = false
        resetGestureState()
        _recordPaused.value = false
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
        resetGestureState()
        lastEventEndMs = 0L
        lastSwipeAt = 0L
        _recordPaused.value = false
        _isRecording.value = false
        _stepCount.value = 0
    }

    /** 重置原始/采集触点状态机，避免残留的半程手势影响下一次记录或回放。 */
    private fun resetGestureState() {
        tracking = false
        skipCurrent = false
        lastGestureEndMs = 0L
        pendingDelayMs = 0L
    }

    /** 原始触点入口。当前无调用方，保留备用（见类注释）；不触发回放。 */
    fun onRawTouch(event: MotionEvent) {
        if (!recording) return
        handleTouch(event, replay = false)
    }

    /**
     * 采集层触摸入口：记录手势（含与上一动作的间隔），并在抬手后回放给目标 App。
     *
     * 由覆盖在屏幕上的全屏可触摸采集层调用。未在录制、已暂停或落在 [ignoredRegion] 内的手势
     * 既不记录也不回放。记录与回放共用 [handleTouch] 状态机，保证判定与坐标一致。
     */
    fun onCaptureTouch(event: MotionEvent) {
        if (!recording) return
        if (_recordPaused.value) return
        handleTouch(event, replay = true)
    }

    /**
     * 触点状态机（原始触点与采集层共用）。
     *
     * 阈值 [TAP_SLOP_PX] 判定点按/滑动，[LONG_PRESS_MS] 判定长按；多指手势与 [ignoredRegion] 内的
     * 触点整体跳过；动作间隔取 `event.eventTime - lastGestureEndMs` 并上限 [MAX_DELAY_MS]。
     * [replay] 为 true 时（采集层路径）在抬手完成分类后，异步把同款手势回放给下方目标 App。
     */
    private fun handleTouch(event: MotionEvent, replay: Boolean) {
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
                    distance < TAP_SLOP_PX && duration >= LONG_PRESS_MS -> {
                        append(
                            Step.LongPress(
                                delayBeforeMs = pendingDelayMs,
                                x = downX,
                                y = downY,
                                durationMs = duration
                            )
                        )
                        if (replay) replayTapLike(downX, downY, duration)
                    }

                    distance < TAP_SLOP_PX -> {
                        append(Step.Tap(delayBeforeMs = pendingDelayMs, x = downX, y = downY))
                        if (replay) replayTapLike(downX, downY, duration)
                    }

                    else -> {
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
                        if (replay) replaySwipe(downX, downY, upX, upY, duration)
                    }
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
     * 异步回放一次点按类手势（点击与长按共用）。
     *
     * 用 [GestureExecutor.longPress] 而非 [GestureExecutor.click]：`click` 固定 50ms，无法还原用户
     * 实际按住的时长（含长按），而 `longPress` 可指定任意时长，等价于"按住 duration 毫秒"，手感与
     * 记录到的 [Step] 一致。起点取按下点 (x, y)，与步骤里记录的坐标保持同一基准。
     *
     * 回放放在 [replayScope] 里异步执行，避免同步等待系统手势回调阻塞采集层触摸分发（主线程）。
     * 回放失败（返回 false）不影响录制，此处直接忽略返回值。
     */
    private fun replayTapLike(x: Float, y: Float, durationMs: Long) {
        val safe = durationMs.coerceIn(REPLAY_MIN_DURATION_MS, MAX_DELAY_MS)
        replayScope.launch {
            try {
                acquireCapturePassthrough()
                GestureExecutor.longPress(x = x, y = y, durationMs = safe)
            } catch (e: Exception) {
                // 回放失败不影响录制
            } finally {
                releaseCapturePassthrough()
            }
        }
    }

    /**
     * 异步回放一次滑动。[GestureExecutor.swipe] 同样放到 [replayScope] 中执行，理由见 [replayTapLike]。
     */
    private fun replaySwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        val safe = durationMs.coerceIn(REPLAY_MIN_DURATION_MS, MAX_DELAY_MS)
        replayScope.launch {
            try {
                acquireCapturePassthrough()
                GestureExecutor.swipe(x1 = x1, y1 = y1, x2 = x2, y2 = y2, durationMs = safe)
            } catch (e: Exception) {
                // 回放失败不影响录制
            } finally {
                releaseCapturePassthrough()
            }
        }
    }

    /** 第一个回放开始前开启采集层透传；控制器在主线程执行。 */
    private suspend fun acquireCapturePassthrough() {
        if (replayInFlight.incrementAndGet() == 1) {
            applyCapturePassthrough(true)
        }
    }

    /** 最后一个回放结束后关闭采集层透传，恢复正常录制态。 */
    private suspend fun releaseCapturePassthrough() {
        if (replayInFlight.decrementAndGet() == 0) {
            applyCapturePassthrough(false)
        }
    }

    /** 在主线程把透传状态交给控制器；采集层已销毁等异常一律忽略。 */
    private suspend fun applyCapturePassthrough(passthrough: Boolean) {
        val controller = capturePassthrough ?: return
        try {
            withContext(Dispatchers.Main) { controller(passthrough) }
        } catch (e: Exception) {
            // 采集层窗口可能已销毁，忽略
        }
    }

    /**
     * 无障碍事件入口：录制点击、长按与滚动，并测量与上一次动作的间隔。
     *
     * 精确模式（[preciseMode]）下直接返回：此模式由采集层路径负责录入，若继续处理无障碍事件
     * 会把同一次动作重复记入脚本。
     */
    fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (preciseMode.value) return
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
        try {
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
        } finally {
            recycleQuietly(source)
        }
    }

    private fun recordLongClick(event: AccessibilityEvent) {
        val source = event.source ?: return
        try {
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
        } finally {
            recycleQuietly(source)
        }
    }

    private fun recordScroll(event: AccessibilityEvent) {
        // scrollDeltaX / scrollDeltaY 需要 API 28；更低版本忽略滚动事件。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return

        val source = event.source ?: return
        try {
            recordScrollFrom(event, source)
        } finally {
            recycleQuietly(source)
        }
    }

    private fun recordScrollFrom(event: AccessibilityEvent, source: AccessibilityNodeInfo) {
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

    /** 安全回收无障碍节点：重复回收或框架已回收时忽略异常。 */
    @Suppress("DEPRECATION")
    private fun recycleQuietly(node: AccessibilityNodeInfo) {
        try {
            node.recycle()
        } catch (e: Exception) {
            // 忽略回收异常
        }
    }

    /** 偏移量取区域长/宽的四分之一，并限制在 [120, 400] px。 */
    private fun offsetFor(extent: Int): Float {
        return (extent / 4f).coerceIn(OFFSET_MIN, OFFSET_MAX)
    }
}