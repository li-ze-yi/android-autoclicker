package com.autoclicker.engine

import com.autoclicker.domain.model.StepNode
import kotlin.random.Random

/** 单步执行结果。 */
data class StepOutcome(
    /** true=成功 / false=失败 / null=无结果（非识别/条件类动作）。供 [BranchIns] 使用。 */
    val success: Boolean? = null,
    /** 主动跳转的目标步骤 id（JumpAction.STEP）。 */
    val jumpTargetStepId: String? = null,
    /** 结束整个任务（JumpAction.END_TASK）。 */
    val endTask: Boolean = false,
)

/** 单步执行器：把一个步骤编译期无关地执行掉（由 ActionExecutor 实现）。 */
fun interface StepRunner {
    suspend fun run(step: StepNode, ctx: ExecutionContext): StepOutcome
}

/** 暂停门控：为步骤间隙与延时提供暂停/继续语义。 */
interface PauseGate {
    /** 若已暂停则挂起，直到恢复或协程被取消。 */
    suspend fun awaitResume()

    /** 可暂停的延时。 */
    suspend fun delay(ms: Long)
}

/**
 * 指令解释器：以程序计数器（pc）驱动 [PlaybackPlan] 的指令序列，
 * 支持组循环、步骤重复与成功/失败分支跳转。
 */
class PlaybackInterpreter(private val plan: PlaybackPlan) {

    suspend fun run(
        ctx: ExecutionContext,
        gate: PauseGate,
        runner: StepRunner,
        onStep: (step: StepNode, stepIndex: Int, stepTotal: Int) -> Unit,
    ) {
        val counters = HashMap<Int, Int>()
        val outcomes = HashMap<String, StepOutcome>()
        val total = plan.stepIndex.size
        var pc = 0
        var guard = 0
        while (pc in plan.instructions.indices) {
            if (guard++ > MAX_ITERATIONS) {
                throw IllegalStateException("执行步数超过上限，可能存在死循环")
            }
            gate.awaitResume()
            when (val ins = plan.instructions[pc]) {
                is DoStep -> {
                    onStep(ins.step, (plan.stepIndex[ins.stepId] ?: 0) + 1, total)
                    val outcome = runner.run(ins.step, ctx)
                    outcomes[ins.stepId] = outcome
                    if (outcome.endTask) return
                    pc = outcome.jumpTargetStepId?.let { plan.stepIndex[it] } ?: (pc + 1)
                }

                is DelayIns -> {
                    val extra = if (ins.randomMs > 0) Random.nextLong(0, ins.randomMs + 1) else 0L
                    gate.delay(ins.ms + extra)
                    pc++
                }

                is LoopIns -> {
                    val next = (counters[pc] ?: 0) + 1
                    if (next >= ins.count) {
                        counters[pc] = 0
                        pc++
                    } else {
                        counters[pc] = next
                        pc = ins.target
                    }
                }

                is BranchIns -> {
                    val outcome = outcomes[ins.stepId]
                    val target = when (outcome?.success) {
                        true -> ins.successTargetStepId
                        false -> ins.failureTargetStepId
                        null -> null
                    }
                    pc = target?.let { plan.stepIndex[it] } ?: (pc + 1)
                }

                is JumpIns -> pc = plan.stepIndex[ins.targetStepId] ?: (pc + 1)
            }
        }
    }

    companion object {
        /** 单次运行允许的最大指令数，作为死循环兜底。 */
        const val MAX_ITERATIONS = 5_000_000
    }
}