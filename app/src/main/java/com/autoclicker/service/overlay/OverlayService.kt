package com.autoclicker.service.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.autoclicker.core.bus.LogEntry
import com.autoclicker.core.bus.LogLevel
import com.autoclicker.core.bus.PlaybackState
import com.autoclicker.core.bus.RecorderBus
import com.autoclicker.core.bus.RecordingState
import com.autoclicker.core.bus.RuntimeBus
import com.autoclicker.core.permission.PermissionChecker
import com.autoclicker.di.ServiceLocator
import com.autoclicker.domain.model.Action
import com.autoclicker.domain.model.ClickAction
import com.autoclicker.domain.model.GestureAction
import com.autoclicker.domain.model.GlobalKeyAction
import com.autoclicker.domain.model.InputTextAction
import com.autoclicker.domain.model.LongPressAction
import com.autoclicker.domain.model.Script
import com.autoclicker.domain.model.StepNode
import com.autoclicker.domain.model.SwipeAction
import com.autoclicker.service.capture.TemplateCaptureOverlay
import com.autoclicker.service.record.Recorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 悬浮窗服务：可拖动悬浮球 + 运行控制台。
 *
 * 悬浮球：单击展开/收起控制台，长按快速开始/停止当前脚本；球体颜色与图标随运行/录制状态变化。
 * 控制台：脚本选择 + 开始/暂停/停止、录制控制与手动取点、录制步骤直接编辑、实时日志与当前步骤。
 */
class OverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var ballView: TextView? = null
    private var consoleView: View? = null

    /** 悬浮球的窗口参数（拖动时原地更新，用于计算控制台位置避免重叠）。 */
    private var ballParams: WindowManager.LayoutParams? = null

    /** 自动缩小去重：记录上一次播放/录制状态，仅在「切入」RUNNING/RECORDING 时收起一次。 */
    private var lastPlaybackState: PlaybackState = PlaybackState.IDLE
    private var lastRecordingState: RecordingState = RecordingState.IDLE

    private var stateText: TextView? = null
    private var stepText: TextView? = null
    private var logsText: TextView? = null
    private var logsScroll: ScrollView? = null
    private var stepsContainer: LinearLayout? = null
    private var recordStateText: TextView? = null
    private var startButton: Button? = null
    private var pauseButton: Button? = null
    private var stopButton: Button? = null
    private var recordButton: Button? = null

    private var scriptNameText: TextView? = null
    private var scripts: List<Script> = emptyList()
    private var selectedScript: Script? = null

    private var logsJob: Job? = null
    private var stateJob: Job? = null
    private var stepJob: Job? = null
    private var recordJob: Job? = null
    private var recorderStepsJob: Job? = null
    private var ballStateJob: Job? = null

    private lateinit var controller: OverlayControllerImpl
    private lateinit var windowManager: WindowManager

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        controller = OverlayControllerImpl(
            showBallAction = { showFloatingBall() },
            hideBallAction = { hideFloatingBall() },
            ballVisible = { ballView != null },
            showConsoleAction = { showConsole() },
            hideConsoleAction = { hideConsole() },
        )
        ServiceLocator.overlay = controller
        observeAutoShrink()
    }

    /**
     * 自动缩小：播放切入 [PlaybackState.RUNNING] 或录制切入 [RecordingState.RECORDING] 时收起控制台。
     * 仅在状态发生「切入」的那一次触发，用户手动展开后不会被再次收起，直到状态下次重新切入。
     */
    private fun observeAutoShrink() {
        scope.launch {
            RuntimeBus.state.collect { state ->
                val enteredRunning = state == PlaybackState.RUNNING && lastPlaybackState != PlaybackState.RUNNING
                lastPlaybackState = state
                if (enteredRunning) hideConsole()
            }
        }
        scope.launch {
            RuntimeBus.recording.collect { state ->
                val enteredRecording = state == RecordingState.RECORDING && lastRecordingState != RecordingState.RECORDING
                lastRecordingState = state
                if (enteredRecording) hideConsole()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        showFloatingBall()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        hideConsole()
        hideFloatingBall()
        scope.cancel()
        if (ServiceLocator.overlay === controller) ServiceLocator.overlay = null
        super.onDestroy()
    }

    // ---------------- 悬浮球 ----------------

    private fun showFloatingBall() {
        if (ballView != null) return
        if (!Settings.canDrawOverlays(this)) {
            RuntimeBus.log(LogLevel.WARN, "无悬浮窗权限，无法显示悬浮球")
            return
        }
        val size = dp(BALL_SIZE_DP)
        val ball = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 18f
            background = oval(ballColor(), strokeWidthDp = 2)
            text = ballIcon()
        }
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(160)
        }
        ball.setOnTouchListener(BallTouchListener(params))
        try {
            windowManager.addView(ball, params)
        } catch (t: Throwable) {
            RuntimeBus.log(LogLevel.ERROR, "悬浮球创建失败：${t.message}")
            return
        }
        ballView = ball
        ballParams = params
        observeBallState()
        RuntimeBus.log("悬浮球已显示（单击展开控制台，长按快速开始/停止）")
    }

    private fun hideFloatingBall() {
        ballStateJob?.cancel()
        ballStateJob = null
        val view = ballView ?: return
        ballView = null
        ballParams = null
        try {
            windowManager.removeView(view)
        } catch (t: Throwable) {
            // 忽略移除异常。
        }
    }

    private fun observeBallState() {
        ballStateJob?.cancel()
        ballStateJob = scope.launch {
            launch { RuntimeBus.state.collect { refreshBall() } }
            launch { RuntimeBus.recording.collect { refreshBall() } }
        }
    }

    private fun refreshBall() {
        ballView?.let { ball ->
            val color = ballColor()
            ball.background = oval(color, strokeWidthDp = 2)
            ball.text = ballIcon()
        }
    }

    private fun ballColor(): Int {
        if (RuntimeBus.recording.value != RecordingState.IDLE) return COLOR_RECORDING
        return when (RuntimeBus.state.value) {
            PlaybackState.RUNNING -> COLOR_RUNNING
            PlaybackState.PAUSED -> COLOR_PAUSED
            PlaybackState.ERROR -> COLOR_ERROR
            else -> COLOR_IDLE
        }
    }

    private fun ballIcon(): String {
        if (RuntimeBus.recording.value != RecordingState.IDLE) return "●"
        return when (RuntimeBus.state.value) {
            PlaybackState.RUNNING -> "▶"
            PlaybackState.PAUSED -> "‖"
            PlaybackState.ERROR -> "!"
            else -> "≡"
        }
    }

    private inner class BallTouchListener(
        private val params: WindowManager.LayoutParams,
    ) : View.OnTouchListener {

        private var startX = 0
        private var startY = 0
        private var touchX = 0f
        private var touchY = 0f
        private var moved = false
        private var downTime = 0L

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    downTime = SystemClock.uptimeMillis()
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (abs(dx) > DRAG_SLOP_PX || abs(dy) > DRAG_SLOP_PX) moved = true
                    params.x = startX + dx.toInt()
                    params.y = startY + dy.toInt()
                    runCatching { windowManager.updateViewLayout(view, params) }
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    val held = SystemClock.uptimeMillis() - downTime
                    if (!moved) {
                        if (held >= LONG_PRESS_MS) quickToggle() else toggleConsole()
                    }
                    return true
                }
            }
            return false
        }
    }

    private fun toggleConsole() {
        if (consoleView == null) showConsole() else hideConsole()
    }

    /** 长按悬浮球：运行中/暂停时停止，否则开始选中（或首个）脚本。 */
    private fun quickToggle() {
        val state = RuntimeBus.state.value
        if (state == PlaybackState.RUNNING || state == PlaybackState.PAUSED) {
            ServiceLocator.player?.stop()
            RuntimeBus.log("悬浮球：停止")
            return
        }
        scope.launch {
            val script = selectedScript ?: ServiceLocator.scripts.list().firstOrNull()
            if (script == null) {
                RuntimeBus.log(LogLevel.WARN, "悬浮球：没有可用任务")
                return@launch
            }
            selectedScript = script
            RuntimeBus.log("悬浮球：运行「${script.name}」")
            ServiceLocator.player?.play(script)
        }
    }

    // ---------------- 运行控制台 ----------------

    private fun showConsole() {
        if (consoleView != null) return
        if (!Settings.canDrawOverlays(this)) {
            RuntimeBus.log(LogLevel.WARN, "无悬浮窗权限，无法显示控制台")
            return
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(CONSOLE_BG)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        // 标题行：左侧标题 + 右侧醒目的「缩小」按钮（收起控制台，仅留悬浮球）
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(
            TextView(this).apply {
                text = "运行控制台"
                setTextColor(Color.WHITE)
                textSize = 14f
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        titleRow.addView(button("缩小") { hideConsole() })
        root.addView(titleRow)

        // 脚本选择（悬浮窗内不使用弹出菜单，避免不可聚焦窗口收不到点击）
        val scriptRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val scriptName = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            text = "任务：--"
        }
        scriptNameText = scriptName
        scriptRow.addView(
            scriptName,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        scriptRow.addView(button("切换") { cycleScript() })
        scriptRow.addView(button("刷新") { loadScripts() })
        root.addView(scriptRow)

        val state = TextView(this).apply {
            setTextColor(STATE_COLOR)
            textSize = 12f
            text = "状态：${stateLabel(RuntimeBus.state.value)}"
        }
        stateText = state
        root.addView(state)

        val step = TextView(this).apply {
            setTextColor(STEP_COLOR)
            textSize = 12f
            text = "步骤：--"
        }
        stepText = step
        root.addView(step)

        // 播放控制
        val playRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val start = button("开始") { startSelectedScript() }
        val pause = button("暂停") {
            val player = ServiceLocator.player
            if (RuntimeBus.state.value == PlaybackState.PAUSED) {
                player?.resume()
                RuntimeBus.log("控制台：继续")
            } else {
                player?.pause()
                RuntimeBus.log("控制台：暂停")
            }
        }
        val stop = button("停止") {
            ServiceLocator.player?.stop()
            RuntimeBus.log("控制台：停止")
        }
        startButton = start
        pauseButton = pause
        stopButton = stop
        playRow.addView(start)
        playRow.addView(pause)
        playRow.addView(stop)
        root.addView(playRow)

        // 录制控制
        val recordState = TextView(this).apply {
            setTextColor(RECORD_COLOR)
            textSize = 12f
            text = "录制：${recordingLabel(RuntimeBus.recording.value)}"
        }
        recordStateText = recordState
        root.addView(recordState)

        val recordRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val record = button("录制") {
            if (!PermissionChecker.requireAccessibility(this)) return@button
            if (Recorder.isRecording()) Recorder.stop(this) else Recorder.start(this)
            RuntimeBus.log(if (Recorder.isRecording()) "控制台：录制开始" else "控制台：录制停止")
        }
        recordButton = record
        recordRow.addView(record)
        recordRow.addView(button("暂停录") {
            if (!PermissionChecker.requireAccessibility(this)) return@button
            if (Recorder.isRecording()) Recorder.pause(this)
        })
        recordRow.addView(button("取点") {
            if (!PermissionChecker.requireOverlay(this)) return@button
            Recorder.pickPoint(this)
            RuntimeBus.log("控制台：进入取点模式")
        })
        root.addView(recordRow)

        // 截图建模板（悬浮窗内直接唤起截图裁剪层）
        val templateRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        templateRow.addView(button("截图建模板") {
            if (!PermissionChecker.requireOverlay(this)) return@button
            TemplateCaptureOverlay.start(this) { template ->
                if (template != null) RuntimeBus.log("控制台：已添加模板「${template.name}」")
            }
        })
        root.addView(templateRow)

        // 已录制步骤（可直接修改）
        root.addView(TextView(this).apply {
            text = "已录制步骤（可直接修改）"
            setTextColor(STEP_COLOR)
            textSize = 12f
        })
        val steps = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        stepsContainer = steps
        val stepsScroll = ScrollView(this).apply { addView(steps) }
        root.addView(
            stepsScroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(STEPS_VIEW_HEIGHT_DP)),
        )

        // 日志
        val logsView = TextView(this).apply {
            setTextColor(Color.parseColor("#DDDDDD"))
            textSize = 10f
            typeface = Typeface.MONOSPACE
        }
        logsText = logsView
        val scroll = ScrollView(this).apply { addView(logsView) }
        logsScroll = scroll
        root.addView(
            scroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(LOG_VIEW_HEIGHT_DP)),
        )

        val params = WindowManager.LayoutParams(
            dp(CONSOLE_WIDTH_DP),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(160)
        }
        try {
            windowManager.addView(root, params)
        } catch (t: Throwable) {
            RuntimeBus.log(LogLevel.ERROR, "控制台创建失败：${t.message}")
            return
        }
        consoleView = root
        // 测量后再定位，确保控制台矩形与悬浮球矩形不相交。
        root.post { applyConsolePosition(root, params) }

        loadScripts()
        renderLogs(RuntimeBus.logs.value)
        rebuildSteps()
        startCollectors()
    }

    private fun hideConsole() {
        logsJob?.cancel(); logsJob = null
        stateJob?.cancel(); stateJob = null
        stepJob?.cancel(); stepJob = null
        recordJob?.cancel(); recordJob = null
        recorderStepsJob?.cancel(); recorderStepsJob = null
        val view = consoleView ?: return
        consoleView = null
        stateText = null
        stepText = null
        logsText = null
        logsScroll = null
        stepsContainer = null
        recordStateText = null
        startButton = null
        pauseButton = null
        stopButton = null
        recordButton = null
        scriptNameText = null
        try {
            windowManager.removeView(view)
        } catch (t: Throwable) {
            // 忽略移除异常。
        }
    }

    /**
     * 依据悬浮球当前位置计算控制台窗口的 x/y，使两者矩形不相交：
     * 优先放在球下方（ballY + ballSize + gap），下方空间不足则放到球上方（ballY - consoleHeight - gap），
     * 最后对屏幕边界做 clamp（x 同理）。
     */
    private fun applyConsolePosition(view: View, params: WindowManager.LayoutParams) {
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val consoleW = params.width
        val consoleH = if (view.height > 0) view.height else view.measuredHeight
        val ball = ballParams
        val hasBall = ball != null && ballView != null

        var x = if (hasBall) ball!!.x else params.x
        var y = if (hasBall) ball!!.y else params.y

        if (hasBall && consoleH > 0) {
            val ballSize = dp(BALL_SIZE_DP)
            val gap = dp(CONSOLE_GAP_DP)
            val belowY = ball!!.y + ballSize + gap
            val aboveY = ball!!.y - consoleH - gap
            y = when {
                belowY + consoleH <= screenH -> belowY
                aboveY >= 0 -> aboveY
                else -> belowY
            }
        } else if (hasBall) {
            y = ball!!.y + dp(BALL_SIZE_DP) + dp(CONSOLE_GAP_DP)
        }

        val maxX = (screenW - consoleW).coerceAtLeast(0)
        val maxY = (screenH - consoleH).coerceAtLeast(0)
        params.x = x.coerceIn(0, maxX)
        params.y = y.coerceIn(0, maxY)
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private fun loadScripts() {
        scope.launch {
            scripts = runCatching { ServiceLocator.scripts.list() }.getOrDefault(emptyList())
            selectedScript = scripts.firstOrNull { it.id == selectedScript?.id }
                ?: scripts.firstOrNull()
            updateScriptLabel()
        }
    }

    private fun cycleScript() {
        if (scripts.isEmpty()) {
            loadScripts()
            return
        }
        val currentIndex = scripts.indexOfFirst { it.id == selectedScript?.id }
        val nextIndex = if (currentIndex < 0) 0 else (currentIndex + 1) % scripts.size
        selectedScript = scripts[nextIndex]
        updateScriptLabel()
    }

    private fun updateScriptLabel() {
        scriptNameText?.text = "任务：${selectedScript?.name ?: "--（点击刷新）"}"
    }

    private fun startSelectedScript() {
        if (!PermissionChecker.requireAccessibility(this)) return
        scope.launch {
            val script = selectedScript ?: scripts.firstOrNull()
            if (script == null) {
                RuntimeBus.log(LogLevel.WARN, "控制台：请先选择任务")
                return@launch
            }
            selectedScript = script
            RuntimeBus.log("控制台：运行「${script.name}」")
            ServiceLocator.player?.play(script)
        }
    }

    private fun startCollectors() {
        logsJob = scope.launch {
            RuntimeBus.logs.collect { logs -> renderLogs(logs) }
        }
        stateJob = scope.launch {
            RuntimeBus.state.collect { state ->
                stateText?.text = "状态：${stateLabel(state)}"
                val running = state == PlaybackState.RUNNING || state == PlaybackState.PAUSED
                startButton?.isEnabled = !running
                pauseButton?.isEnabled = running
                stopButton?.isEnabled = running
                pauseButton?.text = if (state == PlaybackState.PAUSED) "继续" else "暂停"
            }
        }
        stepJob = scope.launch {
            RuntimeBus.currentStep.collect { info ->
                stepText?.text = if (info == null) {
                    "步骤：--"
                } else {
                    "步骤 ${info.stepIndex + 1}/${info.stepTotal}：${info.description}"
                }
            }
        }
        recordJob = scope.launch {
            RuntimeBus.recording.collect { state ->
                recordStateText?.text = "录制：${recordingLabel(state)}"
                recordButton?.text = if (state == RecordingState.IDLE) "录制" else "停止录"
            }
        }
        recorderStepsJob = scope.launch {
            RecorderBus.steps.collect { rebuildSteps() }
        }
    }

    // ---------------- 步骤列表（可直接修改） ----------------

    private fun rebuildSteps() {
        val container = stepsContainer ?: return
        container.removeAllViews()
        val steps = RecorderBus.steps.value
        if (steps.isEmpty()) {
            container.addView(smallText("（暂无录制步骤）"))
            return
        }
        steps.forEachIndexed { index, step ->
            container.addView(stepRow(index, step))
        }
    }

    private fun stepRow(index: Int, step: StepNode): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        row.addView(TextView(this).apply {
            text = "#${index + 1} ${summarize(step.action)}"
            setTextColor(Color.WHITE)
            textSize = 11f
        })
        val editRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        editRow.addView(button("延时-") { adjustDelay(step, -DELAY_STEP_MS) })
        editRow.addView(TextView(this).apply {
            text = "${step.delayAfterMs}ms"
            setTextColor(Color.parseColor("#BBBBBB"))
            textSize = 11f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
        })
        editRow.addView(button("延时+") { adjustDelay(step, DELAY_STEP_MS) })
        editRow.addView(button("次数-") { adjustRepeat(step, -1) })
        editRow.addView(TextView(this).apply {
            text = "×${step.repeatCount}"
            setTextColor(Color.parseColor("#BBBBBB"))
            textSize = 11f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
        })
        editRow.addView(button("次数+") { adjustRepeat(step, 1) })
        editRow.addView(button("删除") { RecorderBus.removeStep(step.id) })
        row.addView(editRow)
        return row
    }

    private fun adjustDelay(step: StepNode, delta: Long) {
        val value = (step.delayAfterMs + delta).coerceAtLeast(0L)
        RecorderBus.updateStep(step.copy(delayAfterMs = value))
    }

    private fun adjustRepeat(step: StepNode, delta: Int) {
        val value = (step.repeatCount + delta).coerceAtLeast(1)
        RecorderBus.updateStep(step.copy(repeatCount = value))
    }

    private fun renderLogs(logs: List<LogEntry>) {
        val text = logs.takeLast(LOG_MAX_LINES).joinToString("\n") { entry ->
            "${timeFormat.format(Date(entry.timeMs))} ${entry.level} ${entry.message}"
        }
        logsText?.text = text
        logsScroll?.post { logsScroll?.fullScroll(View.FOCUS_DOWN) }
    }

    // ---------------- 工具 ----------------

    private fun oval(color: Int, strokeWidthDp: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(dp(strokeWidthDp), Color.WHITE)
        }

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 11f
            minimumWidth = 0
            minimumHeight = 0
            setPadding(dp(8), dp(2), dp(8), dp(2))
            setOnClickListener { onClick() }
        }

    private fun smallText(value: String): TextView = TextView(this).apply {
        text = value
        setTextColor(Color.parseColor("#999999"))
        textSize = 11f
    }

    private fun stateLabel(state: PlaybackState): String = when (state) {
        PlaybackState.IDLE -> "空闲"
        PlaybackState.RUNNING -> "运行中"
        PlaybackState.PAUSED -> "已暂停"
        PlaybackState.STOPPED -> "已停止"
        PlaybackState.ERROR -> "错误"
    }

    private fun recordingLabel(state: RecordingState): String = when (state) {
        RecordingState.IDLE -> "空闲"
        RecordingState.RECORDING -> "录制中"
        RecordingState.PAUSED -> "已暂停"
    }

    private fun summarize(action: Action): String = when (action) {
        is ClickAction -> "点击 (${fmt(action.point.x)}, ${fmt(action.point.y)})"
        is LongPressAction -> "长按 (${fmt(action.point.x)}, ${fmt(action.point.y)})"
        is SwipeAction -> "滑动"
        is GestureAction -> "手势"
        is GlobalKeyAction -> "全局键 ${action.key}"
        is InputTextAction -> "输入文字"
        else -> action::class.simpleName ?: "动作"
    }

    private fun fmt(value: Float): String = (value * 1000f).roundToInt().let { "${it / 10f}%" }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    companion object {
        private val COLOR_IDLE = 0xFF2196F3.toInt()
        private val COLOR_RUNNING = 0xFF12B76A.toInt()
        private val COLOR_PAUSED = 0xFFEF8C00.toInt()
        private val COLOR_ERROR = 0xFFD32F2F.toInt()
        private val COLOR_RECORDING = 0xFFE91E63.toInt()
        private val CONSOLE_BG = 0xF0000000.toInt()
        private val STATE_COLOR = 0xFF8BC34A.toInt()
        private val STEP_COLOR = 0xFFFFC107.toInt()
        private val RECORD_COLOR = 0xFFFF8A80.toInt()

        private const val BALL_SIZE_DP = 52
        private const val DRAG_SLOP_PX = 8f
        private const val LONG_PRESS_MS = 450L
        private const val CONSOLE_WIDTH_DP = 300
        private const val CONSOLE_GAP_DP = 8
        private const val LOG_VIEW_HEIGHT_DP = 150
        private const val STEPS_VIEW_HEIGHT_DP = 170
        private const val LOG_MAX_LINES = 120
        private const val DELAY_STEP_MS = 100L

        fun start(context: Context) {
            context.startService(Intent(context, OverlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }
}