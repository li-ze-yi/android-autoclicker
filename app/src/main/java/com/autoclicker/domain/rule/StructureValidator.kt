package com.autoclicker.domain.rule

import com.autoclicker.domain.model.Action
import com.autoclicker.domain.model.CallFunctionAction
import com.autoclicker.domain.model.ClickImageAction
import com.autoclicker.domain.model.ExtractContentAction
import com.autoclicker.domain.model.FunctionPackage
import com.autoclicker.domain.model.GroupNode
import com.autoclicker.domain.model.InputTextAction
import com.autoclicker.domain.model.JumpAction
import com.autoclicker.domain.model.JumpMode
import com.autoclicker.domain.model.Script
import com.autoclicker.domain.model.ScriptNode
import com.autoclicker.domain.model.StepNode
import com.autoclicker.domain.model.TextMode

/** 结构校验问题。 */
data class ValidationIssue(
    val level: Level,
    val message: String,
) {
    enum class Level { ERROR, WARNING }
}

/**
 * 脚本 / 函数包结构校验：重复 ID、跳转目标是否存在、循环次数合法性等。
 */
object StructureValidator {

    fun validateScript(script: Script, knownPackageIds: Set<String> = emptySet()): List<ValidationIssue> {
        val issues = ArrayList<ValidationIssue>()
        if (script.name.isBlank()) {
            issues += ValidationIssue(ValidationIssue.Level.WARNING, "脚本名称为空")
        }
        validateNodes(script.nodes, knownPackageIds, issues)
        return issues
    }

    fun validatePackage(pkg: FunctionPackage): List<ValidationIssue> {
        val issues = ArrayList<ValidationIssue>()
        if (pkg.name.isBlank()) {
            issues += ValidationIssue(ValidationIssue.Level.ERROR, "函数包名称为空")
        }
        val names = HashSet<String>()
        pkg.params.forEach {
            if (it.name.isBlank()) {
                issues += ValidationIssue(ValidationIssue.Level.ERROR, "函数包存在空参数名")
            }
            if (!names.add(it.name)) {
                issues += ValidationIssue(ValidationIssue.Level.ERROR, "函数包参数名重复：${it.name}")
            }
        }
        validateNodes(pkg.nodes, emptySet(), issues)
        return issues
    }

    private fun validateNodes(
        nodes: List<ScriptNode>,
        knownPackageIds: Set<String>,
        issues: MutableList<ValidationIssue>,
    ) {
        val allSteps = ArrayList<StepNode>()
        val ids = HashSet<String>()
        fun walk(list: List<ScriptNode>) {
            for (node in list) {
                if (!ids.add(node.id)) {
                    issues += ValidationIssue(ValidationIssue.Level.ERROR, "节点 ID 重复：${node.id}")
                }
                when (node) {
                    is StepNode -> allSteps.add(node)
                    is GroupNode -> {
                        if (node.loopCount < 1) {
                            issues += ValidationIssue(
                                ValidationIssue.Level.ERROR,
                                "步骤组「${node.name.ifBlank { node.id }}」循环次数需 ≥ 1",
                            )
                        }
                        walk(node.children)
                    }
                }
            }
        }
        walk(nodes)

        val stepIds = allSteps.map { it.id }.toHashSet()
        fun checkJump(target: String?, label: String) {
            if (target != null && target !in stepIds) {
                issues += ValidationIssue(ValidationIssue.Level.ERROR, "$label 跳转目标不存在：$target")
            }
        }

        for (step in allSteps) {
            if (step.repeatCount < 1) {
                issues += ValidationIssue(ValidationIssue.Level.ERROR, "步骤重复次数需 ≥ 1：${step.id}")
            }
            checkJump(step.onSuccessStepId, "成功")
            checkJump(step.onFailureStepId, "失败")
            when (val action = step.action) {
                is JumpAction -> if (action.mode == JumpMode.STEP) {
                    checkJump(action.targetStepId, "跳转")
                }
                is CallFunctionAction -> if (knownPackageIds.isNotEmpty() && action.packageId !in knownPackageIds) {
                    issues += ValidationIssue(ValidationIssue.Level.WARNING, "引用的函数包不存在：${action.packageId}")
                }
                is ClickImageAction -> if (action.templateId.isBlank()) {
                    issues += ValidationIssue(ValidationIssue.Level.ERROR, "点击图片未选择模板：${step.id}")
                }
                is InputTextAction -> if (action.source.mode == TextMode.TEXT_GROUP && action.source.groupId.isBlank()) {
                    issues += ValidationIssue(ValidationIssue.Level.WARNING, "输入文字未选择文本组：${step.id}")
                }
                is ExtractContentAction -> if (action.targetVar.isBlank()) {
                    issues += ValidationIssue(ValidationIssue.Level.ERROR, "内容提取未指定目标变量：${step.id}")
                }
                else -> Unit
            }
        }
    }
}

/** 校验工具：动作是否为「带成功/失败结果」的识别或条件类动作。 */
fun Action.hasOutcomeBranch(): Boolean = when (this) {
    is ClickImageAction,
    is com.autoclicker.domain.model.ClickColorAction,
    is com.autoclicker.domain.model.ClickTextAction,
    is com.autoclicker.domain.model.ClickNodeAction,
    is com.autoclicker.domain.model.ConditionAction,
    -> true
    else -> false
}