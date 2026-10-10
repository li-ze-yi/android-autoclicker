package com.autoclicker.domain.model

import kotlinx.serialization.Serializable

/**
 * 独立函数包：一段可复用的动作流程，可被任意脚本通过 CallFunctionAction 按 id 调用，
 * 支持入参（params）与返回值（returns）。
 */
@Serializable
data class FunctionPackage(
    val id: String,
    val name: String,
    val description: String = "",
    val schemaVersion: Int = Script.CURRENT_SCHEMA,
    val params: List<ParamDef> = emptyList(),
    val returns: List<ReturnDef> = emptyList(),
    val nodes: List<ScriptNode> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val actionCount: Int get() = nodes.flattenSteps().size
}