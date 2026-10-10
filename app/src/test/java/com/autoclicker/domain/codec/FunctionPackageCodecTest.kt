package com.autoclicker.domain.codec

import com.autoclicker.domain.model.Action
import com.autoclicker.domain.model.FunctionPackage
import com.autoclicker.domain.model.ScriptStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FunctionPackageCodec] 测试（TR-2.1 / TR-2.2）。
 */
class FunctionPackageCodecTest {

    private fun samplePackage(): FunctionPackage = FunctionPackage(
        id = "pkg-1",
        name = "日常签到",
        steps = listOf(
            ScriptStep.BasicStep(id = "ps1", action = Action.Tap(x = 50, y = 60)),
            ScriptStep.LoopGroup(
                id = "pg1",
                name = "连点段",
                count = 4,
                steps = listOf(
                    ScriptStep.BasicStep(id = "ps2", action = Action.LongPress(x = 1, y = 2, durationMs = 800L)),
                    ScriptStep.PackageCall(id = "pc1", packageId = "pkg-2"),
                ),
            ),
        ),
    )

    @Test
    fun 函数包往返一致且外层带schemaVersion2() {
        val text = FunctionPackageCodec.encode(samplePackage())
        assertTrue(text.contains("\"schemaVersion\":2"))
        assertTrue(text.contains("\"functionPackage\":{"))
        assertEquals(samplePackage(), FunctionPackageCodec.decode(text))
    }

    @Test
    fun schemaVersion不匹配_抛中文异常() {
        val text = FunctionPackageCodec.encode(samplePackage())
            .replace("\"schemaVersion\":2", "\"schemaVersion\":3")
        try {
            FunctionPackageCodec.decode(text)
        } catch (e: ScriptDataException) {
            assertTrue(e.message!!.contains("版本"))
            return
        }
        throw AssertionError("应当抛出 ScriptDataException")
    }

    @Test
    fun 损坏JSON_抛中文异常() {
        try {
            FunctionPackageCodec.decode("<<<")
        } catch (e: ScriptDataException) {
            assertTrue(e.message!!.contains("损坏"))
            return
        }
        throw AssertionError("应当抛出 ScriptDataException")
    }

    @Test
    fun 顶层为数组_抛中文异常() {
        try {
            FunctionPackageCodec.decode("[]")
        } catch (e: ScriptDataException) {
            assertTrue(e.message!!.contains("顶层"))
            return
        }
        throw AssertionError("应当抛出 ScriptDataException")
    }
}
