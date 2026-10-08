package com.autoclicker.core.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
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
import com.autoclicker.core.runner.ScriptRunner
import com.autoclicker.core.script.Script
import com.autoclicker.core.script.ScriptRepository
import com.autoclicker.core.util.PermissionChecker
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

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
    private var pickView: View? = null
    private var subscribed = false

    private val panelCallbacks = object : OverlayUi.PanelCallbacks {
        override fun onStartClick(script: Script?) = startScript(script)
        override fun onPauseClick() = ScriptRunner.pause()
        override fun onResumeClick() = ScriptRunner.resume()
        override fun onStopClick() = ScriptRunner.stop()
        override fun onStartRecord() = ScriptRecorder.start()
        override fun onStopRecord() = finishRecording()
        override fun onRefreshScripts() = refreshScripts()
        override fun onOpenApp() = openApp()
        override fun onCloseOverlay() = OverlayService.stop(this@OverlayService)
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
        OverlayUi.setStatus(
            panel,
            OverlayUi.formatStatus(
                ScriptRunner.state.value,
                ScriptRecorder.isRecording.value,
                ScriptRecorder.stepCount.value
            )
        )
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
        } else {
            showPanel()
        }
    }

    private fun showPanel() {
        if (panelView != null) return
        val view = OverlayUi.createPanel(this, panelCallbacks)
        val width = OverlayUi.dp(this, 240f)
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
        OverlayUi.setScripts(view, repositoryScripts())
        updateStatus()
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
        if (script == null) {
            toast("未录制到任何操作")
            return
        }
        ScriptRepository.get(this).save(script)
        toast("已保存脚本：${script.name}")
        refreshScripts()
    }

    private fun refreshScripts() {
        val panel = panelView ?: return
        OverlayUi.setScripts(panel, repositoryScripts())
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
        removeView(ballView)
        ballView = null
        ballParams = null
        removeView(panelView)
        panelView = null
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