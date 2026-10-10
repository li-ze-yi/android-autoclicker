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
    val createdAt: Long = System.currentTimeMillis(),
)