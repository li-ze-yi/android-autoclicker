package com.autoclicker.vision

import com.autoclicker.core.bus.LogLevel
import com.autoclicker.core.bus.RuntimeBus
import com.autoclicker.data.repository.TemplateRepository
import com.autoclicker.domain.model.PercentRect
import com.autoclicker.domain.model.PixelPoint
import com.autoclicker.domain.model.PixelRect
import com.autoclicker.platform.ImageFinder
import com.autoclicker.platform.ScreenFrame
import kotlin.math.roundToInt

/**
 * 基于 [NccMatcher] 的模板图像查找器。
 *
 * 从 [TemplateRepository] 加载模板 Bitmap，与屏幕帧一并转成 ARGB 的 IntArray，
 * 交给纯数学的 [NccMatcher] 完成多尺度匹配。
 */
class TemplateImageFinder(
    private val templateRepository: TemplateRepository,
) : ImageFinder {

    /** 最近一次匹配的最高相似度，仅用于日志诊断（判断是不是「擦边命中」）。 */
    @Volatile
    private var lastScoreValue: Double? = null

    override fun lastScore(): Double? = lastScoreValue

    override suspend fun find(
        templateId: String,
        similarity: Float,
        region: PixelRect?,
        frame: ScreenFrame,
    ): PixelPoint? {
        val template = templateRepository.loadBitmap(templateId)
        if (template == null) {
            // 与「画面里没有目标」区分开：模板文件缺失属于配置问题，
            // 且此时不能沿用上一次匹配的相似度（陈旧值会误导日志排查）。
            lastScoreValue = null
            RuntimeBus.log(LogLevel.WARN, "模板文件不存在或加载失败：$templateId")
            return null
        }
        val tw = template.width
        val th = template.height
        if (tw <= 0 || th <= 0) {
            lastScoreValue = INVALID_SEARCH_SCORE
            return null
        }

        // 以 Bitmap 实际尺寸为准，避免与 frame.width/height 不一致导致数组越界
        val bitmap = frame.bitmap
        val sw = bitmap.width
        val sh = bitmap.height
        if (sw <= 0 || sh <= 0) {
            lastScoreValue = INVALID_SEARCH_SCORE
            return null
        }

        // 只拷搜索区域内的像素：限定区域时不再为整屏分配 IntArray（1080×2400 约 10MB）。
        var rl = (region?.l ?: 0).coerceIn(0, sw)
        var rt = (region?.t ?: 0).coerceIn(0, sh)
        val rr = (region?.r ?: sw).coerceIn(rl, sw)
        val rb = (region?.b ?: sh).coerceIn(rt, sh)
        var cw = rr - rl
        var ch = rb - rt
        if (cw < tw || ch < th) {
            // 区域比模板还小，这个区域里永远放不下模板，继续搜只会一直失败。
            // 直接失败会让「限定区域」配置不当表现为「识别永久失效」，故忽略区域改为全屏并明确告警。
            RuntimeBus.log(
                LogLevel.WARN,
                "识别区域 ${cw}x${ch} 小于模板 ${tw}x${th}，已忽略该区域改为全屏识别",
            )
            rl = 0
            rt = 0
            cw = sw
            ch = sh
        }

        val templatePixels = IntArray(tw * th)
        template.getPixels(templatePixels, 0, tw, 0, 0, tw, th)

        val scenePixels = IntArray(cw * ch)
        bitmap.getPixels(scenePixels, 0, cw, rl, rt, cw, ch)

        return NccMatcher.findBestWithScore(
            template = templatePixels,
            tw = tw,
            th = th,
            scene = scenePixels,
            sw = cw,
            sh = ch,
            region = null,
            similarity = similarity,
        ).also { lastScoreValue = it.score }.point?.let { PixelPoint(it.x + rl, it.y + rt) }
    }

    override suspend fun findFirst(
        templateIds: List<String>,
        similarity: Float,
        actionRegion: PixelRect?,
        frame: ScreenFrame,
    ): Pair<String, PixelPoint>? {
        if (templateIds.isEmpty()) return null
        val bitmap = frame.bitmap
        val sw = bitmap.width
        val sh = bitmap.height
        if (sw <= 0 || sh <= 0) {
            lastScoreValue = INVALID_SEARCH_SCORE
            return null
        }

        // 1) 逐个成员解析有效搜索区域：动作 region 覆盖全体 > 成员模板自带 region > 全屏。
        val members = ArrayList<Member>(templateIds.size)
        for (id in templateIds) {
            val template = templateRepository.loadBitmap(id)
            if (template == null) {
                // 单个成员缺失不该让整组失效：跳过并告警，继续试后面的模板。
                RuntimeBus.log(LogLevel.WARN, "模板文件不存在或加载失败（已跳过）：$id")
                continue
            }
            val tw = template.width
            val th = template.height
            if (tw <= 0 || th <= 0) continue
            var region = actionRegion
                ?: templateRepository.get(id)?.region?.let { toPixelRect(it, sw, sh) }
            if (region != null && (region.width < tw || region.height < th)) {
                // 区域比模板还小，这个区域里永远放不下该模板；与单模板识别一致，忽略区域改为全屏。
                RuntimeBus.log(
                    LogLevel.WARN,
                    "识别区域 ${region.width}x${region.height} 小于模板 $id（${tw}x${th}），已忽略该区域改为全屏识别",
                )
                region = null
            }
            val pixels = IntArray(tw * th)
            template.getPixels(pixels, 0, tw, 0, 0, tw, th)
            members += Member(id, NccMatcher.grayOf(pixels, tw * th), tw, th, region)
        }
        if (members.isEmpty()) return null

        // 2) 联合外接矩形：所有成员的区域取并集，只拷一次像素、只灰度一次；
        //    成员匹配时再从这份灰度图里按各自区域做子矩形裁剪（仅行拷贝，开销极小）。
        var uL = sw
        var uT = sh
        var uR = 0
        var uB = 0
        for (m in members) {
            val r = m.region
            if (r == null) {
                // 有任一成员需全屏搜索，联合区域就是全屏。
                uL = 0
                uT = 0
                uR = sw
                uB = sh
                break
            }
            if (r.l < uL) uL = r.l
            if (r.t < uT) uT = r.t
            if (r.r > uR) uR = r.r
            if (r.b > uB) uB = r.b
        }
        val uw = uR - uL
        val uh = uB - uT
        if (uw <= 0 || uh <= 0) {
            lastScoreValue = INVALID_SEARCH_SCORE
            return null
        }
        val unionPixels = IntArray(uw * uh)
        bitmap.getPixels(unionPixels, 0, uw, uL, uT, uw, uh)
        val unionGray = NccMatcher.grayOf(unionPixels, uw * uh)

        // 3) 按组内顺序逐个匹配，命中第一个立即返回（不跑完整组）。
        for (m in members) {
            val r = m.region
            val subL = (r?.l ?: uL) - uL
            val subT = (r?.t ?: uT) - uT
            val subW = r?.width ?: uw
            val subH = r?.height ?: uh
            val sceneGray = subGray(unionGray, uw, subL, subT, subW, subH)
            val result = NccMatcher.findBestGray(m.tplGray, m.tw, m.th, sceneGray, subW, subH, similarity)
            lastScoreValue = result.score
            val p = result.point ?: continue
            return m.id to PixelPoint(p.x + uL + subL, p.y + uT + subT)
        }
        return null
    }

    /** 成员模板及其有效搜索区域（像素）；region 为 null 表示全屏。 */
    private class Member(
        val id: String,
        val tplGray: DoubleArray,
        val tw: Int,
        val th: Int,
        val region: PixelRect?,
    )

    /** 从联合灰度图里裁出成员自己的区域（仅行拷贝）；整块就是它时直接复用，不再复制。 */
    private fun subGray(src: DoubleArray, sw: Int, l: Int, t: Int, w: Int, h: Int): DoubleArray {
        if (l == 0 && t == 0 && w == sw && sw * h == src.size) return src
        val out = DoubleArray(w * h)
        for (y in 0 until h) {
            System.arraycopy(src, (t + y) * sw + l, out, y * w, w)
        }
        return out
    }

    /** 百分比区域转像素；口径与 CoordinateMapper.toPixel 一致（归一化后四舍五入）。 */
    private fun toPixelRect(rect: PercentRect, w: Int, h: Int): PixelRect = PixelRect(
        (minOf(rect.l, rect.r) * w).roundToInt().coerceIn(0, w),
        (minOf(rect.t, rect.b) * h).roundToInt().coerceIn(0, h),
        (maxOf(rect.l, rect.r) * w).roundToInt().coerceIn(0, w),
        (maxOf(rect.t, rect.b) * h).roundToInt().coerceIn(0, h),
    )

    private companion object {
        /**
         * 参数非法（模板/画面尺寸为 0）时的配对分：写入一个非空值，
         * 让日志能区分「本次根本没做匹配」与「匹配了但分数不够」，避免排查时看不到任何数值。
         */
        const val INVALID_SEARCH_SCORE = -1.0
    }
}