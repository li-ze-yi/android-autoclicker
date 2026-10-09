package com.autoclicker.core.recorder

import android.graphics.Rect
import android.os.Build
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.autoclicker.core.accessibility.GestureExecutor
import com.autoclicker.core.accessibility.NodeFinder
import com.autoclicker.core.script.GesturePoint
import com.autoclicker.core.script.OnTimeout
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

    /** P0 轨迹采样：相邻记录点的最小距离（px）与最小时间间隔（ms）。 */
    private const val SAMPLE_MIN_DIST_PX = 6f
    private const val SAMPLE_MIN_INTERVAL_MS = 16L

    /** 多指手势最多保留的手指/轨迹数，防止异常事件产生超大脚本。 */
    private const val MAX_STROKES = 10

    /** 单条轨迹最多保留的采样点数，兜底防止超长脚本。 */
    private const val MAX_SAMPLES_PER_STROKE = 2000

    /** P1：录制生成的定位式步骤在回放时的查找超时（毫秒），避免长时间空等。 */
    private const val DEFAULT_LOCATE_TIMEOUT_MS = 1500L

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

    // 采集层手势状态（P0）。
    private var tracking = false
    private var skipCurrent = false
    /** 当前手势起点的事件时间（ms），轨迹点的 t 相对它计算。 */
    private var gestureStartMs = 0L
    private var lastGestureEndMs = 0L
    private var pendingDelayMs = 0L

    /** pointerId -> 该手指的轨迹点。LinkedHashMap 保持手指加入顺序（0 号手指在最前）。 */
    private val pointerPaths = LinkedHashMap<Int, MutableList<GesturePoint>>()

    // 回退路径状态（无障碍事件）。
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

    /** 重置采集层手势状态机，避免残留的半程手势影响下一次记录或回放。 */
    private fun resetGestureState() {
        tracking = false
        skipCurrent = false
        gestureStartMs = 0L
        lastGestureEndMs = 0L
        pendingDelayMs = 0L
        pointerPaths.clear()
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
     * 触点状态机（采集层使用）。
     *
     * 按 pointerId 逐指采样轨迹点，抬手后统一分类：
     * - 单指且位移小于 [TAP_SLOP_PX] → 点击 / 长按（按住超过 [LONG_PRESS_MS]）
     * - 单指且位移较大 → 带完整轨迹的滑动
     * - 两指及以上 → 多指手势
     *
     * 动作间隔取 `event.eventTime - lastGestureEndMs` 并上限 [MAX_DELAY_MS]；落在 [ignoredRegion]
     * 内的手势整体忽略。[replay] 为 true 时（采集层路径）在抬手后异步把同款手势回放给目标 App。
     *
     * 坐标取 `getX(pointerIndex)/getY(pointerIndex)`：采集层是全屏且位于屏幕左上角的窗口，
     * 视图坐标即屏幕坐标；`rawX/rawY` 只返回 0 号手指，多指场景不可用。
     */
    private fun handleTouch(event: MotionEvent, replay: Boolean) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerPaths.clear()
                tracking = true
                skipCurrent = false
                gestureStartMs = event.eventTime
                pendingDelayMs = if (lastGestureEndMs > 0L) {
                    (event.eventTime - lastGestureEndMs).coerceIn(0L, MAX_DELAY_MS)
                } else {
                    0L
                }
                val x = event.getX(0)
                val y = event.getY(0)
                skipCurrent = ignoredRegion?.contains(x.toInt(), y.toInt()) == true
                appendPoint(event.getPointerId(0), x, y, 0L, force = true)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (tracking) {
                    val index = event.actionIndex
                    appendPoint(
                        event.getPointerId(index),
                        event.getX(index),
                        event.getY(index),
                        event.eventTime - gestureStartMs,
                        force = true
                    )
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (tracking) {
                    val t = event.eventTime - gestureStartMs
                    for (i in 0 until event.pointerCount) {
                        appendPoint(event.getPointerId(i), event.getX(i), event.getY(i), t)
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (tracking) {
                    val index = event.actionIndex
                    // 该指抬起：记录末点，其余手指继续。
                    appendPoint(
                        event.getPointerId(index),
                        event.getX(index),
                        event.getY(index),
                        event.eventTime - gestureStartMs,
                        force = true
                    )
                }
            }

            MotionEvent.ACTION_UP -> {
                if (!tracking) return
                appendPoint(
                    event.getPointerId(event.actionIndex),
                    event.getX(event.actionIndex),
                    event.getY(event.actionIndex),
                    event.eventTime - gestureStartMs,
                    force = true
                )
                tracking = false
                finishGesture(event.eventTime, replay)
            }

            MotionEvent.ACTION_CANCEL -> {
                // 取消不更新 lastGestureEndMs，不计入间隔。
                tracking = false
                skipCurrent = false
                pointerPaths.clear()
            }
        }
    }

    /**
     * 记录一个轨迹采样点。相邻点距离不足 [SAMPLE_MIN_DIST_PX] 且时间不足 [SAMPLE_MIN_INTERVAL_MS]
     * 时丢弃（[force] 为 true 时强制记录，用于按下/抬起端点）。
     */
    private fun appendPoint(pointerId: Int, x: Float, y: Float, t: Long, force: Boolean = false) {
        val path = pointerPaths.getOrPut(pointerId) { mutableListOf() }
        if (path.size >= MAX_SAMPLES_PER_STROKE) return
        val last = path.lastOrNull()
        if (!force && last != null) {
            val distance = hypot((x - last.x).toDouble(), (y - last.y).toDouble()).toFloat()
            if (distance < SAMPLE_MIN_DIST_PX && (t - last.t) < SAMPLE_MIN_INTERVAL_MS) return
        }
        path.add(GesturePoint(x = x, y = y, t = t.coerceAtLeast(0L)))
    }

    /** 抬手后把本次手势分类成步骤，并可选地回放给目标 App。 */
    private fun finishGesture(upEventTimeMs: Long, replay: Boolean) {
        lastGestureEndMs = upEventTimeMs
        if (skipCurrent) {
            skipCurrent = false
            pointerPaths.clear()
            return
        }
        val strokes = pointerPaths.values
            .filter { it.isNotEmpty() }
            .take(MAX_STROKES)
            .map { it.toList() }
        pointerPaths.clear()
        if (strokes.isEmpty()) return

        if (strokes.size == 1) {
            val stroke = strokes[0]
            val first = stroke.first()
            val last = stroke.last()
            val distance = hypot((last.x - first.x).toDouble(), (last.y - first.y).toDouble())
            val duration = (last.t - first.t).coerceAtLeast(1L)
            when {
                distance < TAP_SLOP_PX && duration >= LONG_PRESS_MS -> {
                    append(
                        Step.LongPress(
                            delayBeforeMs = pendingDelayMs,
                            x = first.x,
                            y = first.y,
                            durationMs = duration
                        )
                    )
                    if (replay) replayTapLike(first.x, first.y, duration)
                }

                distance < TAP_SLOP_PX -> {
                    append(Step.Tap(delayBeforeMs = pendingDelayMs, x = first.x, y = first.y))
                    if (replay) replayTapLike(first.x, first.y, duration)
                }

                else -> {
                    append(
                        Step.Swipe(
                            delayBeforeMs = pendingDelayMs,
                            x1 = first.x,
                            y1 = first.y,
                            x2 = last.x,
                            y2 = last.y,
                            durationMs = duration,
                            path = if (stroke.size > 2) stroke else null
                        )
                    )
                    if (replay) replayStrokes(listOf(stroke))
                }
            }
        } else {
            append(Step.MultiGesture(delayBeforeMs = pendingDelayMs, strokes = strokes))
            if (replay) replayStrokes(strokes)
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
     * 异步回放一次轨迹手势（滑动的完整轨迹，或多指手势的全部轨迹）。
     * 放到 [replayScope] 中执行，理由见 [replayTapLike]。
     */
    private fun replayStrokes(strokes: List<List<GesturePoint>>) {
        if (strokes.isEmpty()) return
        replayScope.launch {
            try {
                acquireCapturePassthrough()
                GestureExecutor.dispatchPath(strokes)
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
            val gaps = delayFromLastEvent()
            val locator = Locator.from(source)
            if (locator.hasSelector) {
                // 有可用标识 → 定位式点击，回放时按控件重新定位，抗布局漂移/换分辨率。
                append(
                    Step.TapElement(
                        delayBeforeMs = gaps,
                        note = locator.note,
                        text = locator.text,
                        viewId = locator.viewId,
                        contentDesc = locator.contentDesc,
                        className = locator.className,
                        index = 0,
                        timeoutMs = DEFAULT_LOCATE_TIMEOUT_MS,
                        onTimeout = OnTimeout.STOP
                    )
                )
                return
            }
            val rect = Rect().also { source.getBoundsInScreen(it) }
            val center = NodeFinder.centerOf(source)
            append(
                Step.Tap(
                    delayBeforeMs = gaps,
                    x = center.x,
                    y = center.y,
                    note = locator.note,
                    boundsLeft = rect.left,
                    boundsTop = rect.top,
                    boundsRight = rect.right,
                    boundsBottom = rect.bottom,
                    // 无障碍事件拿不到真实触点，只能以控件中心为准。
                    relX = 0.5f,
                    relY = 0.5f
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
            val gaps = delayFromLastEvent()
            val locator = Locator.from(source)
            val rect = Rect().also { source.getBoundsInScreen(it) }
            val center = NodeFinder.centerOf(source)
            append(
                Step.LongPress(
                    delayBeforeMs = gaps,
                    x = center.x,
                    y = center.y,
                    durationMs = LONG_CLICK_DURATION_MS,
                    note = locator.note,
                    boundsLeft = rect.left,
                    boundsTop = rect.top,
                    boundsRight = rect.right,
                    boundsBottom = rect.bottom,
                    relX = 0.5f,
                    relY = 0.5f
                )
            )
        } finally {
            recycleQuietly(source)
        }
    }

    /** 从节点提取可用于回放定位的标识（P1）。 */
    private class Locator(
        val text: String?,
        val viewId: String?,
        val contentDesc: String?,
        val className: String?,
        val note: String
    ) {
        val hasSelector: Boolean
            get() = text != null || viewId != null || contentDesc != null || className != null

        companion object {
            fun from(node: AccessibilityNodeInfo): Locator {
                val text = node.text?.toString()?.takeIf { it.isNotBlank() }
                val desc = node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                val cls = node.className?.toString()?.takeIf { it.isNotBlank() }
                return Locator(
                    text = text,
                    viewId = node.viewIdResourceName,
                    contentDesc = desc,
                    className = cls,
                    note = text ?: ""
                )
            }
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