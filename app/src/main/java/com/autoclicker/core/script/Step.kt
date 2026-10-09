package com.autoclicker.core.script

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Locale
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()

enum class OnTimeout { STOP, SKIP }

@Serializable
sealed class Step {
    abstract val id: String
    abstract val note: String

    @Serializable @SerialName("tap")
    data class Tap(override val id: String = newId(), override val note: String = "", val x: Float, val y: Float) : Step()

    @Serializable @SerialName("long_press")
    data class LongPress(override val id: String = newId(), override val note: String = "", val x: Float, val y: Float, val durationMs: Long = 800L) : Step()

    @Serializable @SerialName("swipe")
    data class Swipe(override val id: String = newId(), override val note: String = "", val x1: Float, val y1: Float, val x2: Float, val y2: Float, val durationMs: Long = 300L) : Step()

    @Serializable @SerialName("input")
    data class Input(override val id: String = newId(), override val note: String = "", val text: String) : Step()

    @Serializable @SerialName("wait")
    data class Wait(override val id: String = newId(), override val note: String = "", val durationMs: Long = 1000L) : Step()

    @Serializable @SerialName("launch_app")
    data class LaunchApp(override val id: String = newId(), override val note: String = "", val packageName: String) : Step()

    @Serializable @SerialName("wait_element")
    data class WaitForElement(
        override val id: String = newId(),
        override val note: String = "",
        val text: String? = null,
        val viewId: String? = null,
        val contentDesc: String? = null,
        val className: String? = null,
        val timeoutMs: Long = 10000L,
        val onTimeout: OnTimeout = OnTimeout.STOP
    ) : Step()

    @Serializable @SerialName("back")
    data class Back(override val id: String = newId(), override val note: String = "") : Step()

    @Serializable @SerialName("home")
    data class Home(override val id: String = newId(), override val note: String = "") : Step()

    @Serializable @SerialName("burst")
    data class Burst(
        override val id: String = newId(),
        override val note: String = "",
        val x: Float,
        val y: Float,
        val count: Int = 10,
        val intervalMs: Long = 100L,
        val touchDurationMs: Long = 50L
    ) : Step()

    @Serializable @SerialName("tap_element")
    data class TapElement(
        override val id: String = newId(),
        override val note: String = "",
        val text: String? = null,
        val viewId: String? = null,
        val contentDesc: String? = null,
        val className: String? = null,
        val index: Int = 0,
        val timeoutMs: Long = 10000L,
        val onTimeout: OnTimeout = OnTimeout.STOP
    ) : Step()

    @Serializable @SerialName("image_tap")
    data class ImageTap(
        override val id: String = newId(),
        override val note: String = "",
        val templateId: String,
        val thresholdPercent: Int = 85,
        val regionLeft: Int = 0,
        val regionTop: Int = 0,
        val regionWidth: Int = 0,
        val regionHeight: Int = 0,
        val offsetX: Int = 0,
        val offsetY: Int = 0,
        val timeoutMs: Long = 10000L,
        val onTimeout: OnTimeout = OnTimeout.STOP
    ) : Step()

    @Serializable @SerialName("color_tap")
    data class ColorTap(
        override val id: String = newId(),
        override val note: String = "",
        val color: Int,
        val tolerance: Int = 20,
        val regionLeft: Int = 0,
        val regionTop: Int = 0,
        val regionWidth: Int = 0,
        val regionHeight: Int = 0,
        val offsetX: Int = 0,
        val offsetY: Int = 0,
        val timeoutMs: Long = 10000L,
        val onTimeout: OnTimeout = OnTimeout.STOP
    ) : Step()
}

/** 坐标/数值展示：整数去掉多余的小数点后 0。 */
private fun Float.fmt(): String =
    if (this == toLong().toFloat()) toLong().toString() else toString()

/** UI 展示用的中文类型名。 */
val Step.typeLabel: String
    get() = when (this) {
        is Step.Tap -> "点击"
        is Step.LongPress -> "长按"
        is Step.Swipe -> "滑动"
        is Step.Input -> "输入文本"
        is Step.Wait -> "等待"
        is Step.LaunchApp -> "启动应用"
        is Step.WaitForElement -> "等待元素"
        is Step.Back -> "返回键"
        is Step.Home -> "主页键"
        is Step.Burst -> "连点"
        is Step.TapElement -> "智能定位点击"
        is Step.ImageTap -> "识图点击"
        is Step.ColorTap -> "识色点击"
    }

/**
 * UI 展示用的一行可读摘要。
 *
 * 区域语义（[Step.ImageTap] / [Step.ColorTap]）：当 regionWidth <= 0 或 regionHeight <= 0 时表示"全屏"，
 * 否则表示以 (regionLeft, regionTop) 为左上角、宽 regionWidth、高 regionHeight 的矩形区域。
 */
fun Step.describe(): String = when (this) {
    is Step.Tap -> "点击 (${x.fmt()}, ${y.fmt()})"
    is Step.LongPress -> "长按 (${x.fmt()}, ${y.fmt()}) ${durationMs}ms"
    is Step.Swipe -> "滑动 (${x1.fmt()},${y1.fmt()}) → (${x2.fmt()},${y2.fmt()}) ${durationMs}ms"
    is Step.Input -> "输入 \"$text\""
    is Step.Wait -> "等待 ${durationMs}ms"
    is Step.LaunchApp -> "启动应用 $packageName"
    is Step.WaitForElement -> "等待元素 ${selectorSummary()} 超时${timeoutMs}ms"
    is Step.Back -> "返回键"
    is Step.Home -> "主页键"
    is Step.Burst -> "连点 (${x.fmt()}, ${y.fmt()}) ×$count 间隔${intervalMs}ms"
    is Step.TapElement -> "智能定位 ${selectorSummary()} 超时${timeoutMs}ms"
    is Step.ImageTap -> "识图 模板=$templateId 阈值${thresholdPercent}% 超时${timeoutMs}ms"
    is Step.ColorTap -> "识色 ${formatColor(color)} 容差$tolerance 超时${timeoutMs}ms"
}

private fun formatColor(color: Int): String = "#%06X".format(Locale.US, color and 0xFFFFFF)

/** 生成新 id 的副本（保留其余字段），供复制步骤使用。 */
fun Step.withNewId(): Step = when (this) {
    is Step.Tap -> copy(id = newId())
    is Step.LongPress -> copy(id = newId())
    is Step.Swipe -> copy(id = newId())
    is Step.Input -> copy(id = newId())
    is Step.Wait -> copy(id = newId())
    is Step.LaunchApp -> copy(id = newId())
    is Step.WaitForElement -> copy(id = newId())
    is Step.Back -> copy(id = newId())
    is Step.Home -> copy(id = newId())
    is Step.Burst -> copy(id = newId())
    is Step.TapElement -> copy(id = newId())
    is Step.ImageTap -> copy(id = newId())
    is Step.ColorTap -> copy(id = newId())
}

private fun Step.WaitForElement.selectorSummary(): String {
    val parts = buildList {
        text?.let { add("text=$it") }
        viewId?.let { add("viewId=$it") }
        contentDesc?.let { add("contentDesc=$it") }
        className?.let { add("className=$it") }
    }
    return if (parts.isEmpty()) "无条件" else parts.joinToString(", ")
}

private fun Step.TapElement.selectorSummary(): String {
    val parts = buildList {
        text?.let { add("text=$it") }
        viewId?.let { add("viewId=$it") }
        contentDesc?.let { add("contentDesc=$it") }
        className?.let { add("className=$it") }
    }
    return if (parts.isEmpty()) "无条件" else parts.joinToString(", ")
}