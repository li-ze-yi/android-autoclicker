package com.autoclicker.vision

import com.autoclicker.domain.model.PixelPoint
import com.autoclicker.domain.model.PixelRect
import com.autoclicker.platform.ColorFinder
import com.autoclicker.platform.ScreenFrame

/**
 * 区域找色：在指定的像素矩形 [PixelRect] 内按行优先顺序线性扫描，
 * 返回第一个与目标颜色各通道差值不超过容差的像素点。
 *
 * 仅比较 R/G/B 三通道，忽略 alpha，避免半透明叠加导致误判。
 */
class PixelColorFinder : ColorFinder {

    override fun find(
        color: Int,
        tolerance: Int,
        region: PixelRect,
        frame: ScreenFrame,
    ): PixelPoint? {
        val bitmap = frame.bitmap
        val sw = bitmap.width
        val sh = bitmap.height
        if (sw <= 0 || sh <= 0) return null

        val l = region.l.coerceIn(0, sw)
        val t = region.t.coerceIn(0, sh)
        val r = region.r.coerceIn(0, sw)
        val b = region.b.coerceIn(0, sh)
        val w = r - l
        val h = b - t
        if (w <= 0 || h <= 0) return null

        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, l, t, w, h)

        val tr = (color shr 16) and 0xFF
        val tg = (color shr 8) and 0xFF
        val tb = color and 0xFF
        val tol = if (tolerance < 0) 0 else tolerance

        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val px = pixels[row + x]
                val dr = ((px shr 16) and 0xFF) - tr
                val dg = ((px shr 8) and 0xFF) - tg
                val db = (px and 0xFF) - tb
                if ((if (dr < 0) -dr else dr) <= tol &&
                    (if (dg < 0) -dg else dg) <= tol &&
                    (if (db < 0) -db else db) <= tol
                ) {
                    return PixelPoint(l + x, t + y)
                }
            }
        }
        return null
    }
}