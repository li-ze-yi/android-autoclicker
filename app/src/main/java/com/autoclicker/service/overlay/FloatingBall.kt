package com.autoclicker.service.overlay

import android.content.Context
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView

/** 悬浮球直径 dp */
private const val BALL_SIZE_DP = 48

/** 拖动/点击判定阈值 dp（实际取 touchSlop 与此值的较大者） */
private const val BALL_TAP_SLOP_DP = 10

/**
 * 悬浮球：约 48dp 圆形、可拖动；单击展开/收起控制面板。
 *
 * 拖动与点击通过位移阈值区分：
 * - 位移 <= 阈值：判定为点击（[onClick]）；
 * - 位移 > 阈值：判定为拖动，位置实时跟随手指，抬手时通过 WindowManager 更新。
 *
 * @param onClick 点击悬浮球（展开/收起面板）
 * @param onDragStart 开始拖动悬浮球（服务借此收起面板）
 */
class FloatingBall(
    context: Context,
    private val wm: WindowManager,
    private val onClick: () -> Unit,
    private val onDragStart: () -> Unit,
) : FrameLayout(context) {

    /** 悬浮球窗口句柄 */
    val handle: WindowHandle

    init {
        val size = dpToPx(context, BALL_SIZE_DP)
        // 主色圆形背景 + 投影
        background = circleBackground(COLOR_PRIMARY)
        elevation = dpToPxF(context, 8)

        // 靶心图标
        val iconSize = dpToPx(context, 26)
        val icon = ImageView(context).apply {
            setImageDrawable(GlyphDrawable(Glyph.TARGET, 0xFFFFFFFF.toInt(), iconSize))
            contentDescription = "自动点击悬浮球"
        }
        addView(
            icon,
            LayoutParams(iconSize, iconSize).apply { gravity = Gravity.CENTER },
        )

        val params = overlayParams(size, size)
        // 默认停靠在屏幕右侧中部
        val margin = dpToPx(context, 12)
        params.x = (ScreenMetrics.width(wm) - size - margin).coerceAtLeast(0)
        params.y = ScreenMetrics.height(wm) / 2 - size / 2

        handle = WindowHandle(this, params)

        val slop = maxOf(
            android.view.ViewConfiguration.get(context).scaledTouchSlop,
            dpToPx(context, BALL_TAP_SLOP_DP),
        )
        setOnTouchListener(BallDragTouch(wm, this, slop))
    }

    /** 添加到窗口 */
    fun attach() = handle.attach(wm)

    /** 从窗口移除 */
    fun detach() = handle.detach(wm)

    /** 重新挂载到最顶层（目标窗口增删后保证悬浮球不被遮挡） */
    fun reorder() {
        if (handle.attached) {
            handle.detach(wm)
            handle.attach(wm)
        }
    }

    /** 悬浮球自身的拖拽处理：点击与拖动互斥 */
    private inner class BallDragTouch(
        wm: WindowManager,
        view: android.view.View,
        slopPx: Int,
    ) : DragTouchListener(wm, view, slopPx) {

        override fun onDragStart() {
            this@FloatingBall.onDragStart()
        }

        override fun onClick() {
            this@FloatingBall.onClick()
        }

        // 悬浮球不参与选中逻辑
        override fun onLongPress() = Unit
    }
}
