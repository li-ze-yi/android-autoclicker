package com.autoclicker.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 屏幕矩形区域（屏幕绝对坐标，单位 px）。
 *
 * 用于 [Action.WaitImage] 的限定找图区域；约定 [left] <= [right]、[top] <= [bottom]，
 * 该不变量由 `StructureValidator` 校验，模型本身保持轻量不做强制检查。
 */
@Serializable
data class Rect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

/**
 * 可执行的基础动作（对应 FR-6 动作类型）。
 *
 * 全部使用屏幕绝对坐标，纯 Kotlin 定义，不依赖任何 Android API，
 * 由引擎层翻译为无障碍手势指令。
 *
 * 多态说明：作为 sealed interface，kotlinx.serialization 默认以各子类的
 * 简单类名作为多态 discriminator（"type" 字段）的值。
 */
@Serializable
sealed interface Action {

    /** 点按：在 (x, y) 处单击一次 */
    @Serializable
    @SerialName("Tap")
    data class Tap(
        val x: Int,
        val y: Int,
    ) : Action

    /** 长按：在 (x, y) 处长按 [durationMs] 毫秒（对应 FR-4） */
    @Serializable
    @SerialName("LongPress")
    data class LongPress(
        val x: Int,
        val y: Int,
        val durationMs: Long,
    ) : Action

    /** 滑动：从 (x1, y1) 滑到 (x2, y2)，持续 [durationMs] 毫秒（建议 >= 300ms） */
    @Serializable
    @SerialName("Swipe")
    data class Swipe(
        val x1: Int,
        val y1: Int,
        val x2: Int,
        val y2: Int,
        val durationMs: Long,
    ) : Action

    /** 延时：等待 [durationMs] 毫秒，不产生任何手势 */
    @Serializable
    @SerialName("Delay")
    data class Delay(
        val durationMs: Long,
    ) : Action

    /**
     * 等待识图模板出现（对应 FR-7）。
     *
     * @param templateId 识图模板 ID（模板资源由数据层单独管理）
     * @param region 限定找图区域，null 表示全屏
     * @param similarity 相似度阈值，0..1，默认 0.8
     * @param timeoutMs 超时时间（毫秒），超时后走超时分支
     * @param tapWhenFound true=找到后点击匹配点；false=找到后仅继续下一步
     */
    @Serializable
    @SerialName("WaitImage")
    data class WaitImage(
        val templateId: String,
        val region: Rect? = null,
        val similarity: Double = DEFAULT_SIMILARITY,
        val timeoutMs: Int,
        val tapWhenFound: Boolean = true,
    ) : Action

    companion object {
        /** 默认相似度阈值 */
        const val DEFAULT_SIMILARITY: Double = 0.8
    }

    /** 按下 Home 键（手动添加的系统键，录制器入口） */
    @Serializable
    @SerialName("GlobalHome")
    data object GlobalHome : Action

    /** 按下返回键（手动添加的系统键） */
    @Serializable
    @SerialName("GlobalBack")
    data object GlobalBack : Action
}
