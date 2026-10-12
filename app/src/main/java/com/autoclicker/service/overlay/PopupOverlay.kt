package com.autoclicker.service.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.autoclicker.core.bus.LogLevel
import com.autoclicker.core.bus.RuntimeBus
import com.autoclicker.core.permission.PermissionChecker
import kotlin.math.roundToInt

/**
 * 运行期悬浮弹窗（**非阻塞**）：显示标题与内容，[AUTO_DISMISS_MS] 后自动关闭，
 * 期间可点「确定」提前关闭；不会暂停脚本，避免无人应答时任务卡死。
 *
 * 用原生 View + TYPE_APPLICATION_OVERLAY：与其余悬浮窗保持一致
 * （ComposeView 挂进悬浮窗在部分机型上会让进程退出）。
 */
object PopupOverlay {

    private const val AUTO_DISMISS_MS = 6000L
    private const val WIDTH_DP = 280
    private const val CARD_BG = 0xFF23262B.toInt()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var manager: WindowManager? = null
    private var view: View? = null
    private var autoDismiss: Runnable? = null

    /** 显示弹窗；不阻塞调用方。 */
    fun show(context: Context, title: String, message: String) {
        val app = context.applicationContext
        mainHandler.post {
            runCatching {
                if (!PermissionChecker.isOverlayGranted(app)) {
                    RuntimeBus.log(LogLevel.WARN, "无悬浮窗权限，弹窗无法显示：$title")
                    return@post
                }
                dismissInternal()
                val windowManager = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                    ?: return@post
                val density = app.resources.displayMetrics.density
                val card = buildCard(app, density, title, message) { dismissInternal() }
                val params = WindowManager.LayoutParams(
                    dp(WIDTH_DP, density),
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    // NOT_FOCUSABLE 不抢输入焦点；仍可接收触摸，所以「确定」可点。
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT,
                ).apply { gravity = Gravity.CENTER }
                windowManager.addView(card, params)
                manager = windowManager
                view = card
                val dismissTask = Runnable { dismissInternal() }
                autoDismiss = dismissTask
                mainHandler.postDelayed(dismissTask, AUTO_DISMISS_MS)
            }.onFailure { RuntimeBus.log(LogLevel.WARN, "弹窗显示失败：${it.message}") }
        }
    }

    /** 立即关闭弹窗（服务销毁时调用）。 */
    fun dismiss() {
        mainHandler.post { dismissInternal() }
    }

    private fun dismissInternal() {
        autoDismiss?.let { mainHandler.removeCallbacks(it) }
        autoDismiss = null
        val current = view
        val windowManager = manager
        view = null
        manager = null
        if (current != null && windowManager != null) {
            runCatching { windowManager.removeView(current) }
        }
    }

    private fun buildCard(
        context: Context,
        density: Float,
        title: String,
        message: String,
        onClose: () -> Unit,
    ): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16, density), dp(16, density), dp(16, density), dp(12, density))
            background = GradientDrawable().apply {
                cornerRadius = dp(12, density).toFloat()
                setColor(CARD_BG)
            }
        }
        card.addView(
            TextView(context).apply {
                text = title
                setTextColor(Color.WHITE)
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
            },
        )
        card.addView(
            TextView(context).apply {
                text = message
                setTextColor(0xFFDDDDDD.toInt())
                textSize = 13f
                setPadding(0, dp(8, density), 0, dp(10, density))
            },
        )
        card.addView(
            Button(context).apply {
                text = "确定"
                isAllCaps = false
                setOnClickListener { onClose() }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        return card
    }

    private fun dp(value: Int, density: Float): Int = (value * density).roundToInt()
}
