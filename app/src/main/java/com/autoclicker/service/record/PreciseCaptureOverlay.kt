package com.autoclicker.service.record

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.autoclicker.core.bus.LogLevel
import com.autoclicker.core.bus.RecordingSession
import com.autoclicker.core.bus.RuntimeBus
import com.autoclicker.di.ServiceLocator
import com.autoclicker.domain.model.ClickAction
import com.autoclicker.domain.model.GestureAction
import com.autoclicker.domain.model.GestureStroke
import com.autoclicker.domain.model.Ids
import com.autoclicker.domain.model.PercentPoint
import com.autoclicker.domain.model.PixelPoint
import com.autoclicker.domain.model.StepNode
import com.autoclicker.domain.model.SwipeAction
import com.autoclicker.platform.PixelStroke
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot

/**
 * 精确录制浮层：全屏透明浮层捕获原始触摸，抬手后再把整段手势回放给底层 App。
 *
 * **为什么不是「实时回放」**：浮层是全屏**可触摸**窗口，而无障碍 `dispatchGesture` 注入的手势
 * 会被 InputDispatcher 路由到坐标处最上层的可触摸窗口 —— 也就是浮层自身。若在拖动过程中
 * 边捕获边注入，注入事件会被浮层再次捕获并再次注入，形成**无限回放循环**，且底层 App 始终收不到触摸。
 * 因此本类采用「**UP/CANCEL 后翻转 touchable 再回放**」策略：
 * 1. 抬手时先把本窗口 flag 改为包含 [WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE]，
 *    使注入的手势穿透浮层直达底层 App（避免自捕获循环）；
 * 2. 短暂延迟等 flag 生效后回放**完整手势**；
 * 3. 回放结束后恢复原可触摸 flag，再写入录制步骤。
 *
 * **固有局限（免 Root）**：录制期间用户的触摸由浮层独占，底层 App 只在**抬手之后**才收到回放的手势，
 * 因此拖动 / 滚动的手感会有明显延迟（手指离开后才生效）。真正实时透传需要 Shizuku 等更高权限方案。
 */
class PreciseCaptureOverlay(
    context: Context,
    private val onStopRequested: () -> Unit,
    private val onPauseToggle: () -> Unit,
) {

    private val appContext: Context = context.applicationContext
    private val windowManager: WindowManager =
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    @Volatile
    private var showing = false

    @Volatile
    private var paused = false

    @Volatile
    private var autoRecordDelay = true

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var root: FrameLayout? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var originalFlags = 0
    private var captureView: CaptureView? = null
    private var statusText: TextView? = null
    private var pauseButton: Button? = null

    private var recordedCount = 0
    private var lastStepId: String? = null
    private var lastStepEndMs = 0L

    private val screenWidth: Int
        get() = appContext.resources.displayMetrics.widthPixels.coerceAtLeast(1)

    private val screenHeight: Int
        get() = appContext.resources.displayMetrics.heightPixels.coerceAtLeast(1)

    fun isShowing(): Boolean = showing

    fun start() {
        if (showing) return
        showing = true
        paused = false
        recordedCount = 0
        lastStepId = null
        lastStepEndMs = 0L
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        observeSettings()
        buildViews()
    }

    fun stop() {
        showing = false
        paused = false
        // 视图即将移除，先确保标志位恢复为可触摸原值（防残留状态）。
        runCatching { setTouchable(true) }
        val view = root
        root = null
        windowParams = null
        captureView = null
        statusText = null
        pauseButton = null
        if (view != null) {
            try {
                windowManager.removeView(view)
            } catch (t: Throwable) {
                // 忽略移除异常。
            }
        }
        runCatching { scope.cancel() }
    }

    fun setPaused(paused: Boolean) {
        this.paused = paused
        pauseButton?.text = if (paused) "继续" else "暂停"
    }

    // ---------------- 视图构建 ----------------

    private fun buildViews() {
        val frame = FrameLayout(appContext)
        val capture = CaptureView(appContext)
        frame.addView(
            capture,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        // 控制条后添加 → 位于 CaptureView 之上，按钮触摸不会被吞掉。
        val bar = buildControlBar()
        frame.addView(
            bar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START,
            ).apply {
                leftMargin = dp(8)
                topMargin = dp(8)
            },
        )

        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        originalFlags = flags
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        try {
            windowManager.addView(frame, params)
            root = frame
            windowParams = params
            captureView = capture
        } catch (t: Throwable) {
            root = null
            windowParams = null
            captureView = null
            showing = false
            RuntimeBus.log(LogLevel.ERROR, "录制浮层创建失败：${t.message}")
        }
    }

    private fun buildControlBar(): LinearLayout {
        val bar = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xCC000000.toInt())
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        val label = TextView(appContext).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            text = statusLabel()
        }
        statusText = label
        val pause = Button(appContext).apply {
            text = "暂停"
            textSize = 12f
            setOnClickListener { onPauseToggle() }
        }
        pauseButton = pause
        val stop = Button(appContext).apply {
            text = "停止"
            textSize = 12f
            setOnClickListener { onStopRequested() }
        }
        bar.addView(label)
        bar.addView(pause)
        bar.addView(stop)
        return bar
    }

    private fun statusLabel(): String = "● 录制中  已录 $recordedCount 步"

    private fun updateStatus() {
        statusText?.text = statusLabel()
    }

    private fun observeSettings() {
        scope.launch {
            ServiceLocator.settings.settings.collect { autoRecordDelay = it.autoRecordDelay }
        }
    }

    /** 切换浮层触摸能力：false 时注入的手势可穿透浮层直达底层 App。 */
    private fun setTouchable(touchable: Boolean) {
        val view = root ?: return
        val params = windowParams ?: return
        params.flags = if (touchable) originalFlags else originalFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        try {
            windowManager.updateViewLayout(view, params)
        } catch (t: Throwable) {
            RuntimeBus.log(LogLevel.WARN, "浮层触摸标志切换失败：${t.message}")
        }
    }

    // ---------------- 触摸捕获与抬手回放 ----------------

    private inner class CaptureView(context: Context) : View(context) {

        private val sampled = ArrayList<PixelPoint>()
        private var downPoint = PixelPoint(0, 0)
        private var downTimeMs = 0L

        /** 正在回放：忽略此期间到达的触摸，避免把注入事件再录一遍。 */
        private var injecting = false

        override fun onTouchEvent(event: MotionEvent): Boolean {
            // 暂停期间仍消费触摸（避免穿透到底层 App），但不回放也不记录。
            if (paused || injecting) return true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val p = PixelPoint(event.rawX.toInt(), event.rawY.toInt())
                    sampled.clear()
                    sampled.add(p)
                    downPoint = p
                    downTimeMs = event.downTime
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val p = PixelPoint(event.rawX.toInt(), event.rawY.toInt())
                    if (sampled.isEmpty()) sampled.add(downPoint)
                    if (dist(sampled.last(), p) >= SAMPLE_MIN_PX) sampled.add(p)
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    val p = PixelPoint(event.rawX.toInt(), event.rawY.toInt())
                    finishGesture(p, event.eventTime)
                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    val last = sampled.lastOrNull() ?: downPoint
                    finishGesture(last, event.eventTime)
                    return true
                }
            }
            return true
        }

        /**
         * 抬手/取消后的完整流程：翻转 flag（不可触摸）→ 延迟 → 回放整段手势 → 恢复 flag → 记录步骤。
         */
        private fun finishGesture(endPoint: PixelPoint, upTime: Long) {
            val displacement = dist(downPoint, endPoint)
            val duration = (upTime - downTimeMs).coerceAtLeast(1L)
            val points = ArrayList(sampled)
            if (points.isEmpty() || points.last() != endPoint) points.add(endPoint)
            val simplified =
                if (displacement < MOVE_THRESHOLD_PX) emptyList() else simplify(points, SIMPLIFY_EPS)

            injecting = true
            scope.launch {
                try {
                    setTouchable(false)
                    delay(REPLAY_FLAG_DELAY_MS)
                    replay(displacement, simplified, duration)
                } finally {
                    setTouchable(true)
                    injecting = false
                }
                recordStep(upTime, duration, simplified, displacement)
            }
        }

        /** 回放整段手势（此时浮层不可触摸，事件直达底层 App）。 */
        private suspend fun replay(
            displacement: Float,
            simplified: List<PixelPoint>,
            duration: Long,
        ) {
            val executor = ServiceLocator.gestureExecutor
            if (executor == null || !executor.isReady()) {
                RuntimeBus.log(LogLevel.WARN, "回放失败：手势执行器未就绪")
                return
            }
            val ok = try {
                if (displacement < MOVE_THRESHOLD_PX || simplified.size < 2) {
                    executor.click(downPoint.x, downPoint.y, duration)
                } else {
                    executor.perform(listOf(PixelStroke(simplified, 0L, duration)))
                }
            } catch (t: Throwable) {
                false
            }
            if (!ok) RuntimeBus.log(LogLevel.WARN, "回放失败：手势注入未成功")
        }

        private fun recordStep(
            upTime: Long,
            duration: Long,
            simplified: List<PixelPoint>,
            displacement: Float,
        ) {
            val action = when {
                displacement < MOVE_THRESHOLD_PX -> ClickAction(toPercent(downPoint), duration)
                simplified.size <= 2 -> SwipeAction(
                    from = toPercent(simplified.first()),
                    to = toPercent(simplified.last()),
                    durationMs = duration,
                )
                else -> GestureAction(
                    strokes = listOf(
                        GestureStroke(
                            points = simplified.map { toPercent(it) },
                            startTimeMs = 0L,
                            durationMs = duration,
                        ),
                    ),
                )
            }

            // 回填「上一次手势结束 → 本次手势开始」的间隔。
            if (autoRecordDelay) {
                val previousId = lastStepId
                if (previousId != null) {
                    val gap = (downTimeMs - lastStepEndMs).coerceAtLeast(0L)
                    val previous =
                        RecordingSession.nodes.value.firstOrNull { it.id == previousId } as? StepNode
                    if (previous != null) RecordingSession.updateNode(previous.copy(delayAfterMs = gap))
                }
            }

            val step = StepNode(id = Ids.newId(), action = action)
            RecordingSession.addNode(step)
            lastStepId = step.id
            lastStepEndMs = upTime
            recordedCount++
            updateStatus()
        }
    }

    // ---------------- 几何工具 ----------------

    private fun toPercent(p: PixelPoint): PercentPoint = PercentPoint(
        (p.x.toFloat() / screenWidth).coerceIn(0f, 1f),
        (p.y.toFloat() / screenHeight).coerceIn(0f, 1f),
    )

    private fun dist(a: PixelPoint, b: PixelPoint): Float =
        hypot((a.x - b.x).toFloat(), (a.y - b.y).toFloat())

    /** Douglas-Peucker 折线化简：保留转角，抹平采样抖动。 */
    private fun simplify(points: List<PixelPoint>, epsilon: Float): List<PixelPoint> {
        if (points.size <= 2) return points
        val first = points.first()
        val last = points.last()
        var maxDist = 0f
        var index = -1
        for (i in 1 until points.size - 1) {
            val d = perpendicularDistance(points[i], first, last)
            if (d > maxDist) {
                maxDist = d
                index = i
            }
        }
        return if (maxDist > epsilon && index > 0) {
            val left = simplify(points.subList(0, index + 1), epsilon)
            val right = simplify(points.subList(index, points.size), epsilon)
            left.dropLast(1) + right
        } else {
            listOf(first, last)
        }
    }

    private fun perpendicularDistance(p: PixelPoint, a: PixelPoint, b: PixelPoint): Float {
        val dx = (b.x - a.x).toFloat()
        val dy = (b.y - a.y).toFloat()
        if (dx == 0f && dy == 0f) return hypot((p.x - a.x).toFloat(), (p.y - a.y).toFloat())
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / (dx * dx + dy * dy)).coerceIn(0f, 1f)
        val projX = a.x + t * dx
        val projY = a.y + t * dy
        return hypot(p.x - projX, p.y - projY)
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        appContext.resources.displayMetrics,
    ).toInt()

    companion object {
        /** 翻转 flag 后等待其生效的时间。 */
        private const val REPLAY_FLAG_DELAY_MS = 32L

        /** 折线化简阈值（像素）。 */
        private const val SIMPLIFY_EPS = 4f

        /** 判定「拖动」的位移阈值（像素）。 */
        private const val MOVE_THRESHOLD_PX = 24f

        /** 采样点最小间距（像素）。 */
        private const val SAMPLE_MIN_PX = 4f
    }
}