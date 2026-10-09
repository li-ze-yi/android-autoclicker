package com.autoclicker.core.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.autoclicker.MainActivity
import com.autoclicker.R
import com.autoclicker.core.accessibility.AutoAccessService
import com.autoclicker.core.recorder.ScriptRecorder
import com.autoclicker.core.runner.RunnerState
import com.autoclicker.core.runner.ScriptRunner
import com.autoclicker.core.script.Script
import com.autoclicker.core.script.ScriptRepository
import com.autoclicker.core.script.Step
import com.autoclicker.core.script.withNewId
import com.autoclicker.core.util.PermissionChecker
import com.autoclicker.core.vision.CapturePermissionActivity
import com.autoclicker.core.vision.ScreenCaptureService
import com.autoclicker.core.vision.VisionBridge
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
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
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val windowManager: WindowManager
        get() = getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var ballView: View? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var panelView: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var pickView: View? = null
    private var subscribed = false
    private var editorShowing = false

    private val panelCallbacks = object : OverlayUi.PanelCallbacks {
        override fun onStartClick(script: Script?) = startScript(script)
        override fun onPauseClick() = ScriptRunner.pause()
        override fun onResumeClick() = ScriptRunner.resume()
        override fun onStopClick() = ScriptRunner.stop()
        override fun onStartRecord() {
            if (!AutoAccessService.isConnected) {
                toast("请先在无障碍设置中开启服务，再开始录制")
                PermissionChecker.openAccessibilitySettings(this@OverlayService)
                return
            }
            updateRecorderIgnoredRegion()
            ScriptRecorder.start()
            toast("录制已开始，请切到目标 App 操作")
        }
        override fun onStopRecord() = finishRecording()
        override fun onRefreshScripts() = refreshScripts()
        override fun onOpenApp() = openApp()
        override fun onCloseOverlay() = OverlayService.stop(this@OverlayService)
        override fun onScriptSelected(script: Script?) = refreshSteps(script)
        override fun onEditStep(script: Script?, index: Int) = editStep(script, index)
        override fun onMoveStep(script: Script?, index: Int, delta: Int) = moveStep(script, index, delta)
        override fun onDeleteStep(script: Script?, index: Int) = deleteStep(script, index)
        override fun onDuplicateStep(script: Script?, index: Int) = duplicateStep(script, index)
        override fun onCaptureTemplate() = captureTemplate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 必须在 5 秒内调用，无论什么 action。
        startForeground(NOTIFICATION_ID, buildNotification())

        // 悬浮窗权限兜底：未授权则跳转授权页并退出（已 startForeground，故先 stopForeground）。
        if (!PermissionChecker.isOverlayGranted(this)) {
            PermissionChecker.openOverlaySettings(this)
            stopForeground(true)
            stopSelf()
            return Service.START_NOT_STICKY
        }

        subscribeState()

        when (intent?.action) {
            ACTION_STOP -> {
                hideAll()
                stopForeground(true)
                stopSelf()
                isRunning = false
            }

            ACTION_START_PICK -> showPickOverlay()

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
    }

    private fun updateStatus() {
        val panel = panelView ?: return
        val state = ScriptRunner.state.value
        OverlayUi.setStatus(
            panel,
            OverlayUi.formatStatus(
                state,
                ScriptRecorder.isRecording.value,
                ScriptRecorder.stepCount.value
            )
        )
        val stepIndex = when (state) {
            is RunnerState.Running -> state.stepIndex
            is RunnerState.Paused -> state.stepIndex
            else -> -1
        }
        OverlayUi.highlightStep(panel, stepIndex)
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
        if (panelView != null) {
            removeView(panelView)
            panelView = null
            panelParams = null
            updateRecorderIgnoredRegion()
        } else {
            showPanel()
        }
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

    // ---- 坐标拾取 ----

    private fun showPickOverlay() {
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
        if (!ScriptRunner.start(this, script)) {
            toast("启动失败：已有脚本在运行")
        }
    }

    private fun finishRecording() {
        val script = ScriptRecorder.stop()
        ScriptRecorder.ignoredRegion = null
        if (script == null) {
            toast("未录制到任何操作（请确认已开启无障碍服务，并在目标 App 中操作）")
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
        val panel = panelView ?: return
        OverlayUi.setScripts(panel, repositoryScripts())
        refreshSteps(OverlayUi.currentScript(panel))
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
        editorShowing = true
        OverlayStepEditor.show(this, windowManager, step) { updated ->
            editorShowing = false
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
        if (!ScreenCaptureService.isReady) {
            toast("尚未授权截屏，正在打开授权页")
            CapturePermissionActivity.request(this)
            return
        }
        scope.launch {
            val bitmap: Bitmap? = withContext(Dispatchers.IO) {
                ScreenCaptureService.capture(0)
            }
            if (bitmap == null) {
                toast("截屏失败")
                return@launch
            }
            VisionBridge.publishCapture(bitmap)
            toast("已截屏，请在识图页框选区域")
            try {
                startActivity(
                    Intent(this@OverlayService, MainActivity::class.java)
                        .addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        )
                        .putExtra("open_route", "vision")
                )
            } catch (e: Exception) {
                // 忽略
            }
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
        editorShowing = false
        removeView(ballView)
        ballView = null
        ballParams = null
        removeView(panelView)
        panelView = null
        panelParams = null
        removeView(pickView)
        pickView = null
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "悬浮窗控制", NotificationManager.IMPORTANCE_LOW)
        )
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