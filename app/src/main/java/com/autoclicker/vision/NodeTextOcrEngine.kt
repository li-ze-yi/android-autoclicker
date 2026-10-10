package com.autoclicker.vision

import com.autoclicker.domain.model.PixelRect
import com.autoclicker.platform.NodeLocator
import com.autoclicker.platform.OcrEngine
import com.autoclicker.platform.OcrItem
import com.autoclicker.platform.ScreenFrame

/**
 * P0 阶段的 OCR 占位实现：不接入真正的 OCR 库，而是从无障碍控件树读取可见文本，
 * 以节点文本 + 节点边界构成 [OcrItem]。当后续接入真实 OCR 引擎时替换本实现即可。
 *
 * [nodeLocatorProvider] 延迟获取 [NodeLocator]（其由无障碍服务在运行期注册），
 * 因而在识别前才判断可用性。
 */
class NodeTextOcrEngine(
    private val nodeLocatorProvider: () -> NodeLocator?,
) : OcrEngine {

    override fun isReady(): Boolean = nodeLocatorProvider() != null

    override suspend fun recognize(frame: ScreenFrame, region: PixelRect?): List<OcrItem> {
        val locator = nodeLocatorProvider() ?: return emptyList()
        val nodes = locator.readAllText()
        if (nodes.isEmpty()) return emptyList()

        val items = ArrayList<OcrItem>(nodes.size)
        for (node in nodes) {
            val text = node.text
            if (text.isNullOrBlank()) continue
            val bounds = node.bounds
            if (region != null && !intersects(bounds, region)) continue
            items.add(OcrItem(text = text, bounds = bounds))
        }
        return items
    }

    /** 两个矩形是否有非空重叠。 */
    private fun intersects(a: PixelRect, b: PixelRect): Boolean {
        val l = if (a.l > b.l) a.l else b.l
        val t = if (a.t > b.t) a.t else b.t
        val r = if (a.r < b.r) a.r else b.r
        val bb = if (a.b < b.b) a.b else b.b
        return r > l && bb > t
    }
}