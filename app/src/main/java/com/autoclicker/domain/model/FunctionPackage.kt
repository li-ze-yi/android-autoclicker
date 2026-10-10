package com.autoclicker.domain.model

import kotlinx.serialization.Serializable

/**
 * 全局函数包（FR-6B）：一组可复用的步骤（可含循环段、可调用其他函数包），
 * 独立于脚本存储；脚本通过 [ScriptStep.PackageCall] 仅以 ID 引用。
 *
 * @property id 函数包稳定 ID，被引用方只存此 ID
 * @property name 函数包名称
 * @property steps 函数包步骤树
 */
@Serializable
data class FunctionPackage(
    val id: String,
    val name: String,
    val steps: List<ScriptStep> = emptyList(),
)
