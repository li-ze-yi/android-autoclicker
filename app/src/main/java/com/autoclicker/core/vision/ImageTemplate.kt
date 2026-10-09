package com.autoclicker.core.vision

import kotlinx.serialization.Serializable

@Serializable
data class ImageTemplate(
    val id: String,
    val name: String,
    val fileName: String,
    val width: Int,
    val height: Int,
    val createdAt: Long = System.currentTimeMillis()
)

/** 匹配结果：命中位置的整屏坐标中心点与相似度（0f~1f）。 */
data class MatchResult(val centerX: Int, val centerY: Int, val similarity: Float)