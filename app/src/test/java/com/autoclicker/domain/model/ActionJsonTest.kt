package com.autoclicker.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Action] 多态 JSON 序列化测试（TR-2.1）。
 * 纯 JVM/JUnit4，无任何 Android 依赖。
 */
class ActionJsonTest {

    /** 测试用包装类型，使 [Action] 以多态字段形式参与序列化 */
    @Serializable
    private data class Holder(val action: Action)

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    private inline fun <reified T : Action> assertRoundTrip(action: T, expectedTypeValue: String) {
        val text = json.encodeToString(Holder(action))
        assertTrue("JSON 应包含多态 discriminator", text.contains("\"type\":\"$expectedTypeValue\""))
        val decoded = json.decodeFromString<Holder>(text).action
        assertEquals(action, decoded)
    }

    @Test
    fun tap_往返一致() {
        assertRoundTrip(Action.Tap(x = 540, y = 1200), "Tap")
    }

    @Test
    fun longPress_往返一致() {
        assertRoundTrip(Action.LongPress(x = 100, y = 200, durationMs = 2000L), "LongPress")
    }

    @Test
    fun swipe_往返一致() {
        assertRoundTrip(Action.Swipe(x1 = 0, y1 = 1500, x2 = 0, y2 = 600, durationMs = 400L), "Swipe")
    }

    @Test
    fun delay_往返一致() {
        assertRoundTrip(Action.Delay(durationMs = 350L), "Delay")
    }

    @Test
    fun waitImage_默认值完整输出且往返一致() {
        val action = Action.WaitImage(templateId = "tpl-1", timeoutMs = 5000)
        val text = json.encodeToString(Holder(action))
        // encodeDefaults=true：默认相似度、tapWhenFound 必须写入
        assertTrue(text.contains("\"similarity\":0.8"))
        assertTrue(text.contains("\"tapWhenFound\":true"))
        // region 默认 null，显式输出
        assertTrue(text.contains("\"region\":null"))
        assertEquals(action, json.decodeFromString<Holder>(text).action)
    }

    @Test
    fun waitImage_全参数含区域_往返一致() {
        val action = Action.WaitImage(
            templateId = "tpl-2",
            region = Rect(left = 10, top = 20, right = 310, bottom = 420),
            similarity = 0.92,
            timeoutMs = 8000,
            tapWhenFound = false,
        )
        assertRoundTrip(action, "WaitImage")
    }
}
