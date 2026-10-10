package com.autoclicker.engine

import com.autoclicker.domain.model.GroupNode
import com.autoclicker.domain.model.ScriptNode
import com.autoclicker.domain.model.StepNode

/**
 * 编译后的执行指令。指令分三类：
 * - 执行指令（[DoStep] / [DelayIns]）；
 * - 控制指令（[LoopIns] 计数循环、[BranchIns] 成功/失败分支、[JumpIns] 无条件跳转）。
 */
sealed interface Instruction {
    /** 关联步骤 id（控制指令为组 id），用于日志与高亮。 */
    val stepId: String?
}

/** 执行一个步骤的动作一次（repeatCount 由紧随其后的 [LoopIns] 承担）。 */
data class DoStep(override val stepId: String, val step: StepNode) : Instruction

/** 延时。 */
data class DelayIns(override val stepId: String, val ms: Long, val randomMs: Long) : Instruction

/** 计数循环：执行 [count] 次后落空，其余跳回 [target]。 */
data class LoopIns(
    override val stepId: String?,
    val count: Int,
    val target: Int,
) : Instruction

/** 按上一步执行结果（成功/失败）跳转到目标步骤 id。 */
data class BranchIns(
    override val stepId: String,
    val successTargetStepId: String?,
    val failureTargetStepId: String?,
) : Instruction

/** 无条件跳转到目标步骤 id。 */
data class JumpIns(override val stepId: String?, val targetStepId: String) : Instruction

/**
 * 执行计划：由节点树编译得到的线性指令序列，以及「步骤 id → 指令下标」映射。
 * 分支/跳转的目标以步骤 id 表达，运行期通过 [stepIndex] 解析为指令下标。
 */
class PlaybackPlan private constructor(
    val instructions: List<Instruction>,
    val stepIndex: Map<String, Int>,
) {
    companion object {
        fun compile(nodes: List<ScriptNode>): PlaybackPlan {
            val builder = Builder()
            builder.emit(nodes)
            return PlaybackPlan(builder.out, builder.stepIndex)
        }
    }

    private class Builder {
        val out = ArrayList<Instruction>()
        val stepIndex = LinkedHashMap<String, Int>()

        fun emit(nodes: List<ScriptNode>) {
            for (node in nodes) {
                when (node) {
                    is StepNode -> emitStep(node)
                    is GroupNode -> emitGroup(node)
                }
            }
        }

        private fun emitStep(step: StepNode) {
            val start = out.size
            stepIndex[step.id] = start
            out.add(DoStep(step.id, step))
            if (step.delayAfterMs > 0) {
                // 延时要落在「重复执行」内部：连点时每点一次都等一会儿，屏幕才有时间刷新。
                out.add(DelayIns(step.id, step.delayAfterMs, 0L))
            }
            if (step.repeatCount > 1) {
                // 跳回本步骤的 DoStep，重复执行 repeatCount 次（含每次的动作后延时）。
                out.add(LoopIns(step.id, step.repeatCount, start))
            }
            if (step.onSuccessStepId != null || step.onFailureStepId != null) {
                // 分支在重复执行结束之后生效。
                out.add(BranchIns(step.id, step.onSuccessStepId, step.onFailureStepId))
            }
        }

        private fun emitGroup(group: GroupNode) {
            val bodyStart = out.size
            emit(group.children)
            if (out.size > bodyStart && group.loopCount > 1) {
                // 跳回组体首条指令，整体循环 loopCount 次。
                out.add(LoopIns(group.id, group.loopCount, bodyStart))
            }
        }
    }
}