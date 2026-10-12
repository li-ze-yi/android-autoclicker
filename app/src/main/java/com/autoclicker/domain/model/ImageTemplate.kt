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