package com.autoclicker.core.script

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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
    }

/** UI 展示用的一行可读摘要。 */
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