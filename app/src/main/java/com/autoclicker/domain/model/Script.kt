package com.autoclicker.domain.model

import kotlinx.serialization.Serializable

/**
 * 脚本：名称 + 步骤树（对应 FR-6）。
 *
 * @property id 脚本稳定 ID
 * @property name 脚本名称
 * @property steps 脚本步骤树（可含嵌套循环段与函数包调用）
 * @property repeatPolicy 脚本级重复策略（次数 / 总时长 / 直到手动停止，对应 FR-2）；
 *           默认 [RepeatPolicy.UntilStopped]，因此三参构造 Script(id, name, steps) 依然可用
 */
@Serializable
data class Script(
    val id: String,
    val name: String,
    val steps: List<ScriptStep> = emptyList(),
    val repeatPolicy: RepeatPolicy = RepeatPolicy.UntilStopped,
)

/**
 * 脚本级重复策略（对应 FR-2）。
 *
 * 使用 sealed interface 默认多态序列化，discriminator 值为子类简单类名。
 */
@Serializable
sealed interface RepeatPolicy {

    /** 重复指定次数；[times] 必须 >= 1 */
    @Serializable
    data class Count(
        val times: Int = 1,
    ) : RepeatPolicy

    /** 重复直到总时长达到 [durationMs] 毫秒 */
    @Serializable
    data class UntilTime(
        val durationMs: Long,
    ) : RepeatPolicy

    /** 无限循环，直到用户手动停止 */
    @Serializable
    data object UntilStopped : RepeatPolicy
}
