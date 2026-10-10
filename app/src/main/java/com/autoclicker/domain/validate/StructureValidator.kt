package com.autoclicker.domain.validate

import com.autoclicker.domain.model.Action
import com.autoclicker.domain.model.FunctionPackage
import com.autoclicker.domain.model.Rect
import com.autoclicker.domain.model.RepeatPolicy
import com.autoclicker.domain.model.Script
import com.autoclicker.domain.model.ScriptStep

/**
 * 脚本 / 函数包结构合法性校验（纯 Kotlin，对应 FR-6A / FR-6B、AC-15 / AC-16）。
 *
 * 校验规则：
 * - 函数包调用链深度最多 3 层（根函数包 = 第 1 层）；
 * - 禁止函数包自调用、禁止任何形式的环调用（DFS 三色染色检测）；
 * - 被调用的函数包缺失时给出明确中文原因；
 * - 基础不变量：循环段次数 >= 1、相似度 0..1、坐标与时长非负、Rect 左右/上下关系合法、
 *   步骤 id 与 packageId 非空。
 */
object StructureValidator {

    /** 函数包调用最大深度（根为第 1 层） */
    private const val MAX_PACKAGE_DEPTH = 3

    /** 校验结果 */
    sealed interface Result {
        /** 全部合法 */
        data object Valid : Result

        /**
         * 存在非法项
         * @property reasons 全部中文原因，供 UI 直接展示
         */
        data class Invalid(val reasons: List<String>) : Result
    }

    /**
     * 校验一批函数包之间的调用关系，并同时校验每个包步骤树的基础不变量。
     * 用于函数包保存时的整体校验（FR-6B）。
     */
    fun validatePackageCalls(packages: Map<String, FunctionPackage>): Result {
        val reasons = mutableListOf<String>()

        // 1) 每个函数包自身步骤树的基础不变量
        packages.values.forEach { pkg ->
            validateStepsInto(pkg.steps, reasons, "函数包「${pkg.name}」(${pkg.id})")
        }

        // 邻接表：包 ID -> 该包步骤树中引用到的函数包 ID（去重，避免重复报错）
        val edges: Map<String, List<String>> =
            packages.mapValues { (_, pkg) -> collectPackageCallIds(pkg.steps).distinct() }

        // 2) 缺失包、自调用与环检测（DFS 三色染色）
        detectCyclesAndMissing(packages, edges, reasons)

        // 3) 调用深度检测：以每个包为根独立 DFS，确保任意起点的调用链都不超过 3 层
        packages.keys.forEach { rootId ->
            depthFirstCheck(
                id = rootId,
                depth = 1,
                chain = listOf(rootId),
                inPath = mutableSetOf(rootId),
                packages = packages,
                edges = edges,
                reasons = reasons,
            )
        }

        return if (reasons.isEmpty()) Result.Valid else Result.Invalid(reasons.distinct())
    }

    /** 校验单个脚本的步骤树与脚本级参数（基础不变量） */
    fun validateScript(script: Script): Result {
        val reasons = mutableListOf<String>()
        if (script.id.isBlank()) reasons += "脚本 id 不能为空"
        if (script.name.isBlank()) reasons += "脚本名称不能为空"
        validateStepsInto(script.steps, reasons, "脚本「${script.name}」")
        when (val policy = script.repeatPolicy) {
            is RepeatPolicy.Count ->
                if (policy.times < 1) reasons += "脚本重复次数必须 >= 1，当前为 ${policy.times}"
            is RepeatPolicy.UntilTime ->
                if (policy.durationMs < 0) reasons += "脚本总时长不能为负，当前为 ${policy.durationMs}ms"
            is RepeatPolicy.UntilStopped -> Unit
        }
        return if (reasons.isEmpty()) Result.Valid else Result.Invalid(reasons)
    }

    /** 校验单个函数包自身步骤树的基础不变量（不含跨包调用关系） */
    fun validateFunctionPackage(functionPackage: FunctionPackage): Result {
        val reasons = mutableListOf<String>()
        if (functionPackage.id.isBlank()) reasons += "函数包 id 不能为空"
        if (functionPackage.name.isBlank()) reasons += "函数包名称不能为空"
        validateStepsInto(functionPackage.steps, reasons, "函数包「${functionPackage.name}」")
        return if (reasons.isEmpty()) Result.Valid else Result.Invalid(reasons)
    }

    // ---------------------------------------------------------------------
    // 调用图检测
    // ---------------------------------------------------------------------

    /**
     * DFS 三色染色：白（未访问，用 null 表示）、灰（在当前递归栈中）、黑（已完成）。
     * 遇到灰节点即发现回边：指向自身为自调用，指向其他灰祖先为环。
     */
    private fun detectCyclesAndMissing(
        packages: Map<String, FunctionPackage>,
        edges: Map<String, List<String>>,
        reasons: MutableList<String>,
    ) {
        val color = HashMap<String, Int>() // 1=灰，2=黑；无记录=白

        fun dfs(nodeId: String, path: List<String>) {
            color[nodeId] = 1
            val nodePackage = packages.getValue(nodeId)
            for (calleeId in edges[nodeId].orEmpty()) {
                val calleePackage = packages[calleeId]
                if (calleePackage == null) {
                    reasons += "函数包「${nodePackage.name}」($nodeId) 调用了不存在的函数包（$calleeId）"
                    continue
                }
                when (color[calleeId]) {
                    1 -> {
                        if (calleeId == nodeId) {
                            reasons += "函数包「${nodePackage.name}」($nodeId) 不能调用自身"
                        } else {
                            reasons += "函数包调用链存在环：${(path + calleeId).joinToString(" → ")}"
                        }
                    }
                    2 -> Unit // 已完整处理，不可能再经它发现新环
                    else -> dfs(calleeId, path + calleeId)
                }
            }
            color[nodeId] = 2
        }

        packages.keys.forEach { rootId ->
            if (color[rootId] != 2) dfs(rootId, listOf(rootId))
        }
    }

    /**
     * 以 [id] 为当前节点做深度检查；[depth] 为该节点所在层数。
     * [inPath] 为当前根路径上的节点集合，命中即说明有环（已由染色阶段报告），停止下钻避免无限递归。
     */
    private fun depthFirstCheck(
        id: String,
        depth: Int,
        chain: List<String>,
        inPath: MutableSet<String>,
        packages: Map<String, FunctionPackage>,
        edges: Map<String, List<String>>,
        reasons: MutableList<String>,
    ) {
        if (depth > MAX_PACKAGE_DEPTH) {
            reasons += "函数包调用链深度最多 $MAX_PACKAGE_DEPTH 层（根为第 1 层），" +
                "当前链已达 $depth 层：${chain.joinToString(" → ")}"
            return
        }
        for (calleeId in edges[id].orEmpty()) {
            if (calleeId !in packages) continue // 缺失包已另行报告
            if (!inPath.add(calleeId)) continue // 环已另行报告，跳过避免死循环
            depthFirstCheck(
                id = calleeId,
                depth = depth + 1,
                chain = chain + calleeId,
                inPath = inPath,
                packages = packages,
                edges = edges,
                reasons = reasons,
            )
            inPath.remove(calleeId)
        }
    }

    // ---------------------------------------------------------------------
    // 步骤树基础不变量
    // ---------------------------------------------------------------------

    /** 递归收集步骤树内全部函数包调用的 packageId（按出现顺序） */
    private fun collectPackageCallIds(steps: List<ScriptStep>): List<String> {
        val ids = mutableListOf<String>()
        fun walk(step: ScriptStep) {
            when (step) {
                is ScriptStep.BasicStep -> Unit
                is ScriptStep.LoopGroup -> step.steps.forEach(::walk)
                is ScriptStep.PackageCall -> ids += step.packageId
            }
        }
        steps.forEach(::walk)
        return ids
    }

    private fun validateStepsInto(
        steps: List<ScriptStep>,
        reasons: MutableList<String>,
        location: String,
    ) {
        steps.forEachIndexed { index, step ->
            validateStepInto(step, reasons, "$location 第${index + 1}步")
        }
    }

    private fun validateStepInto(step: ScriptStep, reasons: MutableList<String>, location: String) {
        if (step.id.isBlank()) reasons += "$location：步骤 id 不能为空"
        when (step) {
            is ScriptStep.BasicStep ->
                validateActionInto(step.action, reasons, location)

            is ScriptStep.LoopGroup -> {
                if (step.count < 1) {
                    reasons += "$location：循环段「${step.name}」循环次数必须 >= 1，当前为 ${step.count}"
                }
                validateStepsInto(step.steps, reasons, "$location 循环段「${step.name}」")
            }

            is ScriptStep.PackageCall ->
                if (step.packageId.isBlank()) reasons += "$location：函数包调用的 packageId 不能为空"
        }
    }

    private fun validateActionInto(action: Action, reasons: MutableList<String>, location: String) {
        when (action) {
            is Action.Tap -> {
                if (action.x < 0 || action.y < 0) {
                    reasons += "$location：点击坐标不能为负，当前 x=${action.x}, y=${action.y}"
                }
            }

            is Action.LongPress -> {
                if (action.x < 0 || action.y < 0) {
                    reasons += "$location：长按坐标不能为负，当前 x=${action.x}, y=${action.y}"
                }
                if (action.durationMs < 0) {
                    reasons += "$location：长按时长不能为负，当前 ${action.durationMs}ms"
                }
            }

            is Action.Swipe -> {
                if (action.x1 < 0 || action.y1 < 0 || action.x2 < 0 || action.y2 < 0) {
                    reasons += "$location：滑动坐标不能为负，当前起点(${action.x1}, ${action.y1})、终点(${action.x2}, ${action.y2})"
                }
                if (action.durationMs < 0) {
                    reasons += "$location：滑动时长不能为负，当前 ${action.durationMs}ms"
                }
            }

            is Action.Delay ->
                if (action.durationMs < 0) {
                    reasons += "$location：延时时长不能为负，当前 ${action.durationMs}ms"
                }

            is Action.WaitImage -> {
                if (action.templateId.isBlank()) reasons += "$location：识图模板 id 不能为空"
                if (action.similarity !in 0.0..1.0) {
                    reasons += "$location：相似度必须在 0..1 之间，当前为 ${action.similarity}"
                }
                if (action.timeoutMs < 0) {
                    reasons += "$location：等待超时时间不能为负，当前 ${action.timeoutMs}ms"
                }
                action.region?.let { validateRectInto(it, reasons, "$location 找图区域") }
            }

            Action.GlobalHome, Action.GlobalBack -> Unit
        }
    }

    private fun validateRectInto(rect: Rect, reasons: MutableList<String>, location: String) {
        if (rect.left < 0 || rect.top < 0 || rect.right < 0 || rect.bottom < 0) {
            reasons += "$location：矩形坐标不能为负，当前 left=${rect.left}, top=${rect.top}, right=${rect.right}, bottom=${rect.bottom}"
        }
        if (rect.right < rect.left) reasons += "$location：矩形右边界不能小于左边界（left=${rect.left}, right=${rect.right}）"
        if (rect.bottom < rect.top) reasons += "$location：矩形下边界不能小于上边界（top=${rect.top}, bottom=${rect.bottom}）"
    }
}
