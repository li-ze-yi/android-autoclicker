package com.autoclicker.core.vision

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * 模板匹配器：灰度化 + 降采样 + SAD（绝对差之和，带提前终止）。
 *
 * 性能说明：主循环只读写 Int 数组，不再调用任何 Bitmap API，也不分配对象；
 * 工作区临时位图统一在 finally 中回收，传入的 screen / template 不做 recycle。
 */
object TemplateMatcher {

    /**
     * 在 screen 中查找 template。
     *
     * @param region    null 或宽/高 <= 0 表示全屏；否则与屏幕取交集。
     * @param threshold 相似度阈值（0f~1f）。
     * @return 命中返回整屏坐标中心点，未命中返回 null。
     */
    fun findMatch(screen: Bitmap, template: Bitmap, region: Rect?, threshold: Float): MatchResult? {
        if (screen.width <= 0 || screen.height <= 0) return null
        if (template.width <= 0 || template.height <= 0) return null
        if (template.width > screen.width || template.height > screen.height) return null

        val temps = ArrayList<Bitmap>(4)

        // 记录临时位图，跳过传入的原始位图，避免误回收调用方位图。
        fun remember(bitmap: Bitmap) {
            if (bitmap !== screen && bitmap !== template && !temps.contains(bitmap)) {
                temps.add(bitmap)
            }
        }

        try {
            // 1. 计算搜索区域：无效区域返回 null。
            val searchRect: Rect = if (region != null && region.width() > 0 && region.height() > 0) {
                val r = Rect()
                if (!r.setIntersect(region, Rect(0, 0, screen.width, screen.height))) return null
                r
            } else {
                Rect(0, 0, screen.width, screen.height)
            }
            if (searchRect.width() <= 0 || searchRect.height() <= 0) return null

            // 2. 降采样因子：以搜索区宽度为准，同时保证降采样后模板最小边 >= 6px。
            var factor = max(1, ceil(searchRect.width() / 240.0).toInt())
            val templateMinSide = min(template.width, template.height)
            while (factor > 1 && templateMinSide / factor < 6) {
                factor--
            }

            val scaledScreenW = max(1, searchRect.width() / factor)
            val scaledScreenH = max(1, searchRect.height() / factor)
            val scaledTemplateW = max(1, template.width / factor)
            val scaledTemplateH = max(1, template.height / factor)
            // 降采样后模板仍大于搜索区则无法匹配。
            if (scaledTemplateW > scaledScreenW || scaledTemplateH > scaledScreenH) return null

            // 3. 裁剪 + 缩放得到工作区位图。
            val crop = Bitmap.createBitmap(
                screen, searchRect.left, searchRect.top, searchRect.width(), searchRect.height()
            )
            remember(crop)
            val workScreen = Bitmap.createScaledBitmap(crop, scaledScreenW, scaledScreenH, false)
            remember(workScreen)
            val workTemplate = Bitmap.createScaledBitmap(template, scaledTemplateW, scaledTemplateH, false)
            remember(workTemplate)

            // 4. 灰度化，使用 IntArray。
            val screenGray = IntArray(scaledScreenW * scaledScreenH)
            workScreen.getPixels(screenGray, 0, scaledScreenW, 0, 0, scaledScreenW, scaledScreenH)
            toGrayscale(screenGray)

            val templateGray = IntArray(scaledTemplateW * scaledTemplateH)
            workTemplate.getPixels(templateGray, 0, scaledTemplateW, 0, 0, scaledTemplateW, scaledTemplateH)
            toGrayscale(templateGray)

            // 5. SAD + 提前终止。
            val templatePixelCount = scaledTemplateW * scaledTemplateH
            var bestSad = Long.MAX_VALUE
            var bestX = 0
            var bestY = 0
            val maxStartY = scaledScreenH - scaledTemplateH
            val maxStartX = scaledScreenW - scaledTemplateW

            var y = 0
            while (y <= maxStartY) {
                var x = 0
                while (x <= maxStartX) {
                    var sad = 0L
                    var ty = 0
                    var aborted = false
                    while (ty < scaledTemplateH && !aborted) {
                        var ti = ty * scaledTemplateW
                        var si = (y + ty) * scaledScreenW + x
                        var tx = 0
                        while (tx < scaledTemplateW) {
                            val diff = screenGray[si] - templateGray[ti]
                            sad += if (diff < 0) -diff else diff
                            // 当前最优平均差 × 模板像素数 == bestSad，超过即剪枝。
                            if (bestSad != Long.MAX_VALUE && sad > bestSad) {
                                aborted = true
                                break
                            }
                            tx++
                            ti++
                            si++
                        }
                        ty++
                    }
                    if (!aborted && sad < bestSad) {
                        bestSad = sad
                        bestX = x
                        bestY = y
                    }
                    x++
                }
                y++
            }

            if (bestSad == Long.MAX_VALUE) return null

            // 6. 相似度与整屏中心坐标。
            val similarity = 1f - bestSad.toFloat() / (255f * templatePixelCount)
            if (similarity < threshold) return null

            val centerX = searchRect.left + bestX * factor + template.width / 2
            val centerY = searchRect.top + bestY * factor + template.height / 2
            return MatchResult(centerX, centerY)
        } catch (e: Exception) {
            return null
        } finally {
            for (b in temps) {
                try {
                    if (!b.isRecycled) b.recycle()
                } catch (e: Exception) {
                    // 忽略回收异常
                }
            }
        }
    }

    /** 就地灰度化：gray = (r * 299 + g * 587 + b * 114) / 1000。 */
    private fun toGrayscale(pixels: IntArray) {
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            pixels[i] = (r * 299 + g * 587 + b * 114) / 1000
        }
    }
}