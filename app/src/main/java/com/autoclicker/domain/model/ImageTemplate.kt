package com.autoclicker.domain.model

import kotlinx.serialization.Serializable

/** 图像识别模板（库中保存的图片素材）。 */
@Serializable
data class ImageTemplate(
    val id: String,
    val name: String,
    /** 相对于模板目录的文件名，如 "<id>.png"。 */
    val fileName: String,
    val width: Int = 0,
    val height: Int = 0,
    /**
     * 该模板默认的识别区域（屏幕百分比）；null 表示全屏识别。
     * 动作上的 region 若非空则覆盖此值：动作 region > 模板 region > 全屏。
     */
    val region: PercentRect? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * 图像模板组：把同一目标在不同分辨率/主题/版本下的多个外观收拢成一个实体，可跨动作复用。
 *
 * 识别时按 [memberIds] 的顺序逐个匹配，**任一命中即算成功并立即停止**（不跑完整组）。
 * 每个成员沿用各自的识别区域：动作 region 覆盖全体成员 > 成员模板自身 region > 全屏，
 * 与单模板识别语义一致。
 */
@Serializable
data class ImageTemplateGroup(
    val id: String,
    val name: String,
    /** 成员模板 id（按识别优先级排序）。 */
    val memberIds: List<String> = emptyList(),
)