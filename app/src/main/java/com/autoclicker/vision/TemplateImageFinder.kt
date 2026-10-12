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
        if (tw <= 0 || th <= 0) return null

        // 以 Bitmap 实际尺寸为准，避免与 frame.width/height 不一致导致数组越界
        val bitmap = frame.bitmap
        val sw = bitmap.width
        val sh = bitmap.height
        if (sw <= 0 || sh <= 0) return null

        // 只拷搜索区域内的像素：限定区域时不再为整屏分配 IntArray（1080×2400 约 10MB）。
        val rl = (region?.l ?: 0).coerceIn(0, sw)
        val rt = (region?.t ?: 0).coerceIn(0, sh)
        val rr = (region?.r ?: sw).coerceIn(rl, sw)
        val rb = (region?.b ?: sh).coerceIn(rt, sh)
        val cw = rr - rl
        val ch = rb - rt
        if (cw < tw || ch < th) return null

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
}