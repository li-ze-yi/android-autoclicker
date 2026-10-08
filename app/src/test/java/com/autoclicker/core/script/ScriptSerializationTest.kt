package com.autoclicker.core.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptSerializationTest {

    private fun allStepTypes(): List<Step> = listOf(
        Step.Tap(id = "s1", note = "n1", x = 120f, y = 800f),
        Step.LongPress(id = "s2", note = "n2", x = 10f, y = 20f, durationMs = 900L),
        Step.Swipe(id = "s3", note = "n3", x1 = 100f, y1 = 900f, x2 = 100f, y2 = 300f, durationMs = 300L),
        Step.Input(id = "s4", note = "n4", text = "你好"),
        Step.Wait(id = "s5", note = "n5", durationMs = 1000L),
        Step.LaunchApp(id = "s6", note = "n6", packageName = "com.tencent.mm"),
        Step.WaitForElement(
            id = "s7",
            note = "n7",
            text = "登录",
            viewId = "com.app:id/login",
            contentDesc = "登录按钮",
            className = "android.widget.Button",
            timeoutMs = 10000L,
            onTimeout = OnTimeout.SKIP
        ),
        Step.Back(id = "s8", note = "n8"),
        Step.Home(id = "s9", note = "n9")
    )

    @Test
    fun allStepTypesRoundTrip() {
        val original = Script(
            id = "script-1",
            name = "全部类型",
            steps = allStepTypes(),
            stopOnError = false,
            createdAt = 1700000000000L,
            updatedAt = 1700000001000L
        )

        val text = ScriptSerializer.encode(original)
        val decoded = ScriptSerializer.decode(text)

        assertEquals(original, decoded)
    }

    @Test
    fun stepTypeDiscriminator() {
        val script = Script(id = "script-2", name = "判别字段", steps = allStepTypes())
        val text = ScriptSerializer.encode(script)

        assertTrue(text.contains("\"type\": \"tap\""))
        assertTrue(text.contains("\"type\": \"long_press\""))
        assertTrue(text.contains("\"type\": \"swipe\""))
        assertTrue(text.contains("\"type\": \"input\""))
        assertTrue(text.contains("\"type\": \"wait\""))
        assertTrue(text.contains("\"type\": \"launch_app\""))
        assertTrue(text.contains("\"type\": \"wait_element\""))
        assertTrue(text.contains("\"type\": \"back\""))
        assertTrue(text.contains("\"type\": \"home\""))
    }

    @Test
    fun decodeWithUnknownKeys() {
        val text = """
            {
              "id": "script-3",
              "name": "含未知字段",
              "steps": [],
              "stopOnError": true,
              "createdAt": 1,
              "updatedAt": 2,
              "someUnknownField": "ignored",
              "anotherOne": { "nested": 123 }
            }
        """.trimIndent()

        val decoded = ScriptSerializer.decode(text)

        assertEquals("script-3", decoded.id)
        assertEquals("含未知字段", decoded.name)
        assertTrue(decoded.steps.isEmpty())
        assertTrue(decoded.stopOnError)
    }

    @Test
    fun emptySteps() {
        val original = Script(
            id = "script-4",
            name = "空脚本",
            steps = emptyList(),
            stopOnError = true,
            createdAt = 100L,
            updatedAt = 200L
        )

        val decoded = ScriptSerializer.decode(ScriptSerializer.encode(original))

        assertEquals(original, decoded)
        assertTrue(decoded.steps.isEmpty())
    }

    @Test
    fun describeDoesNotCrash() {
        for (step in allStepTypes()) {
            assertFalse(step.typeLabel.isEmpty())
            assertFalse(step.describe().isEmpty())
        }
    }
}