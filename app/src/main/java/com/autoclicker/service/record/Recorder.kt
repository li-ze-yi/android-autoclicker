package com.autoclicker.service.record

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.autoclicker.core.bus.LogLevel
import com.autoclicker.core.bus.RecorderBus
import com.autoclicker.core.bus.RecordingState
import com.autoclicker.core.bus.RuntimeBus
import com.autoclicker.di.ServiceLocator
import com.autoclicker.domain.model.Action
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.math.hypot

/**
 * 触摸录制器：全屏透明悬浮层捕获 [MotionEvent]，抬手时把轨迹归一化为百分比坐标，
 * 生成 [StepNode]（点按→[ClickAction]；滑动/手势→[SwipeAction]/[GestureAction]）写入 [RecorderBus]；
 * 同时通过无障碍把同一手势转发回底层 App，避免录制层遮挡导致底层收不到操作。
 */
class Recorder(context: Context) {

    private val appContext: Context = context.applicationContext
    private val windowManager: WindowManager =
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    @Volatile
    private var scope: CoroutineScope? = null

    @Volatile
    private var overlay: View? = null

    private var layerParams: WindowManager.LayoutParams? = null

    @Volatile
    private var attached = false

    @Volatile
    private var paused = false

    @Volatile
    private var autoRecordDelay = true

    private var lastStepId: String? = null
    private var lastEndTimeMs = 0L

    private val screenWidth: Int
        get() = appContext.resources.displayMetrics.widthPixels.coerceAtLeast(1)

    private val screenHeight: Int
        get() = appContext.resources.displayMetrics.heightPixels.coerceAtLeast(1)

    fun isRecording(): Boolean = overlay != null

    fun start() {
        if (isRecording()) return
        if (!Settings.canDrawOverlays(appContext)) {
            RuntimeBus.log(LogLevel.ERROR, "无法开始录制：缺少悬浮窗权限")
            return
        }
        val recorderScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = recorderScope
        recorderScope.launch {
            ServiceLocator.settings.settings.collect { settings ->
                autoRecordDelay = settings.autoRecordDelay
            }
        }

        val view = TouchLayerView(appContext)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }

        try {
            windowManager.addView(view, params)
        } catch (t: Throwable) {
            RuntimeBus.log(LogLevel.ERROR, "录制触摸层创建失败：${t.message}")
            recorderScope.cancel()
            scope = null
            return
        }
        layerParams = params
        overlay = view
        attached = true
        paused = false
        lastStepId = null
        lastEndTimeMs = 0L
        RuntimeBus.setRecording(RecordingState.RECORDING)
        RuntimeBus.log("录制已开始")
    }

    /** 切换暂停/继续：暂停时移除触摸层，让底层 App 恢复可操作。 */
    fun pause() {
        val view = overlay ?: return
        if (paused) {
            val params = layerParams ?: return
            try {
                windowManager.addView(view, params)
                attached = true
            } catch (t: Throwable) {
                RuntimeBus.log(LogLevel.ERROR, "录制恢复失败：${t.message}")
                return
            }
            paused = false
            RuntimeBus.setRecording(RecordingState.RECORDING)
            RuntimeBus.log("录制已继续")
        } else {
            if (attached) {
                try {
                    windowManager.removeView(view)
                } catch (t: Throwable) {
                    // 忽略移除异常。
                }
                attached = false
            }
            paused = true
            RuntimeBus.setRecording(RecordingState.PAUSED)
            RuntimeBus.log("录制已暂停")
        }
    }

    fun stop() {
        val view = overlay ?: return
        overlay = null
        if (attached) {
            try {
                windowManager.removeView(view)
            } catch (t: Throwable) {
                // 忽略移除异常。
            }
            attached = false
        }
        layerParams = null
        paused = false
        scope?.cancel()
        scope = null
        RuntimeBus.setRecording(RecordingState.IDLE)
        RuntimeBus.log("录制已结束")
    }

    // ---------------- 手势处理 ----------------

    @SuppressLint("ViewConstructor")
    private inner class TouchLayerView(context: Context) : View(context) {

        private val points = ArrayList<PixelPoint>()
        private var downTime = 0L

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downTime = SystemClock.uptimeMillis()
                    points.clear()
                    addPoint(event)
                    onGestureStart(downTime)
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    addPoint(event)
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    addPoint(event)
                    finishGesture(SystemClock.uptimeMillis())
                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    points.clear()
                    return true
                }
            }
            return false
        }

        private fun addPoint(event: MotionEvent) {
            val point = PixelPoint(event.rawX.toInt(), event.rawY.toInt())
            if (points.isEmpty()) {
                points.add(point)
                return
            }
            if (distance(points.last(), point) >= MIN_MOVE_PX) {
                points.add(point)
            } else {
                // 抖动点直接替换为上一点，避免产生冗余轨迹。
                points[points.size - 1] = point
            }
        }

        private fun finishGesture(endTime: Long) {
            val gesturePoints = points.toList()
            if (gesturePoints.isEmpty()) return
            val duration = (endTime - downTime).coerceAtLeast(1L)

            val action = buildAction(gesturePoints, duration)
            val step = StepNode(id = Ids.newId(), action = action)
            RecorderBus.addStep(step)
            lastStepId = step.id
            lastEndTimeMs = endTime

            RuntimeBus.log("录制步骤：${describe(action)}")
            forwardGesture(gesturePoints, duration)
        }
    }

    private fun onGestureStart(now: Long) {
        if (!autoRecordDelay) return
        val previousId = lastStepId ?: return
        val gap = (now - lastEndTimeMs).coerceAtLeast(0L)
        val previous = RecorderBus.steps.value.firstOrNull { it.id == previousId } ?: return
        RecorderBus.updateStep(previous.copy(delayAfterMs = gap))
    }

    private fun buildAction(points: List<PixelPoint>, duration: Long): Action {
        val down = points.first()
        val maxDisplacement = points.maxOf { distance(down, it) }
        if (maxDisplacement < TAP_PX) {
            return ClickAction(point = toPercent(down), durationMs = duration)
        }
        val simplified = simplify(points)
        return if (simplified.size <= 2) {
            SwipeAction(
                from = toPercent(simplified.first()),
                to = toPercent(simplified.last()),
                durationMs = duration,
            )
        } else {
            GestureAction(
                strokes = listOf(
                    GestureStroke(
                        points = simplified.map { toPercent(it) },
                        startTimeMs = 0L,
                        durationMs = duration,
                    ),
                ),
            )
        }
    }

    private fun simplify(points: List<PixelPoint>): List<PixelPoint> {
        if (points.size <= 2) return points
        val result = ArrayList<PixelPoint>()
        result.add(points.first())
        for (i in 1 until points.size - 1) {
            if (distance(result.last(), points[i]) >= SWIPE_SIMPLIFY_PX) result.add(points[i])
        }
        result.add(points.last())
        return result
    }

    private fun toPercent(point: PixelPoint): PercentPoint = PercentPoint(
        (point.x.toFloat() / screenWidth).coerceIn(0f, 1f),
        (point.y.toFloat() / screenHeight).coerceIn(0f, 1f),
    )

    private fun forwardGesture(points: List<PixelPoint>, duration: Long) {
        val executor = ServiceLocator.gestureExecutor ?: return
        val currentScope = scope ?: return
        currentScope.launch {
            try {
                executor.perform(
                    listOf(
                        PixelStroke(
                            points = points,
                            startTimeMs = 0L,
                            durationMs = duration.coerceAtLeast(1L),
                        ),
                    ),
                )
            } catch (t: Throwable) {
                RuntimeBus.log(LogLevel.WARN, "录制手势转发失败：${t.message}")
            }
        }
    }

    private fun describe(action: Action): String = when (action) {
        is ClickAction -> "点按 (${"%.3f".format(action.point.x)}, ${"%.3f".format(action.point.y)})"
        is SwipeAction -> "滑动 (${"%.2f".format(action.from.x)},${"%.2f".format(action.from.y)})→" +
            "(${"%.2f".format(action.to.x)},${"%.2f".format(action.to.y)})"
        is GestureAction -> "手势 ${action.strokes.firstOrNull()?.points?.size ?: 0} 点"
        else -> action.toString()
    }

    private fun distance(a: PixelPoint, b: PixelPoint): Float =
        hypot((a.x - b.x).toFloat(), (a.y - b.y).toFloat())

    companion object {
        private const val MIN_MOVE_PX = 8f
        private const val TAP_PX = 24f
        private const val SWIPE_SIMPLIFY_PX = 12f

        @Volatile
        private var instance: Recorder? = null

        /** 开始录制（由 UI 调用）。 */
        fun start(context: Context) {
            val ctx = context.applicationContext
            instance?.stop()
            val recorder = Recorder(ctx)
            instance = recorder
            recorder.start()
        }

        /** 暂停/继续切换（由 UI 调用）。 */
        fun pause(context: Context) {
            instance?.pause()
        }

        /** 停止录制（由 UI 调用）。 */
        fun stop(context: Context) {
            instance?.stop()
            instance = null
        }

        fun isRecording(): Boolean = instance?.isRecording() == true
    }
}