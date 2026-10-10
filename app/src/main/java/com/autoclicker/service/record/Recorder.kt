package com.autoclicker.service.record

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import com.autoclicker.core.bus.LogLevel
import com.autoclicker.core.bus.RecordingSession
import com.autoclicker.core.bus.RecordingState
import com.autoclicker.core.bus.RuntimeBus
import com.autoclicker.core.permission.PermissionChecker
import com.autoclicker.service.overlay.OverlayService
import com.autoclicker.domain.model.ClickAction
import com.autoclicker.domain.model.Ids
import com.autoclicker.domain.model.PercentPoint
import com.autoclicker.domain.model.StepNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 触摸录制器（**唯一的精确录制模式**）。
 *
 * 录制期间由全屏透明浮层 [PreciseCaptureOverlay] 独占原始触摸：抬手后浮层临时转为不可触摸，
 * 把整段手势回放给底层 App，再按位移归类为点击 / 滑动 / 手势步骤并写入 [RecordingSession]。
 * 不再依赖无障碍事件旁路，录制对目标 App 的兼容性更好。
 *
 * 固有局限（免 Root）：录制期间用户的触摸由浮层独占，底层 App 只在**抬手之后**才收到回放的手势，
 * 因此拖动 / 滚动的手感会有延迟；真正实时透传需要 Shizuku 等更高权限方案。
 *
 * 另外提供：手动取点（一次性全屏取点层，单击即取点并立即消失，用于补充任意像素坐标）。
 */
class Recorder(private val appContext: Context) {

    private val windowManager: WindowManager =
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    @Volatile
    private var recording = false

    @Volatile
    private var paused = false

    private var overlay: PreciseCaptureOverlay? = null

    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pickerView: View? = null

    private var lastStepId: String? = null
    private var lastEventTimeMs = 0L

    private val screenWidth: Int
        get() = appContext.resources.displayMetrics.widthPixels.coerceAtLeast(1)

    private val screenHeight: Int
        get() = appContext.resources.displayMetrics.heightPixels.coerceAtLeast(1)

    fun isRecording(): Boolean = recording

    // ---------------- 录制会话 ----------------

    fun start() {
        if (recording) return
        // 三重守卫：无障碍 → 悬浮窗 → 已绑定任务，任一不满足都不启动。
        if (!PermissionChecker.requireAccessibility(appContext)) return
        if (!PermissionChecker.requireOverlay(appContext)) return
        if (!RecordingSession.isActive) {
            Toast.makeText(appContext, "请先创建或打开一个任务，再开始录制", Toast.LENGTH_SHORT).show()
            RuntimeBus.log(LogLevel.WARN, "无法开始录制：尚未绑定任务，请先在首页创建或打开一个任务")
            return
        }
        recording = true
        paused = false
        val precise = PreciseCaptureOverlay(
            context = appContext,
            onStopRequested = { stop() },
            onPauseToggle = { pause() },
        )
        overlay = precise
        precise.start()
        RuntimeBus.setRecording(RecordingState.RECORDING)
        RuntimeBus.log("录制已开始（精确模式）：请正常操作目标 App，抬手后手势会回放给目标 App")
    }

    /** 切换暂停/继续（暂停期间不回放也不记录）。 */
    fun pause() {
        if (!recording) return
        paused = !paused
        overlay?.setPaused(paused)
        RuntimeBus.setRecording(if (paused) RecordingState.PAUSED else RecordingState.RECORDING)
        RuntimeBus.log(if (paused) "录制已暂停" else "录制已继续")
    }

    fun stop() {
        if (!recording) {
            dismissPicker()
            return
        }
        recording = false
        paused = false
        dismissPicker()
        overlay?.stop()
        overlay = null
        RuntimeBus.setRecording(RecordingState.IDLE)
        RuntimeBus.log("录制已结束")
        // 停止录制后把这一轮的步骤写回任务，然后关闭悬浮球（「录完即收」）。
        saveScope.launch {
            val ok = runCatching { RecordingSession.saveNow() }.getOrDefault(false)
            if (ok) {
                RuntimeBus.log("录制已保存到任务「${RecordingSession.scriptName.value}」")
            } else {
                RuntimeBus.log(LogLevel.WARN, "录制保存失败或未绑定任务")
            }
            runCatching { OverlayService.stop(appContext) }
            RuntimeBus.log("悬浮球已关闭")
        }
    }

    // ---------------- 手动取点 ----------------

    /**
     * 进入一次性取点：屏幕出现半透明取点层，单击任意位置即补一个点击步骤并立即消失。
     * 仅用于补充任意像素坐标。
     */
    fun pickPoint() {
        if (pickerView != null) return
        if (!hasOverlayPermission()) {
            RuntimeBus.log(LogLevel.ERROR, "无法取点：缺少悬浮窗权限")
            return
        }
        val view = PickerView(appContext)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        try {
            windowManager.addView(view, params)
            pickerView = view
            RuntimeBus.log("取点模式：点击屏幕上要添加的位置（单击即取点）")
        } catch (t: Throwable) {
            pickerView = null
            RuntimeBus.log(LogLevel.ERROR, "取点层创建失败：${t.message}")
        }
    }

    private fun dismissPicker() {
        val view = pickerView ?: return
        pickerView = null
        try {
            windowManager.removeView(view)
        } catch (t: Throwable) {
            // 忽略移除异常。
        }
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        appContext.resources.displayMetrics,
    ).toInt()

    private inner class PickerView(context: Context) : TextView(context) {
        init {
            text = "取点模式：点击屏幕上要添加的位置"
            setTextColor(Color.WHITE)
            setBackgroundColor(0x44000000)
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
            setPadding(0, dp(48), 0, 0)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> return true
                MotionEvent.ACTION_UP -> {
                    val point = toPercent(event.rawX.toInt(), event.rawY.toInt())
                    val step = StepNode(id = Ids.newId(), action = ClickAction(point))
                    RecordingSession.addNode(step)
                    lastStepId = step.id
                    lastEventTimeMs = SystemClock.uptimeMillis()
                    RuntimeBus.log("取点：点击 (${fmt(point.x)}, ${fmt(point.y)})")
                    dismissPicker()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }

    private fun toPercent(x: Int, y: Int): PercentPoint = PercentPoint(
        (x.toFloat() / screenWidth).coerceIn(0f, 1f),
        (y.toFloat() / screenHeight).coerceIn(0f, 1f),
    )

    private fun hasOverlayPermission(): Boolean = Settings.canDrawOverlays(appContext)

    private fun fmt(value: Float): String = "%.3f".format(value)

    companion object {

        @Volatile
        private var instance: Recorder? = null

        /** 当前录制器。 */
        fun current(): Recorder? = instance

        private fun obtain(context: Context): Recorder =
            instance ?: Recorder(context.applicationContext).also { instance = it }

        /** 开始录制（由 UI 调用）。 */
        fun start(context: Context) {
            instance?.stop()
            val recorder = obtain(context)
            recorder.start()
        }

        /** 暂停/继续切换（由 UI 调用）。 */
        fun pause(context: Context) {
            instance?.pause()
        }

        /** 停止录制（由 UI 调用）。 */
        fun stop(context: Context) {
            instance?.stop()
        }

        /** 手动取点（由 UI 调用）。 */
        fun pickPoint(context: Context) {
            obtain(context).pickPoint()
        }

        fun isRecording(): Boolean = instance?.isRecording() == true
    }
}