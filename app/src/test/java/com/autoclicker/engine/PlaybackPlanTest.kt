package com.autoclicker.engine

import com.autoclicker.domain.model.ClickAction
import com.autoclicker.domain.model.ClickImageAction
import com.autoclicker.domain.model.ClickNodeAction
import com.autoclicker.domain.model.EmptyAction
import com.autoclicker.domain.model.GroupNode
import com.autoclicker.domain.model.JumpAction
import com.autoclicker.domain.model.JumpMode
import com.autoclicker.domain.model.NodeSelector
import com.autoclicker.domain.model.PercentPoint
import com.autoclicker.domain.model.ScriptNode
import com.autoclicker.domain.model.StepNode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPlanTest {

    @Test
    fun groupLoopCompilesToLoopIns() {
        val step = StepNode("s1", EmptyAction())
        val group = GroupNode("g1", "循环组", listOf(step), loopCount = 3)

        val plan = PlaybackPlan.compile(listOf(group))

        val loop = plan.instructions.filterIsInstance<LoopIns>().first { it.stepId == "g1" }
        assertEquals(3, loop.count)
        // 循环目标指向组体首条 DoStep。
        assertTrue(plan.instructions[loop.target] is DoStep)
        assertEquals(0, loop.target)
    }

    @Test
    fun stepRepeatCompilesToLoopIns() {
        val step = StepNode(
            id = "s2",
            action = ClickAction(PercentPoint(0.5f, 0.5f)),
            repeatCount = 4,
        )

        val plan = PlaybackPlan.compile(listOf(step))

        // DoStep + LoopIns，共 2 条指令。
        assertEquals(2, plan.instructions.size)
        val loop = plan.instructions.filterIsInstance<LoopIns>().first()
        assertEquals(4, loop.count)
        assertEquals(plan.stepIndex["s2"]!!, loop.target)
    }

    @Test
    fun jumpBranchResolvesStepIds() {
        val a = StepNode(
            id = "a",
            action = ClickImageAction(templateId = "t"),
            onSuccessStepId = "b",
            onFailureStepId = "c",
        )
        val b = StepNode("b", EmptyAction())
        val c = StepNode("c", EmptyAction())

        val plan = PlaybackPlan.compile(listOf(a, b, c))

        val branch = plan.instructions.filterIsInstance<BranchIns>().first()
        assertEquals("b", branch.successTargetStepId)
        assertEquals("c", branch.failureTargetStepId)
        assertNotNull(plan.stepIndex["b"])
        assertNotNull(plan.stepIndex["c"])
    }

    @Test
    fun interpreterRunsGroupLoopCountTimes() = runTest {
        var executed = 0
        val step = StepNode("s", EmptyAction())
        val group = GroupNode("g", "组", listOf(step), loopCount = 3)
        val plan = PlaybackPlan.compile(listOf(group))
        val interpreter = PlaybackInterpreter(plan)

        interpreter.run(
            ctx = ExecutionContext(),
            gate = noPauseGate(),
            runner = StepRunner { _, _ ->
                executed++
                StepOutcome()
            },
            onStep = { _, _, _ -> },
        )

        assertEquals(3, executed)
    }

    @Test
    fun interpreterPassesStepRepeatCount() = runTest {
        var executed = 0
        val step = StepNode("s", EmptyAction(), repeatCount = 5)
        val plan = PlaybackPlan.compile(listOf(step))
        val interpreter = PlaybackInterpreter(plan)

        interpreter.run(
            ctx = ExecutionContext(),
            gate = noPauseGate(),
            runner = StepRunner { _, _ ->
                executed++
                StepOutcome()
            },
            onStep = { _, _, _ -> },
        )

        assertEquals(5, executed)
    }

    @Test
    fun interpreterFollowsSuccessBranch() = runTest {
        val visited = mutableListOf<String>()
        val a = StepNode("a", ClickNodeAction(NodeSelector(text = "x")), onSuccessStepId = "c")
        val b = StepNode("b", EmptyAction())
        val c = StepNode("c", EmptyAction())
        val plan = PlaybackPlan.compile(listOf(a, b, c))
        val interpreter = PlaybackInterpreter(plan)

        interpreter.run(
            ctx = ExecutionContext(),
            gate = noPauseGate(),
            runner = StepRunner { step, _ ->
                visited += step.id
                if (step.id == "a") StepOutcome(success = true) else StepOutcome()
            },
            onStep = { _, _, _ -> },
        )

        // a 成功后跳转到 c，跳过 b。
        assertEquals(listOf("a", "c"), visited)
    }

    @Test
    fun interpreterFollowsFailureBranch() = runTest {
        val visited = mutableListOf<String>()
        val a = StepNode("a", ClickNodeAction(NodeSelector(text = "x")), onFailureStepId = "c")
        val b = StepNode("b", EmptyAction())
        val c = StepNode("c", EmptyAction())
        val plan = PlaybackPlan.compile(listOf(a, b, c))
        val interpreter = PlaybackInterpreter(plan)

        interpreter.run(
            ctx = ExecutionContext(),
            gate = noPauseGate(),
            runner = StepRunner { step, _ ->
                visited += step.id
                if (step.id == "a") StepOutcome(success = false) else StepOutcome()
            },
            onStep = { _, _, _ -> },
        )

        assertEquals(listOf("a", "c"), visited)
    }

    @Test
    fun interpreterFollowsJumpAction() = runTest {
        val visited = mutableListOf<String>()
        val a = StepNode("a", JumpAction(JumpMode.STEP, "c"))
        val b = StepNode("b", EmptyAction())
        val c = StepNode("c", EmptyAction())
        val plan = PlaybackPlan.compile(listOf(a, b, c))
        val interpreter = PlaybackInterpreter(plan)

        interpreter.run(
            ctx = ExecutionContext(),
            gate = noPauseGate(),
            runner = StepRunner { step, _ ->
                visited += step.id
                if (step.id == "a") StepOutcome(jumpTargetStepId = "c") else StepOutcome()
            },
            onStep = { _, _, _ -> },
        )

        assertEquals(listOf("a", "c"), visited)
    }

    @Test
    fun interpreterStopsOnEndTask() = runTest {
        val visited = mutableListOf<String>()
        val a = StepNode("a", JumpAction(JumpMode.END_TASK))
        val b = StepNode("b", EmptyAction())
        val plan = PlaybackPlan.compile(listOf<ScriptNode>(a, b))
        val interpreter = PlaybackInterpreter(plan)

        interpreter.run(
            ctx = ExecutionContext(),
            gate = noPauseGate(),
            runner = StepRunner { step, _ ->
                visited += step.id
                if (step.id == "a") StepOutcome(endTask = true) else StepOutcome()
            },
            onStep = { _, _, _ -> },
        )

        assertEquals(listOf("a"), visited)
    }

    @Test
    fun executionContextIsolatesFunctionParams() {
        val ctx = ExecutionContext()
        ctx.putGlobal("全局", "G")
        ctx.pushFrame(mapOf("参数" to "P"))
        // 函数内新建变量写入局部，不污染全局。
        ctx.set("局部", "L")

        assertEquals("P", ctx.get("参数"))
        assertEquals("L", ctx.get("局部"))
        assertEquals("G", ctx.get("全局"))

        ctx.setReturn("结果", "R")
        val returns = ctx.popFrame()
        assertEquals("R", returns["结果"])
        // 弹出后局部变量不再可见。
        assertNull(ctx.get("局部"))
        assertEquals("G", ctx.get("全局"))
    }

    private fun noPauseGate(): PauseGate = object : PauseGate {
        override suspend fun awaitResume() = Unit

        override suspend fun delay(ms: Long) {
            kotlinx.coroutines.delay(ms)
        }
    }
}