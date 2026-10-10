package com.autoclicker.domain.model

import kotlinx.serialization.Serializable

/**
 * 屏幕百分比坐标（0f..1f）。持久化一律使用百分比，运行期再按当前屏幕换算为像素，
 * 从而天然跨分辨率适配。
 */
@Serializable
data class PercentPoint(
    val x: Float,
    val y: Float,
)

/** 屏幕百分比矩形区域。l/t/r/b 均为 0f..1f。 */
@Serializable
data class PercentRect(
    val l: Float,
    val t: Float,
    val r: Float,
    val b: Float,
) {
    val width: Float get() = (r - l).coerceAtLeast(0f)
    val height: Float get() = (b - t).coerceAtLeast(0f)

    companion object {
        val FULL = PercentRect(0f, 0f, 1f, 1f)
    }
}

/** 像素坐标（运行期使用）。 */
data class PixelPoint(val x: Int, val y: Int)

/** 像素矩形（运行期使用）。 */
data class PixelRect(val l: Int, val t: Int, val r: Int, val b: Int) {
    val width: Int get() = (r - l).coerceAtLeast(0)
    val height: Int get() = (b - t).coerceAtLeast(0)
    val centerX: Int get() = l + width / 2
    val centerY: Int get() = t + height / 2
}

/** 记录脚本推荐分辨率，用于跨设备提示与坐标校准。 */
@Serializable
data class ScreenProfile(
    val width: Int,
    val height: Int,
    val density: Float,
)