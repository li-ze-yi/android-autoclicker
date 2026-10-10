package com.autoclicker.service.overlay

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable

/**
 * 悬浮层样式：统一颜色、圆角背景与代码绘制图标。
 *
 * 本任务不新增任何 res/drawable 资源，所有图标由 [GlyphDrawable] 在代码中绘制，
 * 颜色/圆角背景统一在此定义，保证视觉一致。
 */

/** 主色 */
const val COLOR_PRIMARY: Int = 0xFF2962FF.toInt()

/** 危险色（停止、删除） */
const val COLOR_DANGER: Int = 0xFFE53935.toInt()

/** 面板半透明白底 */
const val COLOR_PANEL_BG: Int = 0xF2FFFFFF.toInt()

/** 面板上图标的深灰色（小眼睛等） */
const val COLOR_ICON_GRAY: Int = 0xFF546E7A.toInt()

/**
 * 圆形背景。
 *
 * @param fill 填充色
 * @param strokeColor 描边色（0 表示不描边）
 * @param strokeWidthPx 描边宽度 px
 */
fun circleBackground(
    fill: Int,
    strokeColor: Int = 0,
    strokeWidthPx: Int = 0,
): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setColor(fill)
    if (strokeColor != 0 && strokeWidthPx > 0) {
        setStroke(strokeWidthPx, strokeColor)
    }
}

/**
 * 圆角矩形背景。
 *
 * @param fill 填充色
 * @param radiusPx 圆角半径 px
 * @param strokeColor 描边色（0 表示不描边）
 * @param strokeWidthPx 描边宽度 px
 */
fun roundRectBackground(
    fill: Int,
    radiusPx: Float,
    strokeColor: Int = 0,
    strokeWidthPx: Int = 0,
): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    cornerRadius = radiusPx
    setColor(fill)
    if (strokeColor != 0 && strokeWidthPx > 0) {
        setStroke(strokeWidthPx, strokeColor)
    }
}

/** 代码绘制的图标类型 */
enum class Glyph {
    /** 播放（三角形） */
    PLAY,

    /** 暂停（两竖条） */
    PAUSE,

    /** 停止（方块） */
    STOP,

    /** 添加（加号） */
    ADD,

    /** 滑动（带箭头的斜线） */
    SWIPE,

    /** 睁眼 */
    EYE_OPEN,

    /** 闭眼 */
    EYE_CLOSED,

    /** 悬浮球上的靶心图案 */
    TARGET,
}

/**
 * 纯代码矢量图标：以归一化坐标 [-1,1] 绘制，随 [intrinsicSizePx] 缩放。
 */
class GlyphDrawable(
    private val glyph: Glyph,
    private val color: Int,
    private val intrinsicSizePx: Int,
) : Drawable() {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = this@GlyphDrawable.color
        style = Paint.Style.FILL
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = this@GlyphDrawable.color
        style = Paint.Style.STROKE
        strokeWidth = STROKE_WIDTH
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun getIntrinsicWidth(): Int = intrinsicSizePx

    override fun getIntrinsicHeight(): Int = intrinsicSizePx

    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return
        // 以控件中心为原点，归一化坐标映射到 [-1,1]
        canvas.scale(w / 2f, h / 2f, w / 2f, h / 2f)
        when (glyph) {
            Glyph.PLAY -> {
                val path = Path().apply {
                    moveTo(-0.42f, -0.62f)
                    lineTo(0.58f, 0f)
                    lineTo(-0.42f, 0.62f)
                    close()
                }
                canvas.drawPath(path, fillPaint)
            }

            Glyph.PAUSE -> {
                val rect = RectF(-0.58f, -0.62f, -0.14f, 0.62f)
                canvas.drawRoundRect(rect, 0.16f, 0.16f, fillPaint)
                rect.set(0.14f, -0.62f, 0.58f, 0.62f)
                canvas.drawRoundRect(rect, 0.16f, 0.16f, fillPaint)
            }

            Glyph.STOP -> {
                val rect = RectF(-0.55f, -0.55f, 0.55f, 0.55f)
                canvas.drawRoundRect(rect, 0.18f, 0.18f, fillPaint)
            }

            Glyph.ADD -> {
                canvas.drawLine(-0.62f, 0f, 0.62f, 0f, strokePaint)
                canvas.drawLine(0f, -0.62f, 0f, 0.62f, strokePaint)
            }

            Glyph.SWIPE -> {
                // 斜线 + 箭头 + 尾部圆点
                canvas.drawLine(-0.45f, 0.45f, 0.45f, -0.45f, strokePaint)
                canvas.drawLine(0.45f, -0.45f, 0.05f, -0.45f, strokePaint)
                canvas.drawLine(0.45f, -0.45f, 0.45f, -0.05f, strokePaint)
                canvas.drawCircle(-0.45f, 0.45f, 0.1f, fillPaint)
            }

            Glyph.EYE_OPEN -> {
                val path = Path().apply {
                    moveTo(-0.9f, 0f)
                    cubicTo(-0.3f, -0.62f, 0.3f, -0.62f, 0.9f, 0f)
                    cubicTo(0.3f, 0.62f, -0.3f, 0.62f, -0.9f, 0f)
                    close()
                }
                canvas.drawPath(path, strokePaint)
                canvas.drawCircle(0f, 0f, 0.26f, fillPaint)
            }

            Glyph.EYE_CLOSED -> {
                // 下眼睑弧线 + 斜杠
                val path = Path().apply {
                    moveTo(-0.9f, 0.18f)
                    cubicTo(-0.3f, 0.68f, 0.3f, 0.68f, 0.9f, 0.18f)
                }
                canvas.drawPath(path, strokePaint)
                canvas.drawLine(-0.62f, -0.58f, 0.62f, 0.3f, strokePaint)
            }

            Glyph.TARGET -> {
                canvas.drawCircle(0f, 0f, 0.62f, strokePaint)
                canvas.drawCircle(0f, 0f, 0.18f, fillPaint)
            }
        }
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        strokePaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        strokePaint.colorFilter = colorFilter
    }

    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        /** 归一化坐标下的描边宽度 */
        const val STROKE_WIDTH = 0.17f
    }
}
