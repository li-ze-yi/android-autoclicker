package com.autoclicker.vision

import com.autoclicker.domain.model.PixelPoint
import com.autoclicker.domain.model.PixelRect
import kotlin.math.sqrt

/**
 * 归一化互相关（NCC）模板匹配器。
 *
 * 纯 Kotlin 数学实现，输入为 ARGB 像素数组，不依赖任何 Android API，便于 JVM 单测。
 *
 * 性能策略：
 * 1. 先按搜索区域（region）裁剪出子画面并转灰度，避免处理整屏；
 * 2. 多尺度金字塔：各自按 4/2 倍盒式降采样粗搜，逐级在半倍数局部窗口内精搜；
 * 3. 最后在原始分辨率下于候选点附近的小窗口内逐像素精搜，得到精确坐标。
 *
 * 相似度采用 Pearson 相关系数（均值-方差归一化），取值 [-1, 1]：
 * 1 表示完全相同，低于阈值返回 null。对亮度整体偏移/增益具有鲁棒性。
 */
object NccMatcher {

    /**
     * 在 [scene] 内查找 [template] 的最佳匹配位置。
     *
     * @param template 模板像素（ARGB），长度为 tw*th
     * @param tw 模板宽
     * @param th 模板高
     * @param scene 场景像素（ARGB），长度为 sw*sh
     * @param sw 场景宽
     * @param sh 场景高
     * @param region 搜索区域（像素，相对场景左上角）；为 null 表示全画面
     * @param similarity 相似度阈值，命中需 >= 该值
     * @return 命中时返回**匹配区域中心点**的像素坐标；未命中或参数非法返回 null
     */
    fun findBest(
        template: IntArray,
        tw: Int,
        th: Int,
        scene: IntArray,
        sw: Int,
        sh: Int,
        region: PixelRect?,
        similarity: Float,
    ): PixelPoint? {
        if (tw <= 0 || th <= 0 || sw <= 0 || sh <= 0) return null
        if (template.size < tw * th || scene.size < sw * sh) return null
        if (tw > sw || th > sh) return null

        // 裁剪搜索区域
        val rl = (region?.l ?: 0).coerceIn(0, sw)
        val rt = (region?.t ?: 0).coerceIn(0, sh)
        val rr = (region?.r ?: sw).coerceIn(0, sw)
        val rb = (region?.b ?: sh).coerceIn(0, sh)
        val rw = rr - rl
        val rh = rb - rt
        if (rw < tw || rh < th) return null

        // 子画面灰度化
        val sceneGray = DoubleArray(rw * rh)
        for (y in 0 until rh) {
            val srcRow = (rt + y) * sw + rl
            val dstRow = y * rw
            for (x in 0 until rw) {
                sceneGray[dstRow + x] = luminance(scene[srcRow + x])
            }
        }

        // 模板灰度化
        val tplGray = DoubleArray(tw * th)
        for (i in 0 until tw * th) tplGray[i] = luminance(template[i])

        val maxX = rw - tw
        val maxY = rh - th
        if (maxX < 0 || maxY < 0) return null

        // 选择起始降采样倍数
        val startFactor = when {
            tw >= 16 && th >= 16 && rw >= 64 && rh >= 64 -> 4
            tw >= 8 && th >= 8 && rw >= 32 && rh >= 32 -> 2
            else -> 1
        }

        var bestX = 0
        var bestY = 0
        var bestScore = -1.0

        // 当前搜索窗口（原始分辨率下的候选左上角范围）
        var winL = 0
        var winT = 0
        var winR = maxX
        var winB = maxY

        var f = startFactor
        while (true) {
            val res = searchLevel(sceneGray, rw, rh, tplGray, tw, th, f, winL, winT, winR, winB)
            if (f == 1) {
                bestX = res.x
                bestY = res.y
                bestScore = res.score
                break
            }
            if (res.score <= -1.0) {
                // 该降采样层无法计算有效相似度（模板/画面近似恒定），
                // 放弃金字塔，回到原始分辨率全窗口精搜，避免漏检。
                winL = 0
                winT = 0
                winR = maxX
                winB = maxY
                f = 1
                continue
            }
            // 将粗搜结果映射回原始分辨率，并建立局部精搜窗口
            val cfx = res.x * f
            val cfy = res.y * f
            val rad = f * 2 + 2
            winL = (cfx - rad).coerceIn(0, maxX)
            winT = (cfy - rad).coerceIn(0, maxY)
            winR = (cfx + rad).coerceIn(0, maxX)
            winB = (cfy + rad).coerceIn(0, maxY)
            f = if (f >= 4) 2 else 1
        }

        if (bestScore < similarity) {
            // 金字塔粗搜可能因模板与降采样网格未对齐而漏检（小模板尤其明显）：
            // 回退为全分辨率全窗口精搜，保证不漏匹配。仅在快速路径未命中时才付此代价。
            val full = searchLevel(sceneGray, rw, rh, tplGray, tw, th, 1, 0, 0, maxX, maxY)
            if (full.score > bestScore) {
                bestX = full.x
                bestY = full.y
                bestScore = full.score
            }
        }

        if (bestScore < similarity) return null
        // 返回匹配区域中心点，便于直接作为点击坐标
        return PixelPoint(rl + bestX + tw / 2, rt + bestY + th / 2)
    }

    /** 单层搜索结果：坐标为该降采样层坐标系下的模板左上角。 */
    private class LevelResult(val x: Int, val y: Int, val score: Double)

    /**
     * 在指定降采样倍数 [f] 下，于原始分辨率窗口 [winL,winT,winR,winB] 内搜索最佳位置。
     * 返回值为该层坐标系下的左上角坐标（乘 f 即得原始分辨率坐标）。
     */
    private fun searchLevel(
        sceneGray: DoubleArray,
        rw: Int,
        rh: Int,
        tplGray: DoubleArray,
        tw: Int,
        th: Int,
        f: Int,
        winL: Int,
        winT: Int,
        winR: Int,
        winB: Int,
    ): LevelResult {
        val scene = if (f == 1) GrayImage(sceneGray, rw, rh) else downsample(sceneGray, rw, rh, f)
        val tpl = if (f == 1) GrayImage(tplGray, tw, th) else downsample(tplGray, tw, th, f)

        val swc = scene.w
        val shc = scene.h
        val twc = tpl.w
        val thc = tpl.h
        if (twc <= 0 || thc <= 0 || swc < twc || shc < thc) {
            return LevelResult(0, 0, -1.0)
        }

        // 模板统计量
        val tMean = mean(tpl.data)
        val tNorm = sqrt(sumSqDev(tpl.data, tMean))

        val maxFx = swc - twc
        val maxFy = shc - thc
        val fx0 = (winL / f).coerceIn(0, maxFx)
        val fy0 = (winT / f).coerceIn(0, maxFy)
        val fx1 = (winR / f).coerceIn(0, maxFx)
        val fy1 = (winB / f).coerceIn(0, maxFy)

        var best = -1.0
        var bx = fx0
        var by = fy0
        for (fy in fy0..fy1) {
            for (fx in fx0..fx1) {
                val score = ncc(scene.data, swc, fx, fy, tpl.data, twc, thc, tMean, tNorm)
                if (score > best) {
                    best = score
                    bx = fx
                    by = fy
                }
            }
        }
        return LevelResult(bx, by, best)
    }

    /** Pearson 相关系数；任一方差为 0 时返回 -1（视为不可匹配）。 */
    private fun ncc(
        scene: DoubleArray,
        sw: Int,
        fx: Int,
        fy: Int,
        tpl: DoubleArray,
        tw: Int,
        th: Int,
        tMean: Double,
        tNorm: Double,
    ): Double {
        var sumS = 0.0
        var sumS2 = 0.0
        var sumTS = 0.0
        var idx = 0
        for (y in 0 until th) {
            val row = (fy + y) * sw + fx
            for (x in 0 until tw) {
                val s = scene[row + x]
                sumS += s
                sumS2 += s * s
                sumTS += tpl[idx] * s
                idx++
            }
        }
        val n = (tw * th).toDouble()
        val sMean = sumS / n
        val sVar = sumS2 - n * sMean * sMean
        val sNorm = sqrt(if (sVar > 0.0) sVar else 0.0)
        if (tNorm < 1e-6 || sNorm < 1e-6) return -1.0
        val num = sumTS - n * tMean * sMean
        return num / (tNorm * sNorm)
    }

    private class GrayImage(val data: DoubleArray, val w: Int, val h: Int)

    /** 盒式降采样：每个输出像素取 f×f 块的均值。 */
    private fun downsample(src: DoubleArray, w: Int, h: Int, f: Int): GrayImage {
        val ow = w / f
        val oh = h / f
        if (ow <= 0 || oh <= 0) return GrayImage(DoubleArray(0), 0, 0)
        val out = DoubleArray(ow * oh)
        val inv = 1.0 / (f * f)
        for (oy in 0 until oh) {
            val srcRow = oy * f * w
            val dstRow = oy * ow
            for (ox in 0 until ow) {
                var sum = 0.0
                val base = srcRow + ox * f
                for (dy in 0 until f) {
                    val row = base + dy * w
                    for (dx in 0 until f) {
                        sum += src[row + dx]
                    }
                }
                out[dstRow + ox] = sum * inv
            }
        }
        return GrayImage(out, ow, oh)
    }

    private fun mean(a: DoubleArray): Double {
        if (a.isEmpty()) return 0.0
        var s = 0.0
        for (v in a) s += v
        return s / a.size
    }

    private fun sumSqDev(a: DoubleArray, m: Double): Double {
        var s = 0.0
        for (v in a) {
            val d = v - m
            s += d * d
        }
        return s
    }

    /** 转为灰度亮度（Rec.601 系数）。 */
    private fun luminance(argb: Int): Double {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return 0.299 * r + 0.587 * g + 0.114 * b
    }
}