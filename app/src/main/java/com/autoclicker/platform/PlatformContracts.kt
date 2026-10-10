package com.autoclicker.platform

import android.graphics.Bitmap
import com.autoclicker.domain.model.PixelPoint
import com.autoclicker.domain.model.PixelRect

/** 一帧屏幕画面。 */
class ScreenFrame(
    val bitmap: Bitmap,
    val width: Int,
    val height: Int,
)

/** 屏幕采集来源：无障碍截屏或 MediaProjection。 */
interface ScreenSource {
    /** 采集一帧；返回 null 表示未授权或采集失败。 */
    suspend fun capture(): ScreenFrame?

    /** 是否已就绪（已授权）。 */
    fun isReady(): Boolean

    fun release()
}

/** 一次手势中的单条轨迹（像素坐标 + 时间轴）。 */
data class PixelStroke(
    val points: List<PixelPoint>,
    val startTimeMs: Long,
    val durationMs: Long,
)

/** 手势派发能力（无障碍 dispatchGesture）。 */
interface GestureExecutor {
    fun isReady(): Boolean

    suspend fun click(x: Int, y: Int, durationMs: Long): Boolean

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean

    suspend fun perform(strokes: List<PixelStroke>): Boolean
}

/** 全局操作能力（返回键/桌面/最近任务/打开应用/关闭应用）。 */
interface GlobalActions {
    fun back(): Boolean

    fun home(): Boolean

    fun recents(): Boolean

    fun openApp(packageName: String, activity: String?): Boolean

    fun closeApp(packageName: String): Boolean
}

/** 无障碍节点信息。 */
data class NodeInfo(
    val text: String?,
    val viewId: String?,
    val className: String?,
    val contentDesc: String?,
    val bounds: PixelRect,
    val clickable: Boolean,
)

/** 节点定位能力（无障碍控件树查询）。 */
interface NodeLocator {
    fun isReady(): Boolean

    /** 查询当前窗口匹配的节点（已按点击优先级排序）。 */
    suspend fun findNodes(selector: com.autoclicker.domain.model.NodeSelector): List<NodeInfo>

    /** 读取屏幕上的全部文本（用于「点击文字」与内容提取）。 */
    suspend fun readAllText(): List<NodeInfo>
}

/** OCR 识别结果项。 */
data class OcrItem(
    val text: String,
    val bounds: PixelRect,
)

/** OCR 引擎（P0 预留接口，默认实现返回空列表）。 */
interface OcrEngine {
    fun isReady(): Boolean

    suspend fun recognize(frame: ScreenFrame, region: PixelRect?): List<OcrItem>
}

/** 图像模板匹配能力。 */
interface ImageFinder {
    /** 返回命中点（像素），未命中返回 null。 */
    suspend fun find(
        templateId: String,
        similarity: Float,
        region: PixelRect?,
        frame: ScreenFrame,
    ): PixelPoint?
}

/** 找色能力。 */
interface ColorFinder {
    /** 返回命中点（像素），未命中返回 null。 */
    fun find(
        color: Int,
        tolerance: Int,
        region: PixelRect,
        frame: ScreenFrame,
    ): PixelPoint?
}