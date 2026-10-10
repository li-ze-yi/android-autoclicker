package com.autoclicker.domain.model

import kotlinx.serialization.Serializable

/**
 * 脚本步骤树的一个节点（对应 FR-6 / FR-6A / FR-6B）。
 *
 * 每个步骤都带一个稳定 [id]，供编辑器做单选、拖拽排序与跨层级移动；
 * 步骤树允许通过 [LoopGroup] 任意嵌套。
 *
 * 多态说明：与 [Action] 一样使用 sealed interface 的默认多态序列化。
 * [ScriptStep] 与 [Action] 是互相独立的多态层次，discriminator 都叫 "type"，
 * 但各自只出现在自己的 JSON 对象里（Action 嵌在 BasicStep 的 action 字段内），
 * 因此不会冲突。
 */
@Serializable
sealed interface ScriptStep {

    /** 步骤稳定 ID，编辑器内唯一 */
    val id: String

    /**
     * 基础步骤：包装一个 [Action]。
     */
    @Serializable
    data class BasicStep(
        override val id: String,
        val action: Action,
    ) : ScriptStep

    /**
     * 循环段（FR-6A）：把 [steps] 整体执行 [count] 次后继续后续步骤；
     * [steps] 内可再嵌套循环段。
     *
     * @param count 循环次数，必须 >= 1，由 StructureValidator 校验
     */
    @Serializable
    data class LoopGroup(
        override val id: String,
        val name: String = DEFAULT_NAME,
        val steps: List<ScriptStep> = emptyList(),
        val count: Int = 1,
    ) : ScriptStep {
        companion object {
            /** 循环段默认名称 */
            const val DEFAULT_NAME: String = "循环段"
        }
    }

    /**
     * 函数包调用（FR-6B）：脚本中只存被引用函数包的 [packageId]（单一数据源），
     * 运行时按 ID 解析函数包最新内容。调用链深度与环由 StructureValidator 校验。
     */
    @Serializable
    data class PackageCall(
        override val id: String,
        val packageId: String,
    ) : ScriptStep
}
