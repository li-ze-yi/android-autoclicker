package com.autoclicker.service.capture

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.autoclicker.service.overlay.COLOR_DANGER
import com.autoclicker.service.overlay.COLOR_PRIMARY
import com.autoclicker.service.overlay.ScreenMetrics
import com.autoclicker.service.overlay.dpToPx
import com.autoclicker.service.overlay.roundRectBackground
import com.autoclicker.service.overlay.spToPx

/**
 * 区域框选悬浮层（FR-7，TR-10.2）。
 *
 * - 传统 View + WindowManager 实现，全屏半透明窗口（TYPE_APPLICATION_OVERLAY）；
 * - 手指从左上拖到右下绘制矩形：框外保持半透明遮罩，框内透明并显示主色边框；
 * - 抬手矩形固定，再次按下可重画；底部提供「确认 / 取消」按钮；
 * - 确认后回调屏幕绝对坐标 [Rect]，坐标空间与全屏 Bitmap 一致，可直接用于裁剪识图模板；
 * - 取消回调 [onCancel]。任何路径结束都会先 detach，资源不残留。
 *
 * 前置条件：调用方需已取得悬浮窗权限（SYSTEM_ALERT_WINDOW）。
 */
class RegionPickerOverlay(
    private val context: Context,
    private val wm: WindowManager,
    private val onConfirm: (Rect) -> Unit,
    private val onCancel: () -> Unit,
) {

    private val screenWidth: Int = ScreenMetrics.width(wm)
    private val screenHeight: Int = ScreenMetrics.height(wm)

    private val pickerView: RegionPickerView = RegionPickerView(context)
    private val confirmButton: TextView
    private val cancelButton: TextView
    private val root: FrameLayout

    private var attached: Boolean = false

    /** 窗口参数：全屏、不获取焦点、布局延伸到系统栏区域 */
    private val params: WindowManager.LayoutParams = WindowManager.LayoutParams(
        screenWidth,
        screenHeight,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 0
        y = 0
    }

    init {
        root = FrameLayout(context)

        // 全屏框选视图
        root.addView(
            pickerView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        pickerView.onSelectionChanged = { updateConfirmState() }

        confirmButton = buildButton("确认", COLOR_PRIMARY)
        cancelButton = buildButton("取消", COLOR_DANGER)

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        row.addView(cancelButton)
        row.addView(
            View(context).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(context, 12), 1)
            },
        )
        row.addView(confirmButton)

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val hint = TextView(context).apply {
            text = "拖动手指框选识图区域"
            setTextColor(Color.WHITE)
            textSize = spToPx(context, HINT_TEXT_SP)
            gravity = Gravity.CENTER
            val padV = dpToPx(context, 6)
            setPadding(0, 0, 0, padV)
            setShadowLayer(6f, 0f, 2f, 0xAA000000.toInt())
        }
        column.addView(hint)
        column.addView(row)
        // 半透明深色底板，保证底部文字/按钮在任何画面上都可见
        column.background = roundRectBackground(
            0xCC263238.toInt(),
            dpToPx(context, 12).toFloat(),
        )
        val padH = dpToPx(context, 18)
        val padV = dpToPx(context, 12)
        column.setPadding(padH, padV, padH, padV)

        // 底部说明 + 操作按钮容器
        val barParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = dpToPx(context, BOTTOM_MARGIN_DP)
        }
        root.addView(column, barParams)

        confirmButton.setOnClickListener {
            val selection = pickerView.selection ?: return@setOnClickListener
            detach()
            onConfirm(Rect(selection))
        }
        cancelButton.setOnClickListener {
            detach()
            onCancel()
        }

        updateConfirmState()
    }

    /** 构建操作按钮（主色/危险色实心圆角） */
    private fun buildButton(text: String, color: Int): TextView = TextView(context).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = spToPx(context, BUTTON_TEXT_SP)
        gravity = Gravity.CENTER
        val padH = dpToPx(context, 22)
        val padV = dpToPx(context, 8)
        setPadding(padH, padV, padH, padV)
        background = roundRectBackground(color, dpToPx(context, 8).toFloat())
    }

    /** 根据是否有有效选区切换确认按钮可用态 */
    private fun updateConfirmState() {
        val enabled = pickerView.selection != null
        confirmButton.isEnabled = enabled
        confirmButton.alpha = if (enabled) 1f else 0.45f
    }

    /** 挂载悬浮层（幂等）。addView 失败时以取消回调收口，不抛出崩溃 */
    fun attach() {
        if (attached) return
        try {
            wm.addView(root, params)
            attached = true
        } catch (t: Throwable) {
            attached = false
            onCancel()
        }
    }

    /** 移除悬浮层（幂等，兜底各种窗口异常） */
    fun detach() {
        if (!attached) return
        attached = false
        runCatching { wm.removeView(root) }
    }

    // -----------------------------------------------------------------------
    // 框选绘制视图
    // -----------------------------------------------------------------------

    /**
     * 全屏框选视图：遮罩挖空 + 选区边框。
     * 触摸坐标统一取 rawX/rawY（窗口位于 (0,0)，即屏幕绝对坐标）。
     */
    private inner class RegionPickerView(context: Context) : View(context) {

        /** 当前选区（屏幕绝对坐标，已归一化）；为 null 表示尚未选定 */
        var selection: Rect? = null
            private set

        /** 选区变化回调（用于刷新确认按钮态） */
        var onSelectionChanged: (() -> Unit)? = null

        // 本次拖拽起点
        private var startX = 0f
        private var startY = 0f
        private var dragging: Boolean = false

        private val density = context.resources.displayMetrics.density
        private fun dp(v: Float): Float = v * density

        /** 半透明遮罩画笔 */
        private val dimPaint = Paint().apply { color = DIM_COLOR }

        /** 挖空画笔：把选区内的遮罩清掉，显示真实屏幕 */
        private val clearPaint = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }

        /** 选区淡主色填充 */
        private val fillPaint = Paint().apply {
            color = 0x332962FF
            style = Paint.Style.FILL
        }

        /** 选区主色边框 */
        private val borderPaint = Paint().apply {
            color = COLOR_PRIMARY
            style = Paint.Style.STROKE
            strokeWidth = dp(2f)
            isAntiAlias = true
        }

        override fun onDraw(canvas: Canvas) {
            // 离屏图层上先铺遮罩再挖空，避免 CLEAR 影响窗口外内容
            val saveCount = canvas.saveLayer(
                0f, 0f, width.toFloat(), height.toFloat(), null,
            )
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
            selection?.let { rect ->
                val rectF = RectF(rect)
                canvas.drawRect(rectF, clearPaint)
                canvas.drawRect(rectF, fillPaint)
                canvas.drawRect(rectF, borderPaint)
            }
            canvas.restoreToCount(saveCount)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            // raw 坐标夹取到屏幕边界内
            val rawX = event.rawX.coerceIn(0f, screenWidth.toFloat())
            val rawY = event.rawY.coerceIn(0f, screenHeight.toFloat())

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = rawX
                    startY = rawY
                    dragging = true
                    selection = null
                    onSelectionChanged?.invoke()
                    invalidate()
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!dragging) return true
                    selection = normalizedRect(startX, startY, rawX, rawY)
                    invalidate()
                }

                MotionEvent.ACTION_UP -> {
                    dragging = false
                    val rect = normalizedRect(startX, startY, rawX, rawY)
                    // 小于触控容差的框视为无效（误触）
                    selection = if (
                        rect.width() >= touchSlopPx() && rect.height() >= touchSlopPx()
                    ) {
                        rect
                    } else {
                        null
                    }
                    onSelectionChanged?.invoke()
                    invalidate()
                    performClick()
                }

                MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                }
            }
            return true
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }

        /** 由拖拽两点生成归一化、含最小尺寸的矩形 */
        private fun normalizedRect(x1: Float, y1: Float, x2: Float, y2: Float): Rect {
            val left = minOf(x1, x2).toInt().coerceIn(0, screenWidth - 1)
            val top = minOf(y1, y2).toInt().coerceIn(0, screenHeight - 1)
            val right = maxOf(x1, x2).toInt().coerceIn(left + 1, screenWidth)
            val bottom = maxOf(y1, y2).toInt().coerceIn(top + 1, screenHeight)
            return Rect(left, top, right, bottom)
        }

        /** 框选最小有效边长：取系统 touchSlop 与 8dp 的较大值 */
        private fun touchSlopPx(): Int = maxOf(
            android.view.ViewConfiguration.get(context).scaledTouchSlop,
            dp(8f).toInt(),
        )
    }

    private companion object {
        /** 半透明遮罩颜色（Int：颜色值超出 Int 正数范围，显式转换） */
        val DIM_COLOR = 0x99000000.toInt()

        const val BOTTOM_MARGIN_DP = 28
        const val HINT_TEXT_SP = 13
        const val BUTTON_TEXT_SP = 14
    }
}
