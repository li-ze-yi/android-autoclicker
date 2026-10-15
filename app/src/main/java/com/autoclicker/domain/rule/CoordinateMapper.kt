package com.autoclicker.domain.rule

import com.autoclicker.domain.model.ActionOffset
import com.autoclicker.domain.model.PercentPoint
import com.autoclicker.domain.model.PercentRect
import com.autoclicker.domain.model.PixelPoint
import com.autoclicker.domain.model.PixelRect
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * 百分比坐标 <-> 像素坐标换算。运行期按当前屏幕尺寸构造。
 */
class CoordinateMapper(
    private val screenWidth: Int,
    private val screenHeight: Int,
) {
    fun toPixel(p: PercentPoint): PixelPoint = PixelPoint(
        (p.x * screenWidth).roundToInt().coerceIn(0, (screenWidth - 1).coerceAtLeast(0)),
        (p.y * screenHeight).roundToInt().coerceIn(0, (screenHeight - 1).coerceAtLeast(0)),
    )

    fun toPixel(rect: PercentRect): PixelRect = PixelRect(
        // 归一化：手填或拖拽都可能给出左右/上下颠倒的矩形，
        // 直接换算会得到 r<l 的矩形，后续按「宽度=r-l」计算时会被压成 0，区域形同虚设。
        (minOf(rect.l, rect.r) * screenWidth).roundToInt().coerceIn(0, screenWidth),
        (minOf(rect.t, rect.b) * screenHeight).roundToInt().coerceIn(0, screenHeight),
        (maxOf(rect.l, rect.r) * screenWidth).roundToInt().coerceIn(0, screenWidth),
        (maxOf(rect.t, rect.b) * screenHeight).roundToInt().coerceIn(0, screenHeight),
    )

    fun toPercent(p: PixelPoint): PercentPoint = PercentPoint(
        if (screenWidth <= 0) 0f else p.x.toFloat() / screenWidth,
        if (screenHeight <= 0) 0f else p.y.toFloat() / screenHeight,
    )

    fun toPercent(rect: PixelRect): PercentRect = PercentRect(
        if (screenWidth <= 0) 0f else rect.l.toFloat() / screenWidth,
        if (screenHeight <= 0) 0f else rect.t.toFloat() / screenHeight,
        if (screenWidth <= 0) 0f else rect.r.toFloat() / screenWidth,
        if (screenHeight <= 0) 0f else rect.b.toFloat() / screenHeight,
    )

    /** 在像素矩形内取随机点（拟人化点击）。 */
    fun randomPointIn(rect: PixelRect): PixelPoint {
        if (rect.width <= 0 || rect.height <= 0) return PixelPoint(rect.centerX, rect.centerY)
        return PixelPoint(
            Random.nextInt(rect.l, rect.r),
            Random.nextInt(rect.t, rect.b),
        )
    }

    /** 在命中点附近施加像素级随机偏移。 */
    fun jitter(point: PixelPoint, radius: Int): PixelPoint {
        if (radius <= 0) return point
        return PixelPoint(
            (point.x + Random.nextInt(-radius, radius + 1)).coerceIn(0, (screenWidth - 1).coerceAtLeast(0)),
            (point.y + Random.nextInt(-radius, radius + 1)).coerceIn(0, (screenHeight - 1).coerceAtLeast(0)),
        )
    }

    /** 先按 [offset] 固定平移，再按随机半径抖动，结果夹取在屏幕内。 */
    fun applyOffset(point: PixelPoint, offset: ActionOffset): PixelPoint {
        if (offset.isZero) return point
        return jitter(
            PixelPoint(point.x + offset.dx, point.y + offset.dy),
            offset.randomRadius,
        )
    }
}