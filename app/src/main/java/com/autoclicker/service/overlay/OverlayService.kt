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
import android.widget.Toast
import com.autoclicker.core.bus.LogEntry
import com.autoclicker.core.bus.LogLevel
import com.autoclicker.core.bus.PlaybackState
import com.autoclicker.core.bus.RecordingSession
import com.autoclicker.core.bus.RecordingState
import com.autoclicker.core.bus.RuntimeBus
import com.autoclicker.core.permission.PermissionChecker
import com.autoclicker.di.ServiceLocator
import com.autoclicker.domain.model.GroupNode
import com.autoclicker.domain.model.Script
import com.autoclicker.domain.model.ScriptNode
import com.autoclicker.domain.model.StepNode
import com.autoclicker.service.capture.TemplateCaptureOverlay
import com.autoclicker.service.record.Recorder
import com.autoclicker.ui.editor.actionSummary
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
 * 控制台：脚本选择 + 开始/暂停/停止、录制控制与手动取点、任务步骤直接编辑、实时日志与当前步骤。
 */
class OverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var ballView: TextView? = null
    private var consoleView: View? = null

    /** 悬浮球的窗口参数（拖动时原地更新，用于计算控制台位置避免重叠）。 */
    private var ballParams: WindowManager.LayoutParams? = null

    /** 悬浮球上次被隐藏时的位置，重新显示时恢复到原位置（截图模式会临时隐藏球）。 */
    private var lastBallX: Int? = null
    private var lastBallY: Int? = null

    /** 自动缩小去重：记录上一次播放/录制状态，仅在「切入」RUNNING/RECORDING 时收起一次。 */
    private var lastPlaybackState: PlaybackState = PlaybackState.IDLE
    private var lastRecordingState: RecordingState = RecordingState.IDLE

    private var stateText: TextView? = null
    private var stepText: TextView? = null
    /** 屏幕顶部的运行提示条（显示当前动作 + 延时），仅在运行/暂停时出现。 */
    private var statusBarView: TextView? = null
    private var logsText: TextView? = null
    private var logsScroll: ScrollView? = null
    private var stepsContainer: LinearLayout? = null
    private var recordStateText: TextView? = null
    private var recordTaskText: TextView? = null
    private var startButton: Button? = null
    private var pauseButton: Button? = null
    private var stopButton: Button? = null
    private var recordButton: Button? = null

    private var scriptNameText: TextView? = null
    private var scripts: List<Script> = emptyList()
    private var selectedScript: Script? = null

    /** 首页「运行」入口带过来的目标任务 id，任务列表首次加载时优先选中它。 */
    private var pendingScriptId: String? = null

    private var logsJob: Job? = null
    private var stateJob: Job? = null
    private var stepJob: Job? = null
    private var recordJob: Job? = null
    private var recorderStepsJob: Job? = null
    private var recordTaskJob: Job? = null
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
        observeStatusBar()
    }

    // ---------------- 屏幕顶部运行提示条 ----------------

    /**
     * 运行/暂停时在屏幕顶部显示「当前动作 + 延时」提示条，空闲/停止后自动消失。
     *
     * 提示条不可触摸（FLAG_NOT_TOUCHABLE），不会挡住脚本自身的点击操作。
     */
    private fun observeStatusBar() {
        scope.launch {
            RuntimeBus.state.collect { state ->
                val show = state == PlaybackState.RUNNING || state == PlaybackState.PAUSED
                if (show) {
                    showStatusBar()
                    refreshStatusBar()
                } else {
                    hideStatusBar()
                }
            }
        }
        scope.launch {
            RuntimeBus.currentStep.collect { refreshStatusBar() }
        }
    }

    private fun showStatusBar() {
        if (statusBarView != null) return
        if (!Settings.canDrawOverlays(this)) return
        val bar = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(STATUS_BAR_BG)
            }
            text = "运行中…"
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 不抢焦点、不接收触摸：只做展示，触摸事件穿透到下面的脚本操作。
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(40)
        }
        try {
            windowManager.addView(bar, params)
        } catch (t: Throwable) {
            RuntimeBus.log(LogLevel.WARN, "顶部运行提示创建失败：${t.message}")
            return
        }
        statusBarView = bar
    }

    private fun hideStatusBar() {
        val view = statusBarView ?: return
        statusBarView = null
        try {
            windowManager.removeView(view)
        } catch (t: Throwable) {
            // 忽略移除异常。
        }
    }

    private fun refreshStatusBar() {
        val bar = statusBarView ?: return
        val info = RuntimeBus.currentStep.value
        val state = RuntimeBus.state.value
        bar.text = when {
            info == null -> "运行中…"
            else -> {
                val prefix = if (state == PlaybackState.PAUSED) "已暂停" else "运行中"
                "$prefix ${info.stepIndex + 1}/${info.stepTotal}：${info.description} · 延时 ${info.delayAfterMs}ms"
            }
        }
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
        when (intent?.action) {
            // App 内发起「截图建模板」时用：缩起悬浮球，避免被截进模板图。
            ACTION_ENTER_CAPTURE -> {
                enterCaptureMode()
                return START_STICKY
            }

            ACTION_EXIT_CAPTURE -> {
                exitCaptureMode()
                return START_STICKY
            }
        }
        intent?.getStringExtra(EXTRA_SCRIPT_ID)?.let { pendingScriptId = it }
        showFloatingBall()
        // 带目标任务启动（首页「运行」入口）时先把该任务设为当前选中，长按悬浮球才会跑这个任务。
        if (pendingScriptId != null || consoleView != null) loadScripts()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        hideConsole()
        hideFloatingBall()
        hideStatusBar()
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
            x = lastBallX ?: dp(12)
            y = lastBallY ?: dp(160)
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
        // 记住位置，重新显示时回到原处。
        ballParams?.let {
            lastBallX = it.x
            lastBallY = it.y
        }
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
        // 任务编辑器是独立窗口，点悬浮球先收起编辑器（相当于「缩小」回只留球）。
        if (OverlayStepEditor.isOpen()) {
            OverlayStepEditor.close()
            return
        }
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
        // 编辑器占着屏幕时不要再叠一层控制台。
        if (OverlayStepEditor.isOpen()) OverlayStepEditor.close()
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

        val recordTaskRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val recordTask = TextView(this).apply {
            setTextColor(RECORD_COLOR)
            textSize = 12f
            text = recordTaskLabel()
        }
        recordTaskText = recordTask
        recordTaskRow.addView(
            recordTask,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        recordTaskRow.addView(button("重命名") { promptRenameTask() })
        root.addView(recordTaskRow)

        val recordRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val record = button("录制") {
            if (!RecordingSession.isActive) {
                Toast.makeText(this, "请先创建或打开一个任务，再开始录制", Toast.LENGTH_SHORT).show()
                return@button
            }
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
        recordRow.addView(button("+点击(取点)") {
            if (!PermissionChecker.requireOverlay(this)) return@button
            // 取点要看清整屏：先收起控制台（取点层本身会盖住悬浮球）。
            hideConsole()
            Recorder.pickPoint(this)
            RuntimeBus.log("控制台：进入取点模式")
        })
        root.addView(recordRow)

        val saveRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        saveRow.addView(button("保存到任务") {
            scope.launch {
                val ok = runCatching { RecordingSession.saveNow() }.getOrDefault(false)
                if (ok) RuntimeBus.log("控制台：已保存到任务")
            }
        })
        root.addView(saveRow)

        val taskEditRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        taskEditRow.addView(button("动作编辑") { openTaskEditor() })
        taskEditRow.addView(button("新建步骤组") { promptNewGroup() })
        root.addView(taskEditRow)

        // 截图建模板（悬浮窗内直接唤起截图裁剪层）：先收起控制台并隐藏悬浮球，否则球会被截进模板图。
        val templateRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        templateRow.addView(button("截图建模板") {
            if (!PermissionChecker.requireOverlay(this)) return@button
            enterCaptureMode()
            TemplateCaptureOverlay.start(this) { template, _ ->
                exitCaptureMode()
                if (template != null) RuntimeBus.log("控制台：已添加模板「${template.name}」")
            }
        })
        root.addView(templateRow)

        // 任务步骤（只读一览）
        root.addView(TextView(this).apply {
            text = "任务步骤（点「动作编辑」可直接改延时）"
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
        recordTaskJob?.cancel(); recordTaskJob = null
        val view = consoleView ?: return
        consoleView = null
        stateText = null
        stepText = null
        logsText = null
        logsScroll = null
        stepsContainer = null
        recordStateText = null
        recordTaskText = null
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
            // 优先选中首页「运行」入口带过来的任务，其次保持当前选择，最后回退到第一个任务。
            val preferred = pendingScriptId
            pendingScriptId = null
            selectedScript = scripts.firstOrNull { it.id == preferred }
                ?: scripts.firstOrNull { it.id == selectedScript?.id }
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
                    "步骤 ${info.stepIndex + 1}/${info.stepTotal}：${info.description} · 延时 ${info.delayAfterMs}ms"
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
            RecordingSession.nodes.collect { rebuildSteps() }
        }
        recordTaskJob = scope.launch {
            RecordingSession.scriptName.collect { recordTaskText?.text = recordTaskLabel() }
        }
    }

    // ---------------- 任务步骤（只读一览，编辑走「动作编辑」） ----------------

    private fun rebuildSteps() {
        val container = stepsContainer ?: return
        container.removeAllViews()
        val nodes = RecordingSession.nodes.value
        if (nodes.isEmpty()) {
            container.addView(smallText("（暂无任务步骤，点「动作编辑」添加）"))
            return
        }
        nodes.forEachIndexed { index, node ->
            container.addView(nodeRow(index, node))
        }
    }

    private fun nodeRow(index: Int, node: ScriptNode): View = when (node) {
        is StepNode -> stepRow(index, node)
        is GroupNode -> groupRow(index, node)
    }

    /** 只读展示：延时/次数在这里不再用加减按钮，改到「动作编辑」里直接输入。 */
    private fun stepRow(index: Int, step: StepNode): View = TextView(this).apply {
        text = "#${index + 1} ${actionSummary(step.action)}　延时${step.delayAfterMs}ms ×${step.repeatCount}"
        setTextColor(Color.WHITE)
        textSize = 11f
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun groupRow(index: Int, group: GroupNode): View = TextView(this).apply {
        text = "#${index + 1} [组] ${group.name.ifBlank { "步骤组" }} ×${group.loopCount}（${group.children.size} 步）"
        setTextColor(GROUP_COLOR)
        textSize = 11f
        setPadding(0, dp(4), 0, dp(4))
    }

    // ---------------- 任务编辑入口 ----------------

    private fun promptRenameTask() = openOverlayEditor(OverlayStepEditor.Mode.RENAME)

    private fun openTaskEditor() = openOverlayEditor(OverlayStepEditor.Mode.LIST)

    private fun promptNewGroup() = openOverlayEditor(OverlayStepEditor.Mode.GROUP)

    /** 在悬浮窗内直接编辑（不跳转 App）：先收起控制台，避免两个窗口叠在一起。 */
    private fun openOverlayEditor(mode: OverlayStepEditor.Mode) {
        if (!RecordingSession.isActive) {
            Toast.makeText(this, "请先创建或打开一个任务", Toast.LENGTH_SHORT).show()
            return
        }
        hideConsole()
        OverlayStepEditor.open(this, mode)
    }

    // ---------------- 截图模式（自动缩小/隐藏悬浮球） ----------------

    /**
     * 进入截图模式：收起控制台并隐藏悬浮球。
     * 悬浮窗会被截进屏幕采集结果，不收起的话模板里会带上悬浮球。
     */
    private fun enterCaptureMode() {
        hideConsole()
        hideFloatingBall()
    }

    /** 退出截图模式：恢复悬浮球（控制台保持收起，交给用户手动展开）。 */
    private fun exitCaptureMode() {
        showFloatingBall()
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

    private fun recordTaskLabel(): String =
        "录制任务：${RecordingSession.scriptName.value ?: "未绑定（请先在首页新建任务）"}"

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
        private val GROUP_COLOR = 0xFF4FC3F7.toInt()
        private val RECORD_COLOR = 0xFFFF8A80.toInt()
        private val STATUS_BAR_BG = 0xE01B1B1B.toInt()

        private const val BALL_SIZE_DP = 52
        private const val DRAG_SLOP_PX = 8f
        private const val LONG_PRESS_MS = 450L
        private const val CONSOLE_WIDTH_DP = 300
        private const val CONSOLE_GAP_DP = 8
        private const val LOG_VIEW_HEIGHT_DP = 150
        private const val STEPS_VIEW_HEIGHT_DP = 170
        private const val LOG_MAX_LINES = 120

        private const val EXTRA_SCRIPT_ID = "script_id"
        private const val ACTION_ENTER_CAPTURE = "enter_capture_mode"
        private const val ACTION_EXIT_CAPTURE = "exit_capture_mode"

        /** 启动悬浮球；[scriptId] 用于把某个任务设为悬浮球当前选中（长按悬浮球即运行它）。 */
        fun start(context: Context, scriptId: String? = null) {
            val intent = Intent(context, OverlayService::class.java)
            if (!scriptId.isNullOrBlank()) intent.putExtra(EXTRA_SCRIPT_ID, scriptId)
            context.startService(intent)
        }

        /** App 内发起截图建模板前调用：收起控制台并隐藏悬浮球，避免悬浮窗被截进画面。 */
        fun enterCaptureMode(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, OverlayService::class.java).setAction(ACTION_ENTER_CAPTURE),
                )
            }
        }

        /** 截图结束（成功或取消）后调用：恢复悬浮球。 */
        fun exitCaptureMode(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, OverlayService::class.java).setAction(ACTION_EXIT_CAPTURE),
                )
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }
}