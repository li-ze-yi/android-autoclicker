package com.autoclicker.service.record

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.autoclicker.core.record.GestureAggregator
import com.autoclicker.core.record.RecordedGesture
import com.autoclicker.core.record.Recorder
import com.autoclicker.service.accessibility.AndroidGestureExecutor
import com.autoclicker.service.overlay.ScreenMetrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 精确录制模式的全屏透明触摸捕获层（FR-5 精确模式）。
 *
 * 关键设计（避免“手势被自己拦截 / 自我级联”）：
 * - 捕获层是最顶层的可触摸窗口，原始触摸只能到这里，目标 App 收不到；
 * - 抬手归并出一条手势后，先录入时间轴，再“安全透传”：
 *   临时把捕获视图从 WindowManager 移除 → 派发等价手势（此时最顶层是目标 App，手势直达）→
 *   完成后重新挂载捕获视图；
 * - 透传过程用 [forwardMutex] 串行化，防止快速连点造成并发；
 * - 坐标一律 rawX/rawY 屏幕绝对坐标。
 *
 * @param scope 主线程协程作用域（OverlayService 提供，服务销毁时统一取消）
 * @param onForwarded 重新挂载后的回调（服务借此把悬浮球重新置顶到捕获层之上）
 */
class PreciseCaptureLayer(
    private val context: Context,
    private val wm: WindowManager,
    private val recorder: Recorder,
    private val scope: CoroutineScope,
    private val onForwarded: () -> Unit = {},
) {

    /** 手势归并器：一次 down→up 只归并出一条手势 */
    private val aggregator = GestureAggregator()

    /** 手势透传执行器（无障碍 dispatchGesture） */
    private val executor = AndroidGestureExecutor()

    /** 串行化透传（移除→派发→重挂）临界区 */
    private val forwardMutex = Mutex()

    /** 捕获视图：近全透明背景（#01000000）保证可接收触摸 */
    private val view: View = View(context).apply {
        setBackgroundColor(0x01000000)
        setOnTouchListener { _, event -> handleTouch(event) }
    }

    /** 逻辑上是否处于录制（attach/detach 维护） */
    private var attached: Boolean = false

    /** 视图是否真的在窗口中（透传期间临时为 false，与 attached 区分，供 detach 兜底） */
    private var viewPresent: Boolean = false

    /** 窗口布局参数：全屏、位置 (0,0)、不获取焦点 */
    private val params: WindowManager.LayoutParams = WindowManager.LayoutParams(
        ScreenMetrics.width(wm),
        ScreenMetrics.height(wm),
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 0
        y = 0
    }

    /** 挂载到窗口（幂等）。仅主线程调用。 */
    fun attach() {
        if (attached) return
        wm.addView(view, params)
        attached = true
        viewPresent = true
    }

    /** 从窗口移除并复位（幂等，容忍透传期间视图临时缺席）。仅主线程调用。 */
    fun detach() {
        if (!attached) return
        if (viewPresent) runCatching { wm.removeView(view) }
        attached = false
        viewPresent = false
        aggregator.reset()
    }

    /**
     * 触摸处理：坐标 rawX/rawY，时间 event.eventTime；返回 true 消费触摸。
     */
    private fun handleTouch(event: MotionEvent): Boolean {
        val rawX = event.rawX.toInt()
        val rawY = event.rawY.toInt()
        val time = event.eventTime

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> aggregator.onDown(rawX, rawY, time)

            MotionEvent.ACTION_MOVE -> aggregator.onMove(rawX, rawY)

            MotionEvent.ACTION_UP -> {
                val gesture = runCatching {
                    aggregator.onUp(rawX, rawY, time)
                }.getOrNull()
                aggregator.reset()
                if (gesture != null) {
                    // 1) 先录入时间轴（其中已自动追加该动作的延时）
                    recorder.recordGesture(gesture, time)
                    // 2) 再安全透传给目标 App（异步、串行，不阻塞 onTouch）
                    scope.launch { forwardSafely(gesture) }
                }
            }

            MotionEvent.ACTION_CANCEL -> aggregator.reset()
        }
        return true
    }

    /**
     * 安全透传：临时移除捕获视图 → 派发等价手势并等待结束 → 重新挂载。
     * 这样派发的手势不会被捕获层自己拦截，也不会再次触发录制（无级联）。
     */
    private suspend fun forwardSafely(gesture: RecordedGesture) = forwardMutex.withLock {
        if (!attached) return@withLock

        // 1) 移除拦截层
        if (viewPresent) {
            runCatching { wm.removeView(view) }
            viewPresent = false
        }

        try {
            // 2) 最顶层现在是目标 App，手势直达
            when (gesture) {
                is RecordedGesture.Tap ->
                    executor.tap(gesture.x, gesture.y, holdMs = 1L)

                is RecordedGesture.Swipe ->
                    executor.swipe(
                        gesture.x1, gesture.y1,
                        gesture.x2, gesture.y2,
                        durationMs = gesture.durationMs,
                    )
            }
        } finally {
            // 3) 重新挂载捕获层（仍在录制才挂）
            if (attached && !viewPresent) {
                runCatching { wm.addView(view, params) }
                viewPresent = true
                onForwarded()
            }
        }
    }
}
