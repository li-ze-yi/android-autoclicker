package com.autoclicker.core.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptSerializationTest {

    private fun allStepTypes(): List<Step> = listOf(
        Step.Tap(id = "s1", note = "n1", delayBeforeMs = 350L, x = 120f, y = 800f),
        Step.Tap(
            id = "s1b",
            note = "带包围盒",
            x = 120f,
            y = 800f,
            boundsLeft = 100,
            boundsTop = 780,
            boundsRight = 140,
            boundsBottom = 820,
            relX = 0.5f,
            relY = 0.5f
        ),
        Step.LongPress(id = "s2", note = "n2", x = 10f, y = 20f, durationMs = 900L),
        Step.Swipe(id = "s3", note = "n3", delayBeforeMs = 1200L, x1 = 100f, y1 = 900f, x2 = 100f, y2 = 300f, durationMs = 300L),
        Step.Swipe(
            id = "s3b",
            note = "带轨迹",
            x1 = 100f,
            y1 = 900f,
            x2 = 200f,
            y2 = 300f,
            durationMs = 400L,
            path = listOf(
                GesturePoint(100f, 900f, 0L),
                GesturePoint(150f, 600f, 200L),
                GesturePoint(200f, 300f, 400L)
            )
        ),
        Step.MultiGesture(
            id = "s3c",
            note = "多指",
            strokes = listOf(
                listOf(GesturePoint(10f, 10f, 0L), GesturePoint(20f, 20f, 100L)),
                listOf(GesturePoint(100f, 100f, 0L), GesturePoint(80f, 80f, 100L))
            )
        ),
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
        Step.Home(id = "s9", note = "n9"),
        Step.Burst(
            id = "s10",
            note = "n10",
            x = 120f,
            y = 800f,
            count = 10,
            intervalMs = 100L,
            touchDurationMs = 50L
        ),
        Step.TapElement(
            id = "s11",
            note = "n11",
            text = "登录",
            viewId = "com.app:id/login",
            contentDesc = "登录按钮",
            className = "android.widget.Button",
            index = 1,
            timeoutMs = 10000L,
            onTimeout = OnTimeout.SKIP
        ),
        Step.ImageTap(
            id = "s12",
            note = "n12",
            templateId = "abc123",
            thresholdPercent = 90,
            regionLeft = 0,
            regionTop = 0,
            regionWidth = 0,
            regionHeight = 0,
            offsetX = 5,
            offsetY = 5,
            timeoutMs = 8000L,
            onTimeout = OnTimeout.STOP
        ),
        Step.ColorTap(
            id = "s13",
            note = "n13",
            delayBeforeMs = 80L,
            color = 0xFFFF0000.toInt(),
            tolerance = 25,
            regionLeft = 10,
            regionTop = 20,
            regionWidth = 100,
            regionHeight = 50,
            offsetX = 0,
            offsetY = 0,
            timeoutMs = 5000L,
            onTimeout = OnTimeout.SKIP
        ),
        Step.SetVar(id = "s14", note = "n14", name = "v1", value = "3"),
        Step.Label(id = "s15", note = "n15", name = "loop1"),
        Step.Jump(id = "s16", note = "n16", label = "loop1", maxTimes = 2),
        Step.IfElse(
            id = "s17",
            note = "n17",
            condition = Condition.ElementExists(
                text = "登录",
                viewId = "com.app:id/login",
                timeoutMs = 1500L
            ),
            thenLabel = "loop1",
            elseLabel = null
        ),
        Step.IfElse(
            id = "s18",
            note = "n18",
            condition = Condition.ColorFound(color = 0xFF00FF00.toInt(), tolerance = 12),
            thenLabel = null,
            elseLabel = "end"
        ),
        Step.IfElse(
            id = "s19",
            note = "n19",
            condition = Condition.VarCompare(name = "v1", op = CompareOp.GT, value = "2"),
            thenLabel = "loop1",
            elseLabel = "end"
        )
    )

    @Test
    fun allStepTypesRoundTrip() {
        val original = Script(
            id = "script-1",
            name = "全部类型",
            steps = allStepTypes(),
            stopOnError = false,
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
        assertTrue(text.contains("\"type\": \"burst\""))
        assertTrue(text.contains("\"type\": \"tap_element\""))
        assertTrue(text.contains("\"type\": \"image_tap\""))
        assertTrue(text.contains("\"type\": \"color_tap\""))
        assertTrue(text.contains("\"type\": \"multi_gesture\""))
        assertTrue(text.contains("\"type\": \"set_var\""))
        assertTrue(text.contains("\"type\": \"label\""))
        assertTrue(text.contains("\"type\": \"jump\""))
        assertTrue(text.contains("\"type\": \"if\""))
        assertTrue(text.contains("\"type\": \"element\""))
        assertTrue(text.contains("\"type\": \"color\""))
        assertTrue(text.contains("\"type\": \"var_compare\""))
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

    @Test
    fun testNewScriptFieldsRoundTrip() {
        val original = Script(
            id = "script-5",
            name = "新字段",
            steps = allStepTypes(),
            stopOnError = true,
            loopCount = 3,
            loopInfinite = false,
            loopIntervalMs = 500L,
            jitterRadiusPx = 6,
            jitterDelayPercent = 15,
            updatedAt = 1700000001000L
        )

        val decoded = ScriptSerializer.decode(ScriptSerializer.encode(original))

        assertEquals(original, decoded)
        assertEquals(3, decoded.loopCount)
        assertFalse(decoded.loopInfinite)
        assertEquals(500L, decoded.loopIntervalMs)
        assertEquals(6, decoded.jitterRadiusPx)
        assertEquals(15, decoded.jitterDelayPercent)
    }

    @Test
    fun testLegacyJsonWithoutNewFields() {
        val text = """
            {
              "id": "script-6",
              "name": "旧格式",
              "steps": [],
              "stopOnError": true,
              "createdAt": 1,
              "updatedAt": 2
            }
        """.trimIndent()

        val decoded = ScriptSerializer.decode(text)

        assertEquals("script-6", decoded.id)
        assertEquals(1, decoded.loopCount)
        assertFalse(decoded.loopInfinite)
        assertEquals(0L, decoded.loopIntervalMs)
        assertEquals(0, decoded.jitterRadiusPx)
        assertEquals(0, decoded.jitterDelayPercent)
    }

    @Test
    fun testDelayBeforeMsRoundTrip() {
        val step = Step.Tap(id = "delay-1", note = "延时", delayBeforeMs = 2500L, x = 5f, y = 6f)

        val text = ScriptSerializer.encode(
            Script(id = "script-delay", name = "延时往返", steps = listOf(step))
        )

        assertTrue(text.contains("delayBeforeMs"))

        val decoded = ScriptSerializer.decode(text)
        val decodedStep = decoded.steps.single() as Step.Tap
        assertEquals(2500L, decodedStep.delayBeforeMs)
    }

    @Test
    fun testDelayDefaultsToZero() {
        val text = """
            {
              "id": "script-legacy-step",
              "name": "旧步骤无延时",
              "steps": [
                {
                  "type": "tap",
                  "id": "legacy-1",
                  "note": "旧",
                  "x": 1.0,
                  "y": 2.0
                }
              ],
              "stopOnError": true,
              "createdAt": 1,
              "updatedAt": 2
            }
        """.trimIndent()

        val decoded = ScriptSerializer.decode(text)

        val step = decoded.steps.single() as Step.Tap
        assertEquals(0L, step.delayBeforeMs)
    }
}