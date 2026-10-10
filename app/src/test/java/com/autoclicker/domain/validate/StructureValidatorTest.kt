package com.autoclicker.domain.validate

import com.autoclicker.domain.model.Action
import com.autoclicker.domain.model.FunctionPackage
import com.autoclicker.domain.model.Rect
import com.autoclicker.domain.model.RepeatPolicy
import com.autoclicker.domain.model.Script
import com.autoclicker.domain.model.ScriptStep
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [StructureValidator] 测试（TR-2.3 / TR-2.4，对应 AC-16）：
 * 调用链深度、环、自调用、缺失包，以及基础不变量。
 */
class StructureValidatorTest {

    // ---------------- 调用图构造辅助 ----------------

    /** 构造一个只含函数包调用步骤的函数包 */
    private fun packageWithCalls(id: String, vararg calls: String): FunctionPackage =
        FunctionPackage(
            id = id,
            name = "包$id",
            steps = calls.map { ScriptStep.PackageCall(id = "step-$id-to-$it", packageId = it) },
        )

    private fun StructureValidator.Result.invalidReasons(): List<String> =
        (this as StructureValidator.Result.Invalid).reasons

    private fun assertAnyReasonContains(result: StructureValidator.Result, keyword: String) {
        val reasons = result.invalidReasons()
        assertTrue("期望存在包含「$keyword」的原因，实际：$reasons", reasons.any { it.contains(keyword) })
    }

    // ---------------- TR-2.3：深度 / 环 / 合法链 ----------------

    @Test
    fun 深度4层链_A到B到C到D_被拒绝() {
        val packages = mapOf(
            "A" to packageWithCalls("A", "B"),
            "B" to packageWithCalls("B", "C"),
            "C" to packageWithCalls("C", "D"),
            "D" to packageWithCalls("D"),
        )
        val result = StructureValidator.validatePackageCalls(packages)
        assertTrue(result is StructureValidator.Result.Invalid)
        assertAnyReasonContains(result, "深度")
    }

    @Test
    fun 环_A到B到A_被拒绝() {
        val packages = mapOf(
            "A" to packageWithCalls("A", "B"),
            "B" to packageWithCalls("B", "A"),
        )
        val result = StructureValidator.validatePackageCalls(packages)
        assertAnyReasonContains(result, "环")
    }

    @Test
    fun 自调用_A到A_被拒绝() {
        val packages = mapOf("A" to packageWithCalls("A", "A"))
        val result = StructureValidator.validatePackageCalls(packages)
        assertAnyReasonContains(result, "自身")
    }

    @Test
    fun 合法3层链_A到B到C_C不调包_通过() {
        val packages = mapOf(
            "A" to packageWithCalls("A", "B"),
            "B" to packageWithCalls("B", "C"),
            "C" to packageWithCalls("C"),
        )
        val result = StructureValidator.validatePackageCalls(packages)
        assertTrue("3 层链应合法，实际：$result", result is StructureValidator.Result.Valid)
    }

    @Test
    fun 调用不存在的函数包_被拒绝并指明缺失() {
        val packages = mapOf("A" to packageWithCalls("A", "X"))
        val result = StructureValidator.validatePackageCalls(packages)
        assertAnyReasonContains(result, "不存在")
    }

    @Test
    fun 两条独立链各自合法_通过() {
        val packages = mapOf(
            "A" to packageWithCalls("A", "B"),
            "B" to packageWithCalls("B"),
            "C" to packageWithCalls("C", "D"),
            "D" to packageWithCalls("D"),
        )
        assertTrue(StructureValidator.validatePackageCalls(packages) is StructureValidator.Result.Valid)
    }

    // ---------------- 基础不变量 ----------------

    private fun scriptWithSteps(vararg steps: ScriptStep) =
        Script(id = "s1", name = "脚本", steps = steps.toList())

    @Test
    fun 循环段次数为0_被拒绝() {
        val script = scriptWithSteps(ScriptStep.LoopGroup(id = "g1", count = 0))
        assertAnyReasonContains(StructureValidator.validateScript(script), "次数")
    }

    @Test
    fun 相似度超过1_被拒绝() {
        val script = scriptWithSteps(
            ScriptStep.BasicStep(id = "s1", action = Action.WaitImage("t", similarity = 1.2, timeoutMs = 1000))
        )
        assertAnyReasonContains(StructureValidator.validateScript(script), "相似度")
    }

    @Test
    fun 相似度为负数_被拒绝() {
        val script = scriptWithSteps(
            ScriptStep.BasicStep(id = "s1", action = Action.WaitImage("t", similarity = -0.1, timeoutMs = 1000))
        )
        assertAnyReasonContains(StructureValidator.validateScript(script), "相似度")
    }

    @Test
    fun 点击坐标为负_被拒绝() {
        val script = scriptWithSteps(
            ScriptStep.BasicStep(id = "s1", action = Action.Tap(x = -1, y = 0))
        )
        assertAnyReasonContains(StructureValidator.validateScript(script), "负")
    }

    @Test
    fun 延时时长为负_被拒绝() {
        val script = scriptWithSteps(
            ScriptStep.BasicStep(id = "s1", action = Action.Delay(durationMs = -5L))
        )
        assertAnyReasonContains(StructureValidator.validateScript(script), "时长")
    }

    @Test
    fun 滑动时长与长按负时长_被拒绝() {
        val script = scriptWithSteps(
            ScriptStep.BasicStep(id = "s1", action = Action.Swipe(0, 0, 1, 1, durationMs = -1L)),
            ScriptStep.BasicStep(id = "s2", action = Action.LongPress(0, 0, durationMs = -2L)),
        )
        assertAnyReasonContains(StructureValidator.validateScript(script), "时长")
    }

    @Test
    fun 等待超时为负_被拒绝() {
        val script = scriptWithSteps(
            ScriptStep.BasicStep(id = "s1", action = Action.WaitImage("t", timeoutMs = -1))
        )
        assertAnyReasonContains(StructureValidator.validateScript(script), "超时")
    }

    @Test
    fun 矩形坐标为负_被拒绝() {
        val script = scriptWithSteps(
            ScriptStep.BasicStep(
                id = "s1",
                action = Action.WaitImage("t", region = Rect(-1, 0, 10, 10), timeoutMs = 1000),
            )
        )
        assertAnyReasonContains(StructureValidator.validateScript(script), "负")
    }

    @Test
    fun 矩形左右颠倒_被拒绝() {
        val script = scriptWithSteps(
            ScriptStep.BasicStep(
                id = "s1",
                action = Action.WaitImage("t", region = Rect(100, 0, 10, 10), timeoutMs = 1000),
            )
        )
        assertAnyReasonContains(StructureValidator.validateScript(script), "右边界")
    }

    @Test
    fun 步骤id为空_被拒绝() {
        val script = scriptWithSteps(
            ScriptStep.BasicStep(id = "", action = Action.Delay(durationMs = 1L))
        )
        assertAnyReasonContains(StructureValidator.validateScript(script), "步骤 id")
    }

    @Test
    fun 函数包调用packageId为空_被拒绝() {
        val script = scriptWithSteps(ScriptStep.PackageCall(id = "s1", packageId = ""))
        assertAnyReasonContains(StructureValidator.validateScript(script), "packageId")
    }

    @Test
    fun 脚本重复次数为0_被拒绝() {
        val script = Script(
            id = "s1",
            name = "脚本",
            steps = emptyList(),
            repeatPolicy = RepeatPolicy.Count(times = 0),
        )
        assertAnyReasonContains(StructureValidator.validateScript(script), "重复次数")
    }

    @Test
    fun 合法的复杂脚本_通过() {
        val script = Script(
            id = "s1",
            name = "完整脚本",
            steps = listOf(
                ScriptStep.BasicStep(id = "s1", action = Action.Tap(1, 2)),
                ScriptStep.LoopGroup(
                    id = "g1",
                    count = 2,
                    steps = listOf(
                        ScriptStep.BasicStep(
                            id = "s2",
                            action = Action.WaitImage(
                                templateId = "t",
                                region = Rect(0, 0, 100, 100),
                                similarity = 0.9,
                                timeoutMs = 3000,
                                tapWhenFound = false,
                            ),
                        ),
                    ),
                ),
            ),
            repeatPolicy = RepeatPolicy.UntilTime(durationMs = 60_000L),
        )
        assertTrue(StructureValidator.validateScript(script) is StructureValidator.Result.Valid)
    }

    @Test
    fun 函数包基础校验_非法步骤被拒绝() {
        val pkg = FunctionPackage(
            id = "p1",
            name = "包",
            steps = listOf(ScriptStep.LoopGroup(id = "g1", count = -1)),
        )
        assertAnyReasonContains(StructureValidator.validateFunctionPackage(pkg), "次数")
    }

    @Test
    fun 函数包基础校验_合法包通过() {
        val pkg = FunctionPackage(
            id = "p1",
            name = "包",
            steps = listOf(ScriptStep.BasicStep(id = "s1", action = Action.Delay(10L))),
        )
        assertTrue(StructureValidator.validateFunctionPackage(pkg) is StructureValidator.Result.Valid)
    }
}
