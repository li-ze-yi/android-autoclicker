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

/**
 * 精确录制模式的全屏透明触摸捕获层（FR-5 精确模式）。
 *
 * - 一个覆盖整块屏幕的透明窗口（TYPE_APPLICATION_OVERLAY），直接接收手指按下/移动/抬起；
 * - 坐标一律取 MotionEvent.rawX/rawY（屏幕绝对坐标，与 ScreenMetrics 同一坐标空间），杜绝偏移；
 * - 抬手时由 [GestureAggregator] 归并为**单条**手势（点击/滑动），随后：
 *   1) 通过无障碍手势把等价手势「透传」给目标 App —— 捕获层消费了原始触摸，目标 App 收不到，必须补派；
 *   2) 交给 [Recorder] 录入时间轴；
 * - 透传为挂起调用，只在主线程 [scope] 中 launch，绝不阻塞 onTouch 返回（NFR-1/NFR-5）。
 *
 * 自我级联防护：精确录制时 recorder.mode == Precise，普通模式无障碍事件源不会录入；
 * 无障碍派发的注入手势也不会再回到本触摸层。
 *
 * @param scope 主线程协程作用域（由 OverlayService 提供，服务销毁时统一取消）
 */
class PreciseCaptureLayer(
    private val context: Context,
    private val wm: WindowManager,
    private val recorder: Recorder,
    private val scope: CoroutineScope,
) {

    /** 手势归并器：一次 down→up 只归并出一条手势 */
    private val aggregator = GestureAggregator()

    /** 手势透传执行器（无障碍 dispatchGesture） */
    private val executor = AndroidGestureExecutor()

    /** 捕获视图：近全透明背景（#01000000）保证视图可绘制、可接收触摸 */
    private val view: View = View(context).apply {
        setBackgroundColor(0x01000000)
        setOnTouchListener { _, event -> handleTouch(event) }
    }

    /** 窗口是否已挂载（用于 attach/detach 幂等） */
    private var attached: Boolean = false

    /** 窗口布局参数：全屏尺寸、位置 (0,0)、不获取焦点 */
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

    /** 挂载到窗口（重复调用幂等）。仅允许在主线程调用。 */
    fun attach() {
        if (attached) return
        wm.addView(view, params)
        attached = true
    }

    /** 从窗口移除并复位归并器（重复调用幂等）。仅允许在主线程调用。 */
    fun detach() {
        if (!attached) return
        wm.removeView(view)
        attached = false
        aggregator.reset()
    }

    /**
     * 触摸处理：坐标一律 rawX/rawY 屏幕绝对坐标，时间用 event.eventTime。
     * 返回 true 表示消费触摸（事件不下发给下层窗口）。
     */
    private fun handleTouch(event: MotionEvent): Boolean {
        val rawX = event.rawX.toInt()
        val rawY = event.rawY.toInt()
        val time = event.eventTime

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> aggregator.onDown(rawX, rawY, time)

            MotionEvent.ACTION_MOVE -> aggregator.onMove(rawX, rawY)

            MotionEvent.ACTION_UP -> {
                // 防御异常配对：拿不到归并结果时只复位，不崩溃
                val gesture = runCatching {
                    aggregator.onUp(rawX, rawY, time)
                }.getOrNull()
                if (gesture != null) {
                    // 1) 先透传等价手势给目标 App（挂起派发，launch 后立即返回，不阻塞 onTouch）
                    scope.launch { passthrough(gesture) }
                    // 2) 录入时间轴（归并/录制内核轻量，直接主线程调用）
                    recorder.recordGesture(gesture, time)
                }
                // 复位归并器，准备归并下一手势
                aggregator.reset()
            }

            MotionEvent.ACTION_CANCEL -> aggregator.reset()
        }
        return true
    }

    /** 把归并手势等价透传给目标 App：点击→tap，滑动→swipe（保留实际耗时） */
    private suspend fun passthrough(gesture: RecordedGesture) {
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
    }
}
