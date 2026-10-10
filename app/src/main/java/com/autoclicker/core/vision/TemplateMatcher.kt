package com.autoclicker.core.vision

import android.graphics.Bitmap
import com.autoclicker.domain.model.Rect
import kotlin.math.abs

/**
 * 模板匹配器（FR-7 / NFR-5，Task 11）。
 *
 * 纯 Kotlin + Android [Bitmap] 像素实现，不引入 OpenCV 等任何第三方库，不依赖 Activity。
 *
 * ## 相似度定义
 * 对模板与屏幕候选区域逐像素比较 RGB 三通道绝对色差，归一化到 0..1：
 *
 *      meanDiff = 所有像素 (|ΔR| + |ΔG| + |ΔB|) 之和 / (像素数 × 3)
 *      similarity = 1 - meanDiff / 255
 *
 * 完全相同 → 1；颜色完全相反 → 接近 0。忽略 Alpha 通道（投屏帧 Alpha 恒为 255）。
 *
 * ## 性能策略（中端机限定区域单次匹配 ≤500ms）
 * 1. **粗扫**：滑窗位置按 [COARSE_STEP]=4px 步进，模板内部按自适应像素采样
 *    （2~4，根据"位置数 × 每位置采样像素"估算的总运算量调节），并做**预算早退**：
 *    累计色差一旦超过"达到阈值所允许的总色差"立即判定该窗口不匹配。
 *    粗扫阈值比目标阈值低 [COARSE_MARGIN]=0.08，避免漏召。
 * 2. **精修**：对粗扫候选（最多 [MAX_CANDIDATES] 个、取得分最高者）在其
 *    ±3px 邻域内逐像素位置做全量比对，返回达到目标阈值的最佳位置。
 *
 * 步长 4px 保证真实匹配位置距某个粗扫点不超过 3px，配合粗扫降阈 + 邻域精修，
 * 在不漏匹配的前提下把计算量降低约一个数量级。
 */
class TemplateMatcher {

    /**
     * 一次模板匹配结果。
     *
     * @property left 匹配矩形左上角 x（屏幕绝对坐标）
     * @property top 匹配矩形左上角 y（屏幕绝对坐标）
     * @property centerX 匹配矩形中心 x（用于 WaitImage 点击匹配点）
     * @property centerY 匹配矩形中心 y
     * @property score 实际相似度得分 0..1
     */
    data class Match(
        val left: Int,
        val top: Int,
        val centerX: Int,
        val centerY: Int,
        val score: Double,
    )

    /** 粗扫候选位置（内部使用） */
    private data class Candidate(val x: Int, val y: Int, val score: Double)

    /**
     * 在屏幕 [screen] 上匹配 [template]。
     *
     * @param similarity 相似度阈值 0..1（越界自动夹取），只返回得分 ≥ 该值的最佳匹配
     * @param region 限定找图区域（屏幕绝对坐标），null 表示全屏；区域会自动夹取到屏幕内
     * @return 最佳匹配；无满足阈值的位置、模板大于区域/屏幕时返回 null
     */
    fun match(
        screen: Bitmap,
        template: Bitmap,
        similarity: Double,
        region: Rect? = null,
    ): Match? {
        val tw = template.width
        val th = template.height
        if (tw <= 0 || th <= 0 || screen.width <= 0 || screen.height <= 0) return null
        if (tw > screen.width || th > screen.height) return null
        val threshold = similarity.coerceIn(0.0, 1.0)

        // 搜索区域：夹取到屏幕范围内
        val areaLeft = (region?.left ?: 0).coerceIn(0, screen.width - 1)
        val areaTop = (region?.top ?: 0).coerceIn(0, screen.height - 1)
        val areaRight = (region?.right ?: screen.width).coerceIn(areaLeft + 1, screen.width)
        val areaBottom = (region?.bottom ?: screen.height).coerceIn(areaTop + 1, screen.height)
        val areaW = areaRight - areaLeft
        val areaH = areaBottom - areaTop
        if (areaW < tw || areaH < th) return null
        val maxX = areaW - tw
        val maxY = areaH - th

        // 一次性拷贝像素：搜索区域像素 + 模板像素，后续全部走数组运算
        val screenPixels = IntArray(areaW * areaH)
        screen.getPixels(screenPixels, 0, areaW, areaLeft, areaTop, areaW, areaH)
        val templatePixels = IntArray(tw * th)
        template.getPixels(templatePixels, 0, tw, 0, 0, tw, th)

        // 自适应像素采样：粗扫总运算量估算超过预算时加大模板内部采样
        val positionCount = (maxX / COARSE_STEP + 1).toLong() * (maxY / COARSE_STEP + 1)
        var pixelSample = MIN_PIXEL_SAMPLE
        while (pixelSample < MAX_PIXEL_SAMPLE &&
            positionCount * (tw / pixelSample).toLong() * (th / pixelSample) > COARSE_OP_BUDGET
        ) {
            pixelSample += 1
        }

        // 粗扫：降阈收集候选，候选数量封顶（保留得分最高者）
        val coarseThreshold = (threshold - COARSE_MARGIN).coerceAtLeast(0.0)
        val candidates = ArrayList<Candidate>(MAX_CANDIDATES)
        var py = 0
        while (py <= maxY) {
            var px = 0
            while (px <= maxX) {
                val score = coarseScore(
                    screenPixels, areaW, templatePixels, tw, th, px, py, pixelSample, coarseThreshold,
                )
                if (score >= coarseThreshold) addCandidate(candidates, Candidate(px, py, score))
                px += COARSE_STEP
            }
            py += COARSE_STEP
        }
        if (candidates.isEmpty()) return null

        // 精修：每个候选 ±(COARSE_STEP-1) 邻域逐像素位置全量比对
        var best: Candidate? = null
        for (c in candidates) {
            val x0 = (c.x - COARSE_STEP + 1).coerceAtLeast(0)
            val x1 = (c.x + COARSE_STEP - 1).coerceAtMost(maxX)
            val y0 = (c.y - COARSE_STEP + 1).coerceAtLeast(0)
            val y1 = (c.y + COARSE_STEP - 1).coerceAtMost(maxY)
            var yy = y0
            while (yy <= y1) {
                var xx = x0
                while (xx <= x1) {
                    val s = fullScore(
                        screenPixels, areaW, templatePixels, tw, th, xx, yy, threshold,
                    )
                    if (s >= threshold && (best == null || s > best!!.score)) {
                        best = Candidate(xx, yy, s)
                    }
                    xx += 1
                }
                yy += 1
            }
        }

        val b = best ?: return null
        return Match(
            left = areaLeft + b.x,
            top = areaTop + b.y,
            centerX = areaLeft + b.x + tw / 2,
            centerY = areaTop + b.y + th / 2,
            score = b.score,
        )
    }

    /**
     * 粗扫打分：模板像素按 [sample] 网格采样，预算早退。
     * 预算 = 若最终相似度要达到 [threshold]，全部像素允许的总色差和；
     * 已比较像素的色差和超过该预算时，剩余像素只可能更差，立即返回 -1。
     */
    private fun coarseScore(
        screenPixels: IntArray,
        areaW: Int,
        templatePixels: IntArray,
        tw: Int,
        th: Int,
        px: Int,
        py: Int,
        sample: Int,
        threshold: Double,
    ): Double {
        val budget = tw.toLong() * th * 3 * 255.0 * (1.0 - threshold)
        var diffSum = 0L
        var compared = 0
        var ty = 0
        while (ty < th) {
            val rowScreen = (py + ty) * areaW + px
            val rowTpl = ty * tw
            var tx = 0
            while (tx < tw) {
                diffSum += channelAbsDiff(
                    screenPixels[rowScreen + tx],
                    templatePixels[rowTpl + tx],
                )
                compared += 1
                if (diffSum > budget) return -1.0
                tx += sample
            }
            ty += sample
        }
        val mean = diffSum.toDouble() / (compared * 3)
        return 1.0 - mean / 255.0
    }

    /**
     * 全量打分：模板内逐像素比较，预算早退（规则同 [coarseScore]）。
     */
    private fun fullScore(
        screenPixels: IntArray,
        areaW: Int,
        templatePixels: IntArray,
        tw: Int,
        th: Int,
        px: Int,
        py: Int,
        threshold: Double,
    ): Double {
        val budget = tw.toLong() * th * 3 * 255.0 * (1.0 - threshold)
        var diffSum = 0L
        var ty = 0
        while (ty < th) {
            val rowScreen = (py + ty) * areaW + px
            val rowTpl = ty * tw
            var tx = 0
            while (tx < tw) {
                diffSum += channelAbsDiff(
                    screenPixels[rowScreen + tx],
                    templatePixels[rowTpl + tx],
                )
                if (diffSum > budget) return -1.0
                tx += 1
            }
            ty += 1
        }
        val mean = diffSum.toDouble() / (tw * th * 3)
        return 1.0 - mean / 255.0
    }

    /** 维护粗扫候选列表：数量封顶，新候选优于最差候选时替换 */
    private fun addCandidate(list: ArrayList<Candidate>, candidate: Candidate) {
        if (list.size < MAX_CANDIDATES) {
            list.add(candidate)
            return
        }
        var worstIndex = 0
        for (i in 1 until list.size) {
            if (list[i].score < list[worstIndex].score) worstIndex = i
        }
        if (candidate.score > list[worstIndex].score) list[worstIndex] = candidate
    }

    private companion object {
        /** 粗扫滑窗步长（px）：≤4，保证不漏匹配 */
        const val COARSE_STEP = 4

        /** 粗扫阈值裕量：粗扫按 threshold - 0.08 召候选，防止位移造成漏召 */
        const val COARSE_MARGIN = 0.08

        /** 粗扫候选数量上限（控制精修计算量） */
        const val MAX_CANDIDATES = 8

        /** 模板内部像素采样范围 */
        const val MIN_PIXEL_SAMPLE = 2
        const val MAX_PIXEL_SAMPLE = 4

        /** 粗扫总运算量预算（位置数 × 每位置采样像素数），超出则加大采样 */
        const val COARSE_OP_BUDGET = 20_000_000L
    }
}

/**
 * 两个 ARGB 颜色的三通道绝对色差之和（范围 0..765）。
 * 内联函数，避免在热循环中产生函数调用开销。
 */
private inline fun channelAbsDiff(a: Int, b: Int): Int {
    var d = abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF))
    d += abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF))
    d += abs((a and 0xFF) - (b and 0xFF))
    return d
}
