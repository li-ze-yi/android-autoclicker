package com.autoclicker.service.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
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
import com.autoclicker.core.bus.RuntimeBus
import com.autoclicker.di.ServiceLocator
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

/**
 * 悬浮窗服务：可拖动的悬浮球 + 运行控制台（开始/暂停/停止、实时日志、状态与当前步骤）。
 * 运行期通过 [OverlayControllerImpl] 注册到 [ServiceLocator.overlay]。
 */
class OverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var ballView: View? = null
    private var consoleView: View? = null

    private var stateText: TextView? = null
    private var stepText: TextView? = null
    private var logsText: TextView? = null
    private var logsScroll: ScrollView? = null

    private var logsJob: Job? = null
    private var stateJob: Job? = null
    private var stepJob: Job? = null

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
        val ball = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(BALL_COLOR)
                setStroke(dp(2), Color.WHITE)
            }
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
        RuntimeBus.log("悬浮球已显示")
    }

    private fun hideFloatingBall() {
        val view = ballView ?: return
        ballView = null
        try {
            windowManager.removeView(view)
        } catch (t: Throwable) {
            // 忽略移除异常。
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

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (abs(dx) > DRAG_SLOP_DP || abs(dy) > DRAG_SLOP_DP) moved = true
                    params.x = startX + dx.toInt()
                    params.y = startY + dy.toInt()
                    runCatching { windowManager.updateViewLayout(view, params) }
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    if (!moved) toggleConsole()
                    return true
                }
            }
            return false
        }
    }

    private fun toggleConsole() {
        if (consoleView == null) showConsole() else hideConsole()
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

        root.addView(TextView(this).apply {
            text = "运行控制台"
            setTextColor(Color.WHITE)
            textSize = 14f
        })

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

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(button("开始") {
            ServiceLocator.player?.resume()
            RuntimeBus.log("控制台：开始")
        })
        buttons.addView(button("暂停") {
            ServiceLocator.player?.pause()
            RuntimeBus.log("控制台：暂停")
        })
        buttons.addView(button("停止") {
            ServiceLocator.player?.stop()
            RuntimeBus.log("控制台：停止")
        })
        buttons.addView(button("隐藏") { hideConsole() })
        root.addView(buttons)

        val params = WindowManager.LayoutParams(
            dp(CONSOLE_WIDTH_DP),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(220)
        }
        try {
            windowManager.addView(root, params)
        } catch (t: Throwable) {
            RuntimeBus.log(LogLevel.ERROR, "控制台创建失败：${t.message}")
            return
        }
        consoleView = root
        renderLogs(RuntimeBus.logs.value)
        startCollectors()
    }

    private fun hideConsole() {
        logsJob?.cancel()
        stateJob?.cancel()
        stepJob?.cancel()
        logsJob = null
        stateJob = null
        stepJob = null
        val view = consoleView ?: return
        consoleView = null
        stateText = null
        stepText = null
        logsText = null
        logsScroll = null
        try {
            windowManager.removeView(view)
        } catch (t: Throwable) {
            // 忽略移除异常。
        }
    }

    private fun startCollectors() {
        logsJob = scope.launch {
            RuntimeBus.logs.collect { logs -> renderLogs(logs) }
        }
        stateJob = scope.launch {
            RuntimeBus.state.collect { state ->
                stateText?.text = "状态：${stateLabel(state)}"
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
    }

    private fun renderLogs(logs: List<LogEntry>) {
        val text = logs.takeLast(LOG_MAX_LINES).joinToString("\n") { entry ->
            "${timeFormat.format(Date(entry.timeMs))} ${entry.level} ${entry.message}"
        }
        logsText?.text = text
        logsScroll?.post { logsScroll?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 12f
            setOnClickListener { onClick() }
        }

    private fun stateLabel(state: PlaybackState): String = when (state) {
        PlaybackState.IDLE -> "空闲"
        PlaybackState.RUNNING -> "运行中"
        PlaybackState.PAUSED -> "已暂停"
        PlaybackState.STOPPED -> "已停止"
        PlaybackState.ERROR -> "错误"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private val BALL_COLOR = 0xFF2196F3.toInt()
        private val CONSOLE_BG = 0xCC000000.toInt()
        private val STATE_COLOR = 0xFF8BC34A.toInt()
        private val STEP_COLOR = 0xFFFFC107.toInt()

        private const val BALL_SIZE_DP = 52
        private const val DRAG_SLOP_DP = 6f
        private const val CONSOLE_WIDTH_DP = 280
        private const val LOG_VIEW_HEIGHT_DP = 160
        private const val LOG_MAX_LINES = 120

        fun start(context: Context) {
            context.startService(Intent(context, OverlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }
}