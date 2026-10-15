package com.autoclicker.vision

import com.autoclicker.core.bus.LogLevel
import com.autoclicker.core.bus.RuntimeBus
import com.autoclicker.data.repository.TemplateRepository
import com.autoclicker.domain.model.PixelPoint
import com.autoclicker.domain.model.PixelRect
import com.autoclicker.platform.ImageFinder
import com.autoclicker.platform.ScreenFrame

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

    private companion object {
        /**
         * 参数非法（模板/画面尺寸为 0）时的配对分：写入一个非空值，
         * 让日志能区分「本次根本没做匹配」与「匹配了但分数不够」，避免排查时看不到任何数值。
         */
        const val INVALID_SEARCH_SCORE = -1.0
    }
}