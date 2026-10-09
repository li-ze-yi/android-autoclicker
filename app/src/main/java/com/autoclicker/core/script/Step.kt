package com.autoclicker.core.script

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Locale
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()

enum class OnTimeout { STOP, SKIP }

/** 变量比较运算符（P3 条件判断用）。 */
enum class CompareOp { EQ, NE, GT, LT, CONTAINS }

/**
 * 手势轨迹上的一个采样点。
 *
 * [t] 为相对该手势起点的毫秒偏移，回放时用于还原每根手指的起始时间与时长。
 */
@Serializable
data class GesturePoint(val x: Float, val y: Float, val t: Long = 0L)

/**
 * 条件判断（P3），用于 [Step.IfElse]。
 * 全部字段可空/带默认值，缺省时不参与过滤。
 */
@Serializable
sealed class Condition {

    @Serializable
    @SerialName("element")
    data class ElementExists(
        val text: String? = null,
        val viewId: String? = null,
        val contentDesc: String? = null,
        val className: String? = null,
        val index: Int = 0,
        val timeoutMs: Long = 1000L
    ) : Condition()

    @Serializable
    @SerialName("color")
    data class ColorFound(
        val color: Int,
        val tolerance: Int = 20,
        val regionLeft: Int = 0,
        val regionTop: Int = 0,
        val regionWidth: Int = 0,
        val regionHeight: Int = 0
    ) : Condition()

    @Serializable
    @SerialName("var_compare")
    data class VarCompare(
        val name: String,
        val op: CompareOp = CompareOp.EQ,
        val value: String = ""
    ) : Condition()
}

@Serializable
sealed class Step {
    abstract val id: String
    abstract val note: String
    abstract val delayBeforeMs: Long

    @Serializable
    @SerialName("tap")
    data class Tap(
        override val id: String = newId(),
        override val note: String = "",
        override val delayBeforeMs: Long = 0L,
        val x: Float,
        val y: Float,
        // P1：录制时附带的控件包围盒与真实触点相对比例（0..1）。回放时优先按 bounds 重新定位控件。
        val boundsLeft: Int? = null,
        val boundsTop: Int? = null,
        val boundsRight: Int? = null,
        val boundsBottom: Int? = null,
        val relX: Float? = null,
        val relY: Float? = null
    ) : Step()

    @Serializable
    @SerialName("long_press")
    data class LongPress(
        override val id: String = newId(),
        override val note: String = "",
        override val delayBeforeMs: Long = 0L,
        val x: Float,
        val y: Float,
        val durationMs: Long = 800L,
        val boundsLeft: Int? = null,
        val boundsTop: Int? = null,
        val boundsRight: Int? = null,
        val boundsBottom: Int? = null,
        val relX: Float? = null,
        val relY: Float? = null
    ) : Step()

    @Serializable
    @SerialName("swipe")
    data class Swipe(
        override val id: String = newId(),
        override val note: String = "",
        override val delayBeforeMs: Long = 0L,
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val durationMs: Long = 300L,
        // P0：完整轨迹点序列；null 表示使用 (x1,y1)->(x2,y2) 直线（旧脚本或手写步骤）。
        val path: List<GesturePoint>? = null
    ) : Step()

    /**
     * 多指手势（P0）：strokes 的每一项是一根手指的轨迹点序列。
     */
    @Serializable
    @SerialName("multi_gesture")
    data class MultiGesture(
        override val id: String = newId(),
        override val note: String = "",
        override val delayBeforeMs: Long = 0L,
        val strokes: List<List<GesturePoint>> = emptyList()
    ) : Step()

    @Serializable
    @SerialName("input")
    data class Input(override val id: String = newId(), override val note: String = "", override val delayBeforeMs: Long = 0L, val text: String) : Step()

    @Serializable
    @SerialName("wait")
    data class Wait(override val id: String = newId(), override val note: String = "", override val delayBeforeMs: Long = 0L, val durationMs: Long = 1000L) : Step()

    @Serializable
    @SerialName("launch_app")
    data class LaunchApp(override val id: String = newId(), override val note: String = "", override val delayBeforeMs: Long = 0L, val packageName: String) : Step()

    @Serializable
    @SerialName("wait_element")
    data class WaitForElement(
        override val id: String = newId(),
        override val note: String = "",
        override val delayBeforeMs: Long = 0L,
        val text: String? = null,
        val viewId: String? = null,
        val contentDesc: String? = null,
        val className: String? = null,
        val timeoutMs: Long = 10000L,
        val onTimeout: OnTimeout = OnTimeout.STOP
    ) : Step()

    @Serializable
    @SerialName("back")
    data class Back(override val id: String = newId(), override val note: String = "", override val delayBeforeMs: Long = 0L) : Step()

    @Serializable
    @SerialName("home")
    data class Home(override val id: String = newId(), override val note: String = "", override val delayBeforeMs: Long = 0L) : Step()

    @Serializable
    @SerialName("burst")
    data class Burst(
        override val id: String = newId(),
        override val note: String = "",
        override val delayBeforeMs: Long = 0L,
        val x: Float,
        val y: Float,
        val count: Int = 10,
        val intervalMs: Long = 100L,
        val touchDurationMs: Long = 50L
    ) : Step()

    @Serializable
    @SerialName("tap_element")
    data class TapElement(
        override val id: String = newId(),
        override val note: String = "",
        override val delayBeforeMs: Long = 0L,
        val text: String? = null,
        val viewId: String? = null,
        val contentDesc: String? = null,
        val className: String? = null,
        val index: Int = 0,
        val timeoutMs: Long = 10000L,
        val onTimeout: OnTimeout = OnTimeout.STOP
    ) : Step()

    @Serializable
    @SerialName("image_tap")
    data class ImageTap(
        override val id: String = newId(),
        override val note: String = "",
        override val delayBeforeMs: Long = 0L,
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

    @Serializable
    @SerialName("color_tap")
    data class ColorTap(
        override val id: String = newId(),
        override val note: String = "",
        override val delayBeforeMs: Long = 0L,
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

    // ---- P3：控制流与变量 ----

    @Serializable
    @SerialName("set_var")
    data class SetVar(
        override val id: String = newId(),
        override val note: String = "",
        override val delayBeforeMs: Long = 0L,
        val name: String,
        val value: String
    ) : Step()

    @Serializable
    @SerialName("label")
    data class Label(
        override val id: String = newId(),
        override val note: String = "",
        override val delayBeforeMs: Long = 0L,
        val name: String
    ) : Step()

    @Serializable
    @SerialName("jump")
    data class Jump(
        override val id: String = newId(),
        override val note: String = "",
        override val delayBeforeMs: Long = 0L,
        val label: String,
        /** 最多跳转次数；-1 表示不限（需自行保证能退出）。 */
        val maxTimes: Int = -1
    ) : Step()

    @Serializable
    @SerialName("if")
    data class IfElse(
        override val id: String = newId(),
        override val note: String = "",
        override val delayBeforeMs: Long = 0L,
        val condition: Condition = Condition.ElementExists(),
        /** 条件成立时跳转到该标签；null 表示继续下一步。 */
        val thenLabel: String? = null,
        /** 条件不成立时跳转到该标签；null 表示继续下一步。 */
        val elseLabel: String? = null
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
        is Step.MultiGesture -> "多指手势"
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
        is Step.SetVar -> "设置变量"
        is Step.Label -> "标签"
        is Step.Jump -> "跳转"
        is Step.IfElse -> "条件判断"
    }

/**
 * UI 展示用的一行可读摘要。
 *
 * 区域语义（[Step.ImageTap] / [Step.ColorTap]）：当 regionWidth <= 0 或 regionHeight <= 0 时表示"全屏"，
 * 否则表示以 (regionLeft, regionTop) 为左上角、宽 regionWidth、高 regionHeight 的矩形区域。
 */
fun Step.describe(): String = when (this) {
    is Step.Tap -> "点击 (${x.fmt()}, ${y.fmt()})".withBounds(boundsLeft, boundsTop, boundsRight, boundsBottom).withDelay(delayBeforeMs)
    is Step.LongPress -> "长按 (${x.fmt()}, ${y.fmt()}) ${durationMs}ms".withBounds(boundsLeft, boundsTop, boundsRight, boundsBottom).withDelay(delayBeforeMs)
    is Step.Swipe -> {
        val p = path
        val shape = if (p != null && p.size > 1) {
            "轨迹${p.size}点 (${x1.fmt()},${y1.fmt()})→(${x2.fmt()},${y2.fmt()})"
        } else {
            "(${x1.fmt()},${y1.fmt()}) → (${x2.fmt()},${y2.fmt()})"
        }
        "滑动 $shape ${durationMs}ms".withDelay(delayBeforeMs)
    }
    is Step.MultiGesture -> "多指手势 ${strokes.size} 指 / ${strokes.sumOf { it.size }} 点".withDelay(delayBeforeMs)
    is Step.Input -> "输入 \"$text\"".withDelay(delayBeforeMs)
    is Step.Wait -> "等待 ${durationMs}ms".withDelay(delayBeforeMs)
    is Step.LaunchApp -> "启动应用 $packageName".withDelay(delayBeforeMs)
    is Step.WaitForElement -> "等待元素 ${selectorSummary()} 超时${timeoutMs}ms".withDelay(delayBeforeMs)
    is Step.Back -> "返回键".withDelay(delayBeforeMs)
    is Step.Home -> "主页键".withDelay(delayBeforeMs)
    is Step.Burst -> "连点 (${x.fmt()}, ${y.fmt()}) ×$count 间隔${intervalMs}ms".withDelay(delayBeforeMs)
    is Step.TapElement -> "智能定位 ${selectorSummary()} 超时${timeoutMs}ms".withDelay(delayBeforeMs)
    is Step.ImageTap -> "识图 模板=$templateId 阈值${thresholdPercent}% 超时${timeoutMs}ms".withDelay(delayBeforeMs)
    is Step.ColorTap -> "识色 ${formatColor(color)} 容差$tolerance 超时${timeoutMs}ms".withDelay(delayBeforeMs)
    is Step.SetVar -> "设置变量 $name = $value".withDelay(delayBeforeMs)
    is Step.Label -> "标签 $name".withDelay(delayBeforeMs)
    is Step.Jump -> (
        "跳转到 $label" + if (maxTimes >= 0) "（最多${maxTimes}次）" else ""
        ).withDelay(delayBeforeMs)
    is Step.IfElse -> (
        "条件判断 ${condition.describe()}" +
            (thenLabel?.let { " → $it" } ?: "") +
            (elseLabel?.let { " 否则→$it" } ?: "")
        ).withDelay(delayBeforeMs)
}

/** 摘要统一追加本步执行前的延时信息；delayMs <= 0 时保持原样。 */
private fun String.withDelay(delayMs: Long): String =
    if (delayMs > 0L) "$this 延时${delayMs}ms" else this

/** 摘要追加控件包围盒信息（仅录制产出的绝对定位步骤会有）。 */
private fun String.withBounds(left: Int?, top: Int?, right: Int?, bottom: Int?): String =
    if (left != null && top != null && right != null && bottom != null) {
        "$this [控件 ${right - left}×${bottom - top}]"
    } else {
        this
    }

/** 条件判断的可读摘要（UI 展示用）。 */
fun Condition.describe(): String = when (this) {
    is Condition.ElementExists -> {
        val parts = buildList {
            text?.let { add("text=$it") }
            viewId?.let { add("viewId=$it") }
            contentDesc?.let { add("contentDesc=$it") }
            className?.let { add("className=$it") }
        }
        "元素(" + (if (parts.isEmpty()) "无条件" else parts.joinToString(", ")) + ")"
    }
    is Condition.ColorFound -> "颜色(" + formatColor(color) + ")"
    is Condition.VarCompare -> "变量($name $op \"$value\")"
}

private fun formatColor(color: Int): String = "#%06X".format(Locale.US, color and 0xFFFFFF)

/** 生成新 id 的副本（保留其余字段），供复制步骤使用。 */
fun Step.withNewId(): Step = when (this) {
    is Step.Tap -> copy(id = newId())
    is Step.LongPress -> copy(id = newId())
    is Step.Swipe -> copy(id = newId())
    is Step.MultiGesture -> copy(id = newId())
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
    is Step.SetVar -> copy(id = newId())
    is Step.Label -> copy(id = newId())
    is Step.Jump -> copy(id = newId())
    is Step.IfElse -> copy(id = newId())
}

/** 返回仅替换执行前延时的副本（P2 批量设置延时用）。 */
fun Step.withDelay(delayMs: Long): Step = when (this) {
    is Step.Tap -> copy(delayBeforeMs = delayMs)
    is Step.LongPress -> copy(delayBeforeMs = delayMs)
    is Step.Swipe -> copy(delayBeforeMs = delayMs)
    is Step.MultiGesture -> copy(delayBeforeMs = delayMs)
    is Step.Input -> copy(delayBeforeMs = delayMs)
    is Step.Wait -> copy(delayBeforeMs = delayMs)
    is Step.LaunchApp -> copy(delayBeforeMs = delayMs)
    is Step.WaitForElement -> copy(delayBeforeMs = delayMs)
    is Step.Back -> copy(delayBeforeMs = delayMs)
    is Step.Home -> copy(delayBeforeMs = delayMs)
    is Step.Burst -> copy(delayBeforeMs = delayMs)
    is Step.TapElement -> copy(delayBeforeMs = delayMs)
    is Step.ImageTap -> copy(delayBeforeMs = delayMs)
    is Step.ColorTap -> copy(delayBeforeMs = delayMs)
    is Step.SetVar -> copy(delayBeforeMs = delayMs)
    is Step.Label -> copy(delayBeforeMs = delayMs)
    is Step.Jump -> copy(delayBeforeMs = delayMs)
    is Step.IfElse -> copy(delayBeforeMs = delayMs)
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