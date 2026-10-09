package com.autoclicker.core.overlay

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.autoclicker.MainActivity
import com.autoclicker.R
import com.autoclicker.core.accessibility.AutoAccessService
import com.autoclicker.core.clicker.ClickerConfig
import com.autoclicker.core.clicker.ClickerEngine
import com.autoclicker.core.clicker.ClickerExporter
import com.autoclicker.core.clicker.ClickerPoint
import com.autoclicker.core.clicker.ClickerState
import com.autoclicker.core.recorder.ScriptRecorder
import com.autoclicker.core.runner.RunnerState
import com.autoclicker.core.runner.ScriptRunner
import com.autoclicker.core.script.Script
import com.autoclicker.core.script.ScriptRepository
import com.autoclicker.core.script.Step
import com.autoclicker.core.script.withNewId
import com.autoclicker.core.util.NotificationChannels
import com.autoclicker.core.util.PermissionChecker
import com.autoclicker.core.vision.CapturePermissionActivity
import com.autoclicker.core.vision.RegionPickerBridge
import com.autoclicker.core.vision.ScreenCaptureService
import com.autoclicker.core.vision.VisionBridge
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 悬浮窗前台服务：承载悬浮球与控制面板，并驱动脚本执行 / 录制。
 */
class OverlayService : Service() {

    companion object {
        const val ACTION_START = "com.autoclicker.action.OVERLAY_START"
        const val ACTION_STOP = "com.autoclicker.action.OVERLAY_STOP"
        const val ACTION_START_PICK = "com.autoclicker.action.OVERLAY_START_PICK"
        const val ACTION_START_REGION_PICK = "com.autoclicker.action.REGION_PICK"
        const val CHANNEL_ID = "overlay_channel"
        const val NOTIFICATION_ID = 1002

        /** 拖动判定阈值（像素），小于该距离视为点击。 */
        private const val DRAG_SLOP = 10

        @Volatile
        var isRunning: Boolean = false
            private set

        /** 启动悬浮窗（显示悬浮球）。 */
        fun start(context: Context) {
            isRunning = true
            ContextCompat.startForegroundService(
                context,
                Intent(context, OverlayService::class.java).setAction(ACTION_START)
            )
        }

        /** 停止悬浮窗并结束服务。 */
        fun stop(context: Context) {
            try {
                context.stopService(
                    Intent(context, OverlayService::class.java).setAction(ACTION_STOP)
                )
            } catch (e: Exception) {
                // 忽略
            }
            isRunning = false
        }

        /** 进入全屏坐标拾取模式。 */
        fun startPickPoint(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, OverlayService::class.java).setAction(ACTION_START_PICK)
            )
        }

        /** 在目标 App 上方全屏框选一个矩形，结果通过 RegionPickerBridge 交回编辑器。 */
        fun startRegionPick(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, OverlayService::class.java).setAction(ACTION_START_REGION_PICK)
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 悬浮窗三态：小球 / 迷你条 / 面板。 */
    private enum class OverlayMode { BALL, MINI_BAR, PANEL }

    private val windowManager: WindowManager
        get() = getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var mode = OverlayMode.BALL
    private var ballView: View? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var panelView: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var miniBarView: View? = null
    private var miniBarParams: WindowManager.LayoutParams? = null
    private var pickView: View? = null
    private var regionPickView: View? = null
    private var captureLayerView: View? = null
    private var captureLayerParams: WindowManager.LayoutParams? = null
    private var subscribed = false

    private var markerView: View? = null
    private var markerParams: WindowManager.LayoutParams? = null
    private var controlBarView: View? = null
    private var controlBarParams: WindowManager.LayoutParams? = null
    private var clickerSubscribed = false

    private val panelCallbacks = object : OverlayUi.PanelCallbacks {
        override fun onStartClick(script: Script?) = startScript(script)
        override fun onPauseClick() = ScriptRunner.pause()
        override fun onResumeClick() = ScriptRunner.resume()
        override fun onStopClick() = ScriptRunner.stop()
        override fun onStartRecord() {
            collapseForAutomation()
            if (!AutoAccessService.isConnected) {
                toast("请先在无障碍设置中开启服务，再开始录制")
                PermissionChecker.openAccessibilitySettings(this@OverlayService)
                return
            }
            updateRecorderIgnoredRegion()
            ScriptRecorder.start()
            if (ScriptRecorder.preciseMode.value) {
                showCaptureLayer()
                toast("精确录制已开始：触摸会被采集并回放，打字请先暂停录制")
            } else {
                toast("录制已开始，请切到目标 App 操作")
            }
        }
        override fun onStopRecord() = finishRecording()
        override fun onTogglePreciseMode() {
            val next = !ScriptRecorder.preciseMode.value
            ScriptRecorder.setPreciseMode(next)
            toast(if (next) "已开启精确录制" else "已关闭精确录制")
            updateStatus()
        }
        override fun onToggleRecordPause() {
            val next = !ScriptRecorder.recordPaused.value
            ScriptRecorder.setRecordPaused(next)
            if (next) {
                // 暂停：移除采集层，触摸直接落到目标 App（打字所需），
                // 同时 ScriptRecorder.onCaptureTouch 不再被调用，语义正确。
                hideCaptureLayer()
                toast("录制已暂停（触摸将直接作用于 App）")
            } else {
                if (ScriptRecorder.isRecording.value && ScriptRecorder.preciseMode.value) {
                    showCaptureLayer()
                }
                toast("录制已继续")
            }
            updateStatus()
        }
        override fun onRefreshScripts() = refreshScripts()
        override fun onOpenApp() = openApp()
        override fun onCloseOverlay() = OverlayService.stop(this@OverlayService)
        override fun onScriptSelected(script: Script?) = refreshSteps(script)
        override fun onEditStep(script: Script?, index: Int) = editStep(script, index)
        override fun onMoveStep(script: Script?, index: Int, delta: Int) = moveStep(script, index, delta)
        override fun onDeleteStep(script: Script?, index: Int) = deleteStep(script, index)
        override fun onDuplicateStep(script: Script?, index: Int) = duplicateStep(script, index)
        override fun onCaptureTemplate() = captureTemplate()
        override fun onOpenClicker() = showClicker()
        override fun onCollapseMiniBar() = setMode(OverlayMode.MINI_BAR)
        override fun onExpandPanel() = setMode(OverlayMode.PANEL)
        override fun onHideToBall() = setMode(OverlayMode.BALL)
    }

    private val clickerCallbacks = object : ClickerOverlay.Callbacks {
        override fun onAddPoint(x: Float, y: Float) {
            ClickerEngine.addPoint(x, y)
        }

        override fun onMovePoint(id: String, x: Float, y: Float) {
            val point = ClickerEngine.config.value.points.firstOrNull { it.id == id } ?: return
            ClickerEngine.updatePoint(
                point.copy(x = x.coerceAtLeast(0f), y = y.coerceAtLeast(0f))
            )
        }

        override fun onEditPoint(point: ClickerPoint) {
            ClickerOverlay.showPointEditor(
                this@OverlayService,
                windowManager,
                point,
                onConfirm = { ClickerEngine.updatePoint(it) },
                onDelete = { ClickerEngine.removePoint(point.id) }
            )
        }

        override fun onStartClicker() {
            if (!AutoAccessService.isConnected) {
                toast("请先在无障碍设置中开启服务")
                PermissionChecker.openAccessibilitySettings(this@OverlayService)
                return
            }
            collapseForAutomation()
            if (!ClickerEngine.start()) {
                toast("启动失败：请先添加点击点")
            }
        }

        override fun onStopClicker() = ClickerEngine.stop()

        override fun onClearPoints() {
            ClickerEngine.clearPoints()
            toast("已清空点击点")
        }

        override fun onLoopSettings(config: ClickerConfig) {
            ClickerOverlay.showLoopSettings(this@OverlayService, windowManager, config) { inf, cnt, iv ->
                ClickerEngine.updateLoop(inf, cnt, iv)
            }
        }

        override fun onExportScript() {
            val cfg = ClickerEngine.config.value
            if (cfg.points.isEmpty()) {
                toast("请先添加点击点")
                return
            }
            val name = "点击器 " + SimpleDateFormat(
                "MM-dd HH:mm",
                Locale.getDefault()
            ).format(Date())
            val script = ClickerExporter.toScript(cfg, name)
            ScriptRepository.get(this@OverlayService).save(script)
            toast("已保存脚本：${script.name}")
            refreshScripts()
        }

        override fun onExitClicker() = hideClicker()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 必须在 5 秒内调用，无论什么 action。
        startForeground(NOTIFICATION_ID, buildNotification())

        // 悬浮窗权限兜底：未授权则跳转授权页并退出（已 startForeground，故先 stopForeground）。
        if (!PermissionChecker.isOverlayGranted(this)) {
            PermissionChecker.openOverlaySettings(this)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return Service.START_NOT_STICKY
        }

        subscribeState()

        when (intent?.action) {
            ACTION_STOP -> {
                hideAll()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                isRunning = false
            }

            ACTION_START_PICK -> {
                isRunning = true
                showPickOverlay()
            }

            ACTION_START_REGION_PICK -> {
                isRunning = true
                collapseForAutomation()
                showRegionPickOverlay()
            }

            else -> {
                isRunning = true
                showBall()
            }
        }

        return Service.START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        hideAll()
        scope.cancel()
        subscribed = false
        isRunning = false
        super.onDestroy()
    }

    // ---- 状态订阅 ----

    private fun subscribeState() {
        if (subscribed) return
        subscribed = true
        scope.launch { ScriptRunner.state.collect { updateStatus() } }
        scope.launch { ScriptRecorder.isRecording.collect { updateStatus() } }
        scope.launch { ScriptRecorder.stepCount.collect { updateStatus() } }
        scope.launch { ScriptRecorder.preciseMode.collect { updateStatus() } }
        scope.launch { ScriptRecorder.recordPaused.collect { updateStatus() } }
    }

    private fun updateStatus() {
        val state = ScriptRunner.state.value
        val recording = ScriptRecorder.isRecording.value
        val stepCount = ScriptRecorder.stepCount.value
        val clickerState = ClickerEngine.state.value
        val clickerRunning = ClickerEngine.isRunning
        val active = state.isActive || recording || clickerRunning
        val statusText = OverlayUi.formatStatus(state, recording, stepCount)

        val panel = panelView
        if (panel != null) {
            OverlayUi.setStatus(panel, statusText)
            OverlayUi.setPanelControls(
                panel,
                state,
                recording,
                clickerRunning,
                ScriptRecorder.preciseMode.value,
                ScriptRecorder.recordPaused.value
            )
            val stepIndex = when (state) {
                is RunnerState.Running -> state.stepIndex
                else -> -1
            }
            OverlayUi.highlightStep(panel, stepIndex)
        }

        val miniBar = miniBarView
        if (miniBar != null) {
            val miniText = buildString {
                append(statusText)
                if (recording) append("｜录制中(${stepCount})")
                if (ScriptRecorder.recordPaused.value) append("｜已暂停")
            }
            OverlayUi.setMiniBarStatus(miniBar, miniText, active)
        }

        val ball = ballView
        if (ball != null) {
            val ballRunning = state.isActive || clickerRunning
            val ballText = when {
                state is RunnerState.Running -> (state.stepIndex + 1).toString()
                clickerState is ClickerState.Running -> (clickerState.pointIndex + 1).toString()
                else -> ""
            }
            OverlayUi.setBallStatus(ball, ballText, ballRunning)
        }
    }

    // ---- 悬浮球 / 面板 ----

    private fun showBall() {
        if (ballView != null) return
        val size = OverlayUi.dp(this, 48f)
        val view = OverlayUi.createBall(this)
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        val metrics = resources.displayMetrics
        params.x = (metrics.widthPixels - size - OverlayUi.dp(this, 8f)).coerceAtLeast(0)
        params.y = metrics.heightPixels / 2

        attachBallTouch(view, params)
        if (!addView(view, params)) return
        ballView = view
        ballParams = params
        updateRecorderIgnoredRegion()
    }

    private fun attachBallTouch(view: View, params: WindowManager.LayoutParams) {
        var startX = 0
        var startY = 0
        var downRawX = 0f
        var downRawY = 0f
        var dragged = false

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    downRawX = event.rawX
                    downRawY = event.rawY
                    dragged = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (abs(dx) > DRAG_SLOP || abs(dy) > DRAG_SLOP) {
                        dragged = true
                    }
                    params.x = startX + dx
                    params.y = startY + dy
                    try {
                        windowManager.updateViewLayout(view, params)
                    } catch (e: Exception) {
                        // 窗口失效时忽略
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!dragged) togglePanel()
                    updateRecorderIgnoredRegion()
                    true
                }

                else -> false
            }
        }
    }

    private fun togglePanel() {
        when (mode) {
            OverlayMode.BALL -> setMode(OverlayMode.PANEL)
            else -> setMode(OverlayMode.BALL)
        }
    }

    /** 切换三态：始终保留悬浮球，按目标态增删面板 / 迷你条。 */
    private fun setMode(newMode: OverlayMode) {
        showBall()
        when (newMode) {
            OverlayMode.BALL -> {
                removePanel()
                removeMiniBar()
            }

            OverlayMode.MINI_BAR -> {
                removePanel()
                showMiniBar()
            }

            OverlayMode.PANEL -> {
                removeMiniBar()
                showPanel()
            }
        }
        mode = newMode
        updateRecorderIgnoredRegion()
    }

    /**
     * 进入自动化类操作前自动收起面板，只保留悬浮小球。
     *
     * 迷你条是横跨屏幕顶部的可触摸窗口，会截走 `dispatchGesture` 注入的点击，
     * 因此自动化期间一律收成小球；小球尺寸很小且用于随时停止，保留其可触摸。
     */
    private fun collapseForAutomation() {
        setMode(OverlayMode.BALL)
    }

    private fun removePanel() {
        removeView(panelView)
        panelView = null
        panelParams = null
    }

    private fun removeMiniBar() {
        removeView(miniBarView)
        miniBarView = null
        miniBarParams = null
    }

    private fun showPanel() {
        if (panelView != null) return
        val view = OverlayUi.createPanel(this, panelCallbacks)
        val width = OverlayUi.dp(this, 300f)
        val params = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        val metrics = resources.displayMetrics
        params.x = (metrics.widthPixels - width - OverlayUi.dp(this, 8f)).coerceAtLeast(0)
        params.y = ballParams?.y ?: (metrics.heightPixels / 3)

        if (!addView(view, params)) return
        panelView = view
        panelParams = params
        refreshScripts()
        updateStatus()
        updateRecorderIgnoredRegion()
    }

    /** 迷你条：收起面板后的快捷条，保留一键启停与展开入口。 */
    private fun showMiniBar() {
        if (miniBarView != null) return
        val view = OverlayUi.createMiniBar(this, panelCallbacks)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        if (!addView(view, params)) return
        miniBarView = view
        miniBarParams = params
        refreshScripts()
        updateStatus()
        updateRecorderIgnoredRegion()
    }

    // ---- 精确录制采集层 ----

    /** 显示全屏触摸采集层（精确录制用）。 */
    private fun showCaptureLayer() {
        if (captureLayerView != null) return
        val view = OverlayUi.createCaptureLayer(this) { e -> ScriptRecorder.onCaptureTouch(e) }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        if (!addView(view, params)) return
        captureLayerView = view
        captureLayerParams = params

        // 回放注入手势期间把采集层临时设为不可触摸，避免注入手势被自己拦截再次录入。
        ScriptRecorder.capturePassthrough = { passthrough -> setCaptureLayerPassthrough(passthrough) }

        // z-order：采集层是全屏且吞触摸的，必须让悬浮球与迷你条位于其之上，
        // 否则用户无法点击「结束录制」。先把控制控件移除并置空，再重新添加一次以提升层级。
        removeView(ballView)
        ballView = null
        ballParams = null
        removeView(miniBarView)
        miniBarView = null
        miniBarParams = null
        showBall()
        if (mode == OverlayMode.MINI_BAR) showMiniBar()
    }

    /** 移除采集层。 */
    private fun hideCaptureLayer() {
        ScriptRecorder.capturePassthrough = null
        removeView(captureLayerView)
        captureLayerView = null
        captureLayerParams = null
    }

    /**
     * 切换采集层是否透传触摸。透传时加 [WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE]，
     * 让注入手势落到下方目标 App；录制态则恢复可触摸以继续采集。
     * 必须在主线程调用（由 ScriptRecorder 通过 Dispatchers.Main 回调）。
     */
    private fun setCaptureLayerPassthrough(passthrough: Boolean) {
        val view = captureLayerView ?: return
        val params = captureLayerParams ?: return
        try {
            params.flags = if (passthrough) {
                params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            } else {
                params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            }
            windowManager.updateViewLayout(view, params)
        } catch (e: Exception) {
            // 窗口失效时忽略
        }
    }

    // ---- 坐标拾取 ----

    private fun showPickOverlay() {
        collapseForAutomation()
        if (pickView != null) return
        val view = OverlayUi.createPickLayer(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        view.setOnTouchListener { v, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                PickPointBridge.notifyPicked(PickPoint(event.rawX, event.rawY))
                removeView(v)
                pickView = null
                openApp()
            }
            true
        }
        if (addView(view, params)) {
            pickView = view
        }
    }

    /** 全屏区域框选：拖拽一个矩形，结果通过 [RegionPickerBridge] 交回编辑器。 */
    private fun showRegionPickOverlay() {
        collapseForAutomation()
        if (regionPickView != null) return
        try {
            val root = FrameLayout(this)
            root.setBackgroundColor(0x99000000.toInt())

            val hint = TextView(this).apply {
                text = "拖拽框选识图搜索区域"
                setTextColor(Color.WHITE)
                textSize = 16f
                gravity = Gravity.CENTER
            }
            root.addView(
                hint,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.CENTER_HORIZONTAL
                ).apply { topMargin = OverlayUi.dp(this@OverlayService, 48f) }
            )

            val box = View(this)
            box.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(0x3334B0FF.toInt())
                setStroke(OverlayUi.dp(this@OverlayService, 1f), 0xFF34B0FF.toInt())
            }
            box.visibility = View.INVISIBLE
            val boxLp = FrameLayout.LayoutParams(0, 0, Gravity.TOP or Gravity.START)
            root.addView(box, boxLp)

            val minSize = OverlayUi.dp(this, 8f)
            var startX = 0f
            var startY = 0f
            root.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = event.rawX
                        startY = event.rawY
                        box.visibility = View.INVISIBLE
                        true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val left = minOf(startX, event.rawX)
                        val top = minOf(startY, event.rawY)
                        val right = maxOf(startX, event.rawX)
                        val bottom = maxOf(startY, event.rawY)
                        boxLp.leftMargin = left.toInt()
                        boxLp.topMargin = top.toInt()
                        boxLp.width = (right - left).toInt()
                        boxLp.height = (bottom - top).toInt()
                        box.layoutParams = boxLp
                        box.visibility = View.VISIBLE
                        true
                    }

                    MotionEvent.ACTION_UP -> {
                        val left = minOf(startX, event.rawX)
                        val top = minOf(startY, event.rawY)
                        val right = maxOf(startX, event.rawX)
                        val bottom = maxOf(startY, event.rawY)
                        if ((right - left) < minSize || (bottom - top) < minSize) {
                            toast("框选区域太小，请重新拖拽")
                        } else {
                            RegionPickerBridge.publish(
                                Rect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
                            )
                            removeRegionPickOverlay()
                            openApp()
                        }
                        true
                    }

                    else -> false
                }
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.START
            if (addView(root, params)) {
                regionPickView = root
            }
        } catch (e: Exception) {
            // 忽略
        }
    }

    private fun removeRegionPickOverlay() {
        removeView(regionPickView)
        regionPickView = null
    }

    // ---- 点击器模式 ----

    /** 打开点击器模式：显示全屏标记层与底部控制条。 */
    private fun showClicker() {
        if (ScriptRunner.isRunning) {
            toast("请先停止正在运行的脚本")
            return
        }
        ClickerEngine.attach(this)

        // 面板 / 迷你条若开着先收起到小球，避免遮挡标记层。
        setMode(OverlayMode.BALL)

        if (markerView == null) {
            val view = ClickerOverlay.createMarkerLayer(this, clickerCallbacks)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.START
            if (addView(view, params)) {
                markerView = view
                markerParams = params
            }
        }

        if (controlBarView == null) {
            val view = ClickerOverlay.createControlBar(this, clickerCallbacks)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.BOTTOM or Gravity.START
            params.y = OverlayUi.dp(this, 8f)
            if (addView(view, params)) {
                controlBarView = view
                controlBarParams = params
            }
        }

        val cfg = ClickerEngine.config.value
        markerView?.let { ClickerOverlay.setPoints(it, cfg.points) }
        controlBarView?.let { ClickerOverlay.setConfig(it, cfg) }
        refreshClickerStatus()
        subscribeClickerState()
    }

    /** 订阅点击器配置与状态流（防重复订阅）。 */
    private fun subscribeClickerState() {
        if (clickerSubscribed) return
        clickerSubscribed = true
        scope.launch {
            ClickerEngine.config.collect { cfg ->
                markerView?.let { ClickerOverlay.setPoints(it, cfg.points) }
                controlBarView?.let { ClickerOverlay.setConfig(it, cfg) }
            }
        }
        scope.launch {
            ClickerEngine.state.collect { st ->
                refreshClickerStatus()
                controlBarView?.let { ClickerOverlay.setRunning(it, st.isRunning) }
                setMarkerTouchable(!st.isRunning)
                updateStatus()
            }
        }
    }

    /** 用当前状态与配置刷新控制条状态文字。 */
    private fun refreshClickerStatus() {
        val bar = controlBarView ?: return
        val cfg = ClickerEngine.config.value
        ClickerOverlay.setStatus(
            bar,
            ClickerOverlay.formatStatus(ClickerEngine.state.value, cfg.points.size, cfg)
        )
    }

    /**
     * 切换标记层是否可触摸。运行态必须设为不可触摸，否则 dispatchGesture
     * 派发的点击会落在我们自己的覆盖层上，目标 App 收不到。
     */
    private fun setMarkerTouchable(touchable: Boolean) {
        val view = markerView ?: return
        val params = markerParams ?: return
        try {
            params.flags = if (touchable) {
                params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            } else {
                params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
            windowManager.updateViewLayout(view, params)
        } catch (e: Exception) {
            // 窗口失效时忽略
        }
    }

    /** 关闭点击器模式并移除相关视图。 */
    private fun hideClicker() {
        ClickerEngine.stop()
        ClickerOverlay.hideEditors(windowManager)
        removeView(markerView)
        markerView = null
        markerParams = null
        removeView(controlBarView)
        controlBarView = null
        controlBarParams = null
    }

    // ---- 按钮逻辑 ----

    private fun startScript(script: Script?) {
        if (script == null) {
            toast("请先选择或创建一个脚本")
            return
        }
        if (!AutoAccessService.isConnected) {
            toast("请先在无障碍设置中开启服务")
            PermissionChecker.openAccessibilitySettings(this)
            return
        }
        collapseForAutomation()
        if (!ScriptRunner.start(this, script)) {
            toast("启动失败：已有脚本在运行")
        }
    }

    private fun finishRecording() {
        val script = ScriptRecorder.stop()
        ScriptRecorder.ignoredRegion = null
        hideCaptureLayer()
        if (script == null) {
            if (ScriptRecorder.preciseMode.value) {
                toast("未录制到任何操作（精确模式下请确认已开启无障碍服务）")
            } else {
                toast("未录制到任何操作（请确认已开启无障碍服务，并在目标 App 中操作）")
            }
            return
        }
        ScriptRepository.get(this).save(script)
        toast("已保存脚本：${script.name}")
        refreshScripts()
    }

    /** 重新计算并写入录制忽略区（悬浮球与面板的屏幕矩形并集），避免录到自身点击。 */
    private fun updateRecorderIgnoredRegion() {
        try {
            val ball = ballParams
            val ballRect = if (ball != null) {
                Rect(ball.x, ball.y, ball.x + ball.width, ball.y + ball.height)
            } else {
                null
            }

            val panel = panelParams
            val panelRect = if (panel != null) {
                // 面板高度为 WRAP_CONTENT，优先用实测高度，未测量时退回参数值。
                val measured = panelView?.height ?: 0
                val height = if (measured > 0) measured else panel.height
                Rect(panel.x, panel.y, panel.x + panel.width, panel.y + height)
            } else {
                null
            }

            ScriptRecorder.ignoredRegion = when {
                ballRect != null && panelRect != null -> Rect(ballRect).apply { union(panelRect) }
                ballRect != null -> ballRect
                panelRect != null -> panelRect
                else -> null
            }
        } catch (e: Exception) {
            ScriptRecorder.ignoredRegion = null
        }
    }

    private fun refreshScripts() {
        val scripts = repositoryScripts()
        val panel = panelView
        if (panel != null) {
            OverlayUi.setScripts(panel, scripts)
            refreshSteps(OverlayUi.currentScript(panel))
        }
        val miniBar = miniBarView
        if (miniBar != null) {
            OverlayUi.setScripts(miniBar, scripts)
        }
    }

    /** 重建步骤列表行，并刷新循环 / 拟人化信息行。 */
    private fun refreshSteps(script: Script?) {
        val panel = panelView ?: return
        if (script != null) OverlayUi.syncSelectedScript(panel, script)
        OverlayUi.setSteps(panel, script?.steps ?: emptyList())
        OverlayUi.setScriptInfo(panel, script)
    }

    private fun editStep(script: Script?, index: Int) {
        val target = script ?: return
        val step = target.steps.getOrNull(index) ?: return
        OverlayStepEditor.show(this, windowManager, step) { updated ->
            applyStepUpdate(target, index, updated)
        }
    }

    /** 用编辑后的步骤替换第 [index] 项，保存后重新拉取脚本列表并重建。 */
    private fun applyStepUpdate(script: Script, index: Int, updated: Step) {
        if (index !in script.steps.indices) return
        val steps = script.steps.toMutableList()
        steps[index] = updated
        ScriptRepository.get(this).save(script.copy(steps = steps))
        refreshScripts()
    }

    private fun moveStep(script: Script?, index: Int, delta: Int) {
        val target = script ?: return
        val dest = index + delta
        if (index !in target.steps.indices || dest !in target.steps.indices) return
        val steps = target.steps.toMutableList()
        val tmp = steps[index]
        steps[index] = steps[dest]
        steps[dest] = tmp
        val updated = target.copy(steps = steps)
        ScriptRepository.get(this).save(updated)
        refreshSteps(updated)
    }

    private fun deleteStep(script: Script?, index: Int) {
        val target = script ?: return
        if (index !in target.steps.indices) return
        val steps = target.steps.toMutableList()
        steps.removeAt(index)
        val updated = target.copy(steps = steps)
        ScriptRepository.get(this).save(updated)
        refreshSteps(updated)
    }

    private fun duplicateStep(script: Script?, index: Int) {
        val target = script ?: return
        val step = target.steps.getOrNull(index) ?: return
        val steps = target.steps.toMutableList()
        steps.add(index + 1, step.withNewId())
        val updated = target.copy(steps = steps)
        ScriptRepository.get(this).save(updated)
        refreshSteps(updated)
    }

    private fun repositoryScripts(): List<Script> =
        ScriptRepository.get(this).listScripts()

    private fun openApp() {
        try {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )
            )
        } catch (e: Exception) {
            // 忽略
        }
    }

    /** 截屏并把位图交给识图页框选模板。 */
    private fun captureTemplate() {
        collapseForAutomation()
        scope.launch {
            if (!ScreenCaptureService.awaitReady(4000L)) {
                toast("尚未授权截屏，正在打开授权页…")
                CapturePermissionActivity.request(this@OverlayService)
                // 授权后用户回到目标 App，再挂起等待一次，就绪就自动继续。
                if (!ScreenCaptureService.awaitReady(8000L)) {
                    toast("截屏通道未就绪：${ScreenCaptureService.lastError.value ?: "未知原因"}")
                    return@launch
                }
            }
            val bmp: Bitmap? = withContext(Dispatchers.IO) {
                ScreenCaptureService.capture(0)
            }
            if (bmp == null) {
                toast("截屏失败：${ScreenCaptureService.lastError.value ?: "未知原因"}")
                return@launch
            }
            VisionBridge.publishCapture(bmp)
            toast("已截屏，请在识图页框选区域")
            openAppWithRoute("vision")
        }
    }

    /** 打开应用并携带路由参数（供识图页等直接进入指定子页）。 */
    private fun openAppWithRoute(route: String) {
        try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    .putExtra(MainActivity.EXTRA_OPEN_ROUTE, route)
            )
        } catch (e: Exception) {
            // 忽略
        }
    }

    // ---- 窗口与通知 ----

    private fun addView(view: View, params: WindowManager.LayoutParams): Boolean {
        return try {
            windowManager.addView(view, params)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun removeView(view: View?) {
        if (view == null) return
        try {
            windowManager.removeView(view)
        } catch (e: Exception) {
            // 忽略
        }
    }

    private fun hideAll() {
        OverlayStepEditor.hide(windowManager)
        removeView(ballView)
        ballView = null
        ballParams = null
        removeView(panelView)
        panelView = null
        panelParams = null
        removeView(miniBarView)
        miniBarView = null
        miniBarParams = null
        removeView(pickView)
        pickView = null
        removeView(regionPickView)
        regionPickView = null
        ScriptRecorder.capturePassthrough = null
        removeView(captureLayerView)
        captureLayerView = null
        captureLayerParams = null
        removeView(markerView)
        markerView = null
        markerParams = null
        removeView(controlBarView)
        controlBarView = null
        controlBarParams = null
        ClickerOverlay.hideEditors(windowManager)
        mode = OverlayMode.BALL
    }

    private fun buildNotification(): Notification {
        // 复用统一的通知渠道创建入口；NotificationCompat.Builder 只需 channelId，
        // 故 ensure 返回 null（渠道创建失败）时仍能构建出可用通知，无需额外兜底。
        NotificationChannels.ensure(this, CHANNEL_ID, "悬浮窗控制", NotificationManager.IMPORTANCE_LOW)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("自动点击助手")
            .setContentText("悬浮窗运行中")
            .setOngoing(true)
            .build()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}