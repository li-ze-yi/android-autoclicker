package com.autoclicker.core.vision

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.abs

/**
 * 颜色匹配器：按步长在指定区域内扫描目标颜色。
 *
 * 性能说明：逐行 getPixels，只读取 region 子区域，避免在整屏上分配大数组。
 */
object ColorMatcher {

    /**
     * 在 screen 中查找与 color 相近的像素。
     *
     * @param region  null 或宽/高 <= 0 表示全屏；否则与屏幕取交集。
     * @param stepPx  采样步长，最小按 1 处理。
     * @return 命中返回该像素整屏坐标，未命中返回 null。
     */
    fun findColor(
        screen: Bitmap,
        color: Int,
        tolerance: Int,
        region: Rect?,
        stepPx: Int = 4
    ): MatchResult? {
        if (screen.width <= 0 || screen.height <= 0) return null
        val step = if (stepPx < 1) 1 else stepPx
        val tol = if (tolerance < 0) 0 else tolerance

        try {
            val rect: Rect = if (region != null && region.width() > 0 && region.height() > 0) {
                val r = Rect()
                if (!r.setIntersect(region, Rect(0, 0, screen.width, screen.height))) return null
                r
            } else {
                Rect(0, 0, screen.width, screen.height)
            }
            val w = rect.width()
            val h = rect.height()
            if (w <= 0 || h <= 0) return null

            val targetR = (color shr 16) and 0xFF
            val targetG = (color shr 8) and 0xFF
            val targetB = color and 0xFF

            val rowBuffer = IntArray(w)
            var y = 0
            while (y < h) {
                screen.getPixels(rowBuffer, 0, w, rect.left, rect.top + y, w, 1)
                var x = 0
                while (x < w) {
                    val c = rowBuffer[x]
                    val dr = abs(((c shr 16) and 0xFF) - targetR)
                    val dg = abs(((c shr 8) and 0xFF) - targetG)
                    val db = abs((c and 0xFF) - targetB)
                    if (dr <= tol && dg <= tol && db <= tol) {
                        return MatchResult(rect.left + x, rect.top + y)
                    }
                    x += step
                }
                y += step
            }
            return null
        } catch (e: Exception) {
            return null
        }
    }

    /** 读取指定整屏坐标的颜色；越界或异常返回 null。 */
    fun colorAt(screen: Bitmap, x: Int, y: Int): Int? {
        return try {
            if (x < 0 || y < 0 || x >= screen.width || y >= screen.height) {
                null
            } else {
                screen.getPixel(x, y)
            }
        } catch (e: Exception) {
            null
        }
    }
}