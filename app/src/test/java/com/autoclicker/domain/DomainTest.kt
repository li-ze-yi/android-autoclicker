package com.autoclicker.domain

import com.autoclicker.domain.codec.FunctionPackageCodec
import com.autoclicker.domain.codec.ScriptCodec
import com.autoclicker.domain.model.ClickAction
import com.autoclicker.domain.model.GroupNode
import com.autoclicker.domain.model.Ids
import com.autoclicker.domain.model.PercentPoint
import com.autoclicker.domain.model.PercentRect
import com.autoclicker.domain.model.Script
import com.autoclicker.domain.model.StepNode
import com.autoclicker.domain.rule.CoordinateMapper
import com.autoclicker.domain.rule.StructureValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainTest {

    @Test
    fun scriptRoundTripPreservesStructure() {
        val inner = StepNode(
            id = Ids.newId(),
            action = ClickAction(PercentPoint(0.5f, 0.25f), durationMs = 80),
            delayAfterMs = 1200,
            repeatCount = 3,
        )
        val group = GroupNode(
            id = Ids.newId(),
            name = "循环组",
            children = listOf(inner),
            loopCount = 5,
        )
        val script = Script(id = Ids.newId(), name = "测试脚本", nodes = listOf(group))

        val decoded = ScriptCodec.decode(ScriptCodec.encode(script))

        assertEquals(script.name, decoded.name)
        assertEquals(1, decoded.actionCount)
        val decodedGroup = decoded.nodes.first() as GroupNode
        assertEquals(5, decodedGroup.loopCount)
        val decodedStep = decodedGroup.children.first() as StepNode
        assertEquals(3, decodedStep.repeatCount)
        assertEquals(1200L, decodedStep.delayAfterMs)
        assertEquals(0.5f, (decodedStep.action as ClickAction).point.x, 0.0001f)
    }

    @Test
    fun packageRoundTripWorks() {
        val pkg = com.autoclicker.domain.model.FunctionPackage(
            id = Ids.newId(),
            name = "打卡函数包",
            params = listOf(com.autoclicker.domain.model.ParamDef("账号")),
            returns = listOf(com.autoclicker.domain.model.ReturnDef("结果")),
            nodes = listOf(StepNode(Ids.newId(), ClickAction(PercentPoint(0.1f, 0.1f)))),
        )
        val decoded = FunctionPackageCodec.decode(FunctionPackageCodec.encode(pkg))
        assertEquals("打卡函数包", decoded.name)
        assertEquals(1, decoded.params.size)
        assertNotNull(decoded.returns.firstOrNull())
    }

    @Test
    fun validatorDetectsBrokenJump() {
        val step = StepNode(
            id = Ids.newId(),
            action = ClickAction(PercentPoint(0.2f, 0.2f)),
            onSuccessStepId = "not-exist",
        )
        val script = Script(id = Ids.newId(), name = "x", nodes = listOf(step))
        val issues = StructureValidator.validateScript(script)
        assertTrue(issues.any { it.message.contains("跳转目标不存在") })
    }

    @Test
    fun coordinateMapperConvertsPercentAndPixel() {
        val mapper = CoordinateMapper(1080, 2316)
        val pixel = mapper.toPixel(PercentPoint(0.5f, 0.5f))
        assertEquals(540, pixel.x)
        assertEquals(1158, pixel.y)

        val rect = mapper.toPixel(PercentRect(0f, 0f, 1f, 1f))
        assertEquals(1080, rect.r)
        assertEquals(2316, rect.b)

        val back = mapper.toPercent(pixel)
        assertEquals(0.5f, back.x, 0.01f)
        assertEquals(0.5f, back.y, 0.01f)
    }
}