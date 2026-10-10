package com.autoclicker.vision

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

    override suspend fun find(
        templateId: String,
        similarity: Float,
        region: PixelRect?,
        frame: ScreenFrame,
    ): PixelPoint? {
        val template = templateRepository.loadBitmap(templateId) ?: return null
        val tw = template.width
        val th = template.height
        if (tw <= 0 || th <= 0) return null

        // 以 Bitmap 实际尺寸为准，避免与 frame.width/height 不一致导致数组越界
        val bitmap = frame.bitmap
        val sw = bitmap.width
        val sh = bitmap.height
        if (sw <= 0 || sh <= 0) return null

        val templatePixels = IntArray(tw * th)
        template.getPixels(templatePixels, 0, tw, 0, 0, tw, th)

        val scenePixels = IntArray(sw * sh)
        bitmap.getPixels(scenePixels, 0, sw, 0, 0, sw, sh)

        return NccMatcher.findBest(
            template = templatePixels,
            tw = tw,
            th = th,
            scene = scenePixels,
            sw = sw,
            sh = sh,
            region = region,
            similarity = similarity,
        )
    }
}