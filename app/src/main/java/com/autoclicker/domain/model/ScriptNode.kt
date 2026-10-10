package com.autoclicker.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 脚本节点：步骤（StepNode）或步骤组（GroupNode）。组可嵌套，用于表达循环。
 */
@Serializable
sealed interface ScriptNode {
    val id: String
}

/**
 * 单个步骤。
 *
 * - [delayAfterMs] 动作执行完后的等待时长；
 * - [repeatCount] 该步骤重复执行次数；
 * - [onSuccessStepId] / [onFailureStepId] 识别或条件类动作的「成功/失败」分别跳转（null 表示继续下一步）。
 */
@Serializable
@SerialName("step")
data class StepNode(
    override val id: String,
    val action: Action,
    val delayAfterMs: Long = 0,
    val repeatCount: Int = 1,
    val note: String = "",
    val onSuccessStepId: String? = null,
    val onFailureStepId: String? = null,
    val enabled: Boolean = true,
) : ScriptNode

/**
 * 步骤组：一组连续步骤，可整体循环 [loopCount] 次。用于把「一段流程」封装为可复用单元。
 */
@Serializable
@SerialName("group")
data class GroupNode(
    override val id: String,
    val name: String = "",
    val children: List<ScriptNode> = emptyList(),
    val loopCount: Int = 1,
) : ScriptNode

/** 在节点树中按 id 查找步骤。 */
fun List<ScriptNode>.findStepById(id: String): StepNode? {
    for (node in this) {
        when (node) {
            is StepNode -> if (node.id == id) return node
            is GroupNode -> node.children.findStepById(id)?.let { return it }
        }
    }
    return null
}

/** 扁平化所有步骤（按执行顺序，忽略组的循环次数）。 */
fun List<ScriptNode>.flattenSteps(): List<StepNode> {
    val out = ArrayList<StepNode>()
    fun walk(nodes: List<ScriptNode>) {
        for (node in nodes) {
            when (node) {
                is StepNode -> out.add(node)
                is GroupNode -> walk(node.children)
            }
        }
    }
    walk(this)
    return out
}