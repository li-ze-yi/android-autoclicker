package com.autoclicker.service.record

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import android.widget.Toast
import com.autoclicker.core.bus.LogLevel
import com.autoclicker.core.bus.RecorderBus
import com.autoclicker.core.bus.RecordingSession
import com.autoclicker.core.bus.RecordingState
import com.autoclicker.core.bus.RuntimeBus
import com.autoclicker.core.permission.PermissionChecker
import com.autoclicker.di.ServiceLocator
import com.autoclicker.service.overlay.OverlayService
import com.autoclicker.domain.model.ClickAction
import com.autoclicker.domain.model.Ids
import com.autoclicker.domain.model.LongPressAction
import com.autoclicker.domain.model.PercentPoint
import com.autoclicker.domain.model.StepNode
import com.autoclicker.domain.model.SwipeAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 触摸录制器（**基于无障碍服务**，不遮挡屏幕）。
 *
 * 录制期间由 [AccessibilityService.onAccessibilityEvent] 回调本类的 [onEvent]，
 * 依据事件类型生成步骤：
 * - `TYPE_VIEW_CLICKED` → [ClickAction]（取控件 bounds 中心）
 * - `TYPE_VIEW_LONG_CLICKED` → [LongPressAction]
 * - `TYPE_VIEW_SCROLLED` → [SwipeAction]（按滚动方向合成）
 *
 * 另外提供：
 * - 录制状态角标（不可触摸的小角标）；
 * - 手动取点（一次性全屏取点层，单击即取点并立即消失，仅用于补充任意像素坐标）。
 */
class Recorder(private val appContext: Context) {

    private val windowManager: WindowManager =
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    @Volatile
    private var recording = false

    @Volatile
    private var paused = false

    @Volatile
    private var autoRecordDelay = true

    private var settingsScope: CoroutineScope? = null
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var badgeView: TextView? = null
    private var pickerView: View? = null

    private var lastStepId: String? = null
    private var lastEventTimeMs = 0L
    private var lastClickCenterX = Int.MIN_VALUE
    private var lastClickCenterY = Int.MIN_VALUE
    private var lastClickTimeMs = 0L

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
        if (ServiceLocator.nodeLocator == null) {
            RuntimeBus.log(LogLevel.WARN, "无障碍服务未连接，录制将无法捕获事件")
        }
        recording = true
        paused = false
        lastStepId = null
        lastEventTimeMs = 0L
        lastClickCenterX = Int.MIN_VALUE
        lastClickCenterY = Int.MIN_VALUE
        observeSettings()
        showBadge()
        RuntimeBus.setRecording(RecordingState.RECORDING)
        RuntimeBus.log("录制已开始：请正常操作目标 App，操作会被旁路记录")
    }

    /** 切换暂停/继续（暂停期间不记录事件）。 */
    fun pause() {
        if (!recording) return
        paused = !paused
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
        hideBadge()
        settingsScope?.cancel()
        settingsScope = null
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

    // ---------------- 无障碍事件入口 ----------------

    /** 由无障碍服务在 [AccessibilityService.onAccessibilityEvent] 中回调。 */
    fun onEvent(event: AccessibilityEvent) {
        if (!recording || paused) return
        val pkg = event.packageName?.toString() ?: return
        // 过滤本应用自身的事件，避免把「停止/暂停」等点击录进去。
        if (pkg == appContext.packageName) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> recordTap(event, longPress = false)
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> recordTap(event, longPress = true)
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> recordScroll(event)
            else -> Unit
        }
    }

    private fun recordTap(event: AccessibilityEvent, longPress: Boolean) {
        val rect = boundsOf(event) ?: return
        if (rect.width() <= 0 || rect.height() <= 0) return
        val cx = rect.centerX()
        val cy = rect.centerY()
        val now = SystemClock.uptimeMillis()
        // 去重：无障碍可能对同一次点击派发多个事件。
        if (!longPress &&
            cx == lastClickCenterX && cy == lastClickCenterY && now - lastClickTimeMs < DEDUPE_MS
        ) {
            return
        }
        lastClickCenterX = cx
        lastClickCenterY = cy
        lastClickTimeMs = now

        val point = toPercent(cx, cy)
        val action = if (longPress) LongPressAction(point) else ClickAction(point)
        appendStep(action, now)
        RuntimeBus.log(if (longPress) "录制：长按 (${fmt(point.x)}, ${fmt(point.y)})" else "录制：点击 (${fmt(point.x)}, ${fmt(point.y)})")
    }

    private fun recordScroll(event: AccessibilityEvent) {
        val now = SystemClock.uptimeMillis()
        val vertical = scrollIsVertical(event)
        val forward = scrollIsForward(event)
        // 手指滑动方向与内容滚动方向相反：内容向下滚 → 手指向上滑。
        val from: PercentPoint
        val to: PercentPoint
        if (vertical) {
            if (forward) {
                from = PercentPoint(0.5f, 0.7f); to = PercentPoint(0.5f, 0.35f)
            } else {
                from = PercentPoint(0.5f, 0.35f); to = PercentPoint(0.5f, 0.7f)
            }
        } else {
            if (forward) {
                from = PercentPoint(0.75f, 0.5f); to = PercentPoint(0.3f, 0.5f)
            } else {
                from = PercentPoint(0.3f, 0.5f); to = PercentPoint(0.75f, 0.5f)
            }
        }
        appendStep(SwipeAction(from = from, to = to, durationMs = 300), now)
        RuntimeBus.log("录制：滑动（${if (vertical) "纵向" else "横向"}）")
    }

    private fun boundsOf(event: AccessibilityEvent): Rect? {
        val source = event.source
        if (source != null) {
            val rect = Rect()
            source.getBoundsInScreen(rect)
            if (!rect.isEmpty) return rect
        }
        return null
    }

    private fun scrollIsVertical(event: AccessibilityEvent): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val dx = event.scrollDeltaX
            val dy = event.scrollDeltaY
            if (dx != 0 || dy != 0) return kotlin.math.abs(dy) >= kotlin.math.abs(dx)
        }
        return true
    }

    private fun scrollIsForward(event: AccessibilityEvent): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val dy = event.scrollDeltaY
            val dx = event.scrollDeltaX
            val delta = if (kotlin.math.abs(dy) >= kotlin.math.abs(dx)) dy else dx
            if (delta != 0) return delta > 0
        }
        return event.toIndex >= event.fromIndex
    }

    private fun appendStep(action: com.autoclicker.domain.model.Action, now: Long) {
        if (autoRecordDelay) {
            val previousId = lastStepId
            if (previousId != null) {
                val gap = (now - lastEventTimeMs).coerceAtLeast(0L)
                val previous = RecorderBus.steps.value.firstOrNull { it.id == previousId }
                if (previous != null) RecorderBus.updateStep(previous.copy(delayAfterMs = gap))
            }
        }
        val step = StepNode(id = Ids.newId(), action = action)
        RecorderBus.addStep(step)
        lastStepId = step.id
        lastEventTimeMs = now
    }

    private fun toPercent(x: Int, y: Int): PercentPoint = PercentPoint(
        (x.toFloat() / screenWidth).coerceIn(0f, 1f),
        (y.toFloat() / screenHeight).coerceIn(0f, 1f),
    )

    // ---------------- 手动取点 ----------------

    /**
     * 进入一次性取点：屏幕出现半透明取点层，单击任意位置即补一个点击步骤并立即消失。
     * 仅用于补充无障碍事件无法覆盖的任意像素坐标。
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
                    RecorderBus.addStep(step)
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

    // ---------------- 状态角标 ----------------

    private fun showBadge() {
        if (badgeView != null) return
        val view = TextView(appContext).apply {
            text = "● 录制中"
            setTextColor(Color.WHITE)
            textSize = 11f
            setBackgroundColor(0xCCD32F2F.toInt())
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8)
            y = dp(8)
        }
        try {
            windowManager.addView(view, params)
            badgeView = view
        } catch (t: Throwable) {
            badgeView = null
            RuntimeBus.log(LogLevel.WARN, "录制角标创建失败：${t.message}")
        }
    }

    private fun hideBadge() {
        val view = badgeView ?: return
        badgeView = null
        try {
            windowManager.removeView(view)
        } catch (t: Throwable) {
            // 忽略移除异常。
        }
    }

    private fun hasOverlayPermission(): Boolean = Settings.canDrawOverlays(appContext)

    private fun observeSettings() {
        settingsScope?.cancel()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        settingsScope = scope
        scope.launch {
            ServiceLocator.settings.settings.collect { settings ->
                autoRecordDelay = settings.autoRecordDelay
            }
        }
    }

    private fun fmt(value: Float): String = "%.3f".format(value)

    companion object {
        private const val DEDUPE_MS = 300L

        @Volatile
        private var instance: Recorder? = null

        /** 当前录制器（供无障碍服务回调事件）。 */
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