package com.autoclicker.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ScriptStep] 步骤树（含嵌套循环段）JSON 序列化测试（TR-2.1）。
 */
class ScriptStepJsonTest {

    /** 以步骤列表作为多态集合触发 ScriptStep 的多态序列化 */
    @Serializable
    private data class TreeHolder(val steps: List<ScriptStep>)

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    /** 构造一棵覆盖全部步骤类型、含双层嵌套循环段的步骤树 */
    private fun sampleTree(): List<ScriptStep> = listOf(
        ScriptStep.BasicStep(id = "step-1", action = Action.Tap(x = 100, y = 200)),
        ScriptStep.LoopGroup(
            id = "loop-1",
            name = "外层循环",
            count = 3,
            steps = listOf(
                ScriptStep.BasicStep(id = "step-2", action = Action.Delay(durationMs = 300L)),
                ScriptStep.LoopGroup(
                    id = "loop-2",
                    count = 2,
                    steps = listOf(
                        ScriptStep.BasicStep(
                            id = "step-3",
                            action = Action.Swipe(x1 = 10, y1 = 20, x2 = 30, y2 = 40, durationMs = 400L),
                        ),
                        ScriptStep.PackageCall(id = "call-1", packageId = "package-x"),
                    ),
                ),
            ),
        ),
        ScriptStep.PackageCall(id = "call-2", packageId = "package-y"),
    )

    @Test
    fun 含嵌套循环段的步骤树_往返一致() {
        val tree = sampleTree()
        val text = json.encodeToString(TreeHolder(tree))
        val decoded = json.decodeFromString<TreeHolder>(text).steps
        assertEquals(tree, decoded)
    }

    @Test
    fun 多态discriminator_在每个节点各自生效且不冲突() {
        val text = json.encodeToString(TreeHolder(sampleTree()))
        // ScriptStep 层与内嵌 Action 层都使用 type 字段，但处于不同 JSON 对象中
        assertTrue(text.contains("\"type\":\"BasicStep\""))
        assertTrue(text.contains("\"type\":\"LoopGroup\""))
        assertTrue(text.contains("\"type\":\"PackageCall\""))
        assertTrue(text.contains("\"type\":\"Tap\""))
        assertTrue(text.contains("\"type\":\"Swipe\""))
    }

    @Test
    fun 循环段默认名称与默认次数_完整输出() {
        val text = json.encodeToString(
            TreeHolder(listOf(ScriptStep.LoopGroup(id = "loop-default")))
        )
        assertTrue(text.contains("\"name\":\"循环段\""))
        assertTrue(text.contains("\"count\":1"))
    }
}
