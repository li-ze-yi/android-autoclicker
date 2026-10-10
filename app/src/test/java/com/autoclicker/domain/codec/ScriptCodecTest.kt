package com.autoclicker.domain.codec

import com.autoclicker.domain.model.Action
import com.autoclicker.domain.model.RepeatPolicy
import com.autoclicker.domain.model.Script
import com.autoclicker.domain.model.ScriptStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [ScriptCodec] 测试：信封格式、往返一致、版本与坏数据异常（TR-2.1 / TR-2.2）。
 */
class ScriptCodecTest {

    private fun sampleScript(): Script = Script(
        id = "script-1",
        name = "测试脚本",
        steps = listOf(
            ScriptStep.BasicStep(id = "s1", action = Action.Tap(x = 1, y = 2)),
            ScriptStep.LoopGroup(
                id = "g1",
                count = 2,
                steps = listOf(ScriptStep.BasicStep(id = "s2", action = Action.Delay(durationMs = 100L))),
            ),
            ScriptStep.PackageCall(id = "c1", packageId = "pkg-1"),
        ),
        repeatPolicy = RepeatPolicy.Count(times = 5),
    )

    @Test
    fun 脚本往返一致且外层带schemaVersion2() {
        val text = ScriptCodec.encode(sampleScript())
        assertTrue(text.contains("\"schemaVersion\":2"))
        assertTrue(text.contains("\"script\":{"))
        assertEquals(sampleScript(), ScriptCodec.decode(text))
    }

    @Test
    fun 默认重复策略为直到停止() {
        val text = ScriptCodec.encode(Script(id = "s", name = "n", steps = emptyList()))
        assertTrue(text.contains("\"type\":\"UntilStopped\""))
        assertEquals(RepeatPolicy.UntilStopped, ScriptCodec.decode(text).repeatPolicy)
    }

    @Test
    fun schemaVersion为1时_抛中文异常() {
        val oldText = ScriptCodec.encode(sampleScript())
            .replace("\"schemaVersion\":2", "\"schemaVersion\":1")
        val ex = expectDataException { ScriptCodec.decode(oldText) }
        assertTrue(ex.message!!.contains("版本"))
    }

    @Test
    fun 损坏的JSON_抛中文异常() {
        val ex = expectDataException { ScriptCodec.decode("{这不是合法JSON") }
        assertTrue(ex.message!!.contains("损坏"))
    }

    @Test
    fun 缺少schemaVersion_抛中文异常() {
        val text = """{"script":{"id":"a","name":"b","steps":[]}}"""
        val ex = expectDataException { ScriptCodec.decode(text) }
        assertTrue(ex.message!!.contains("schemaVersion"))
    }

    @Test
    fun schemaVersion为字符串_抛中文异常() {
        val text = """{"schemaVersion":"2","script":{"id":"a","name":"b","steps":[]}}"""
        expectDataException { ScriptCodec.decode(text) }
    }

    @Test
    fun 信封出现未知字段_严格模式拒绝() {
        val text = """{"schemaVersion":2,"script":{"id":"a","name":"b","steps":[]},"unknown":1}"""
        expectDataException { ScriptCodec.decode(text) }
    }

    @Test
    fun 步骤discriminator非法_抛中文异常() {
        val text = """
            {"schemaVersion":2,"script":{"id":"a","name":"b","steps":[{"type":"NoSuchStep"}]}}
        """.trimIndent()
        val ex = expectDataException { ScriptCodec.decode(text) }
        assertTrue(ex.message!!.contains("损坏"))
    }

    private inline fun expectDataException(block: () -> Unit): ScriptDataException {
        try {
            block()
        } catch (e: ScriptDataException) {
            return e
        }
        fail("应当抛出 ScriptDataException")
        error("unreachable")
    }
}
