package com.autoclicker.core.runner

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import com.autoclicker.core.accessibility.GestureExecutor
import com.autoclicker.core.accessibility.GlobalActions
import com.autoclicker.core.accessibility.NodeFinder
import com.autoclicker.core.accessibility.NodeSelector
import com.autoclicker.core.script.CompareOp
import com.autoclicker.core.script.Condition
import com.autoclicker.core.script.OnTimeout
import com.autoclicker.core.script.Script
import com.autoclicker.core.script.Step
import com.autoclicker.core.script.describe
import com.autoclicker.core.vision.ColorMatcher
import com.autoclicker.core.vision.ImageTemplateRepository
import com.autoclicker.core.vision.MatchResult
import com.autoclicker.core.vision.ScreenCaptureService
import com.autoclicker.core.vision.TemplateMatcher
import kotlin.coroutines.coroutineContext
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 单步执行结果。success=false 时 message 为失败原因。 */
data class StepResult(val success: Boolean, val message: String? = null)

/**
 * 脚本执行引擎：按轮次顺序执行 [Script] 中的步骤，支持循环、暂停 / 恢复 / 停止。
 * 全局单例，状态通过 [state] 暴露给 UI。
 */
object ScriptRunner {

    /** 识图 / 识色超时重试的轮询间隔。 */
    private const val MATCH_POLL_INTERVAL_MS = 500L

    /** 循环间隔等待时，暂停检查的切片长度。 */
    private const val PAUSE_SLICE_MS = 100L

    /** 单轮最多执行步数（含跳转重复执行），兜底防止脚本死循环把设备卡死。 */
    private const val MAX_STEPS_PER_LOOP = 100_000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var job: Job? = null

    /** 应用上下文，用于启动 App 步骤与识图模板读取。 */
    private var appContext: Context? = null

    /** 手动停止标记，用于在取消回调中区分「手动停止」与「其他取消」。 */
    @Volatile
    private var stopRequested = false

    private val paused = MutableStateFlow(false)

    private val _state = MutableStateFlow<RunnerState>(RunnerState.Idle)
    val state: StateFlow<RunnerState> = _state.asStateFlow()

    /** 是否正在执行（含暂停状态）。 */
    val isRunning: Boolean
        get() = job?.isActive == true

    /**
     * 启动脚本执行。若已在运行则忽略并返回 false。
     * 成功提交执行返回 true。
     */
    fun start(context: Context, script: Script): Boolean {
        if (job?.isActive == true) {
            return false
        }
        appContext = context.applicationContext
        paused.value = false
        stopRequested = false
        job = scope.launch { execute(script) }
        return true
    }

    /** 暂停执行，仅在未暂停的运行状态生效；状态由执行循环更新。 */
    fun pause() {
        val current = _state.value
        if (current is RunnerState.Running && !current.paused) {
            paused.value = true
        }
    }

    /** 恢复执行。 */
    fun resume() {
        paused.value = false
    }

    /** 停止执行并取消协程，状态置为已停止。 */
    fun stop() {
        val scriptName = (_state.value as? RunnerState.Running)?.scriptName
        stopRequested = true
        job?.cancel()
        paused.value = false
        job = null
        if (scriptName != null) {
            _state.value = RunnerState.Finished(scriptName, false, "已手动停止")
        }
    }

    private suspend fun execute(script: Script) {
        try {
            if (script.steps.isEmpty()) {
                _state.value = RunnerState.Finished(script.name, false, "脚本为空")
                return
            }

            val steps = script.steps
            val total = steps.size
            val totalLoops = if (script.loopInfinite) -1 else maxOf(1, script.loopCount)
            val labels = buildLabelMap(steps)
            val vars = HashMap<String, String>()

            var loopIndex = 0
            while (true) {
                coroutineContext.ensureActive()
                if (!script.loopInfinite && loopIndex >= totalLoops) {
                    break
                }

                // 单轮内用 pc 游标执行，支持 Label/Jump/IfElse 改变流向。
                var pc = 0
                val jumpCounts = HashMap<Int, Int>()
                var stepsExecuted = 0

                while (pc < steps.size) {
                    coroutineContext.ensureActive()
                    if (stepsExecuted++ >= MAX_STEPS_PER_LOOP) {
                        _state.value = RunnerState.Finished(
                            script.name,
                            false,
                            "单轮执行步数超限（可能存在死循环），已中止"
                        )
                        return
                    }

                    val step = steps[pc]
                    val stepText = step.describe()

                    if (paused.value) {
                        _state.value = RunnerState.Running(
                            script.id, script.name, pc, total, stepText, loopIndex, totalLoops,
                            paused = true
                        )
                        paused.first { !it }
                    }

                    _state.value = RunnerState.Running(
                        script.id, script.name, pc, total, stepText, loopIndex, totalLoops
                    )

                    val stepDelay = if (step.delayBeforeMs > 0L) {
                        jitterDelay(step.delayBeforeMs, script.jitterDelayPercent)
                    } else {
                        0L
                    }
                    if (stepDelay > 0L) {
                        awaitInterruptible(stepDelay)
                    }

                    var nextPc = pc + 1
                    when (step) {
                        is Step.Label -> {
                            // 标签本身无副作用。
                        }

                        is Step.SetVar -> vars[step.name] = interpolate(step.value, vars)

                        is Step.Jump -> {
                            val used = jumpCounts.getOrElse(pc) { 0 }
                            if (step.maxTimes >= 0 && used >= step.maxTimes) {
                                // 达到跳转次数上限，继续向下执行。
                            } else {
                                val target = labels[step.label]
                                if (target == null) {
                                    if (script.stopOnError) {
                                        finishFailed(script, pc, StepResult(false, "跳转目标标签不存在：${step.label}"))
                                        return
                                    }
                                } else {
                                    jumpCounts[pc] = used + 1
                                    nextPc = target
                                }
                            }
                        }

                        is Step.IfElse -> {
                            val matched = evaluateCondition(step.condition, vars)
                            val label = if (matched) step.thenLabel else step.elseLabel
                            if (label != null) {
                                val target = labels[label]
                                if (target == null) {
                                    if (script.stopOnError) {
                                        finishFailed(script, pc, StepResult(false, "条件跳转目标标签不存在：$label"))
                                        return
                                    }
                                } else {
                                    nextPc = target
                                }
                            }
                        }

                        else -> {
                            val result = executeStep(script, step, vars)
                            if (!result.success && script.stopOnError) {
                                finishFailed(script, pc, result)
                                return
                            }
                        }
                    }
                    pc = nextPc
                }

                loopIndex++
                if (!script.loopInfinite && loopIndex >= totalLoops) {
                    break
                }
                if (script.loopIntervalMs > 0) {
                    awaitInterruptible(script.loopIntervalMs)
                }
            }

            _state.value = RunnerState.Finished(script.name, true, null)
        } catch (e: CancellationException) {
            _state.value = RunnerState.Finished(
                script.name,
                false,
                if (stopRequested) "已手动停止" else "已停止"
            )
        } catch (e: Exception) {
            _state.value = RunnerState.Finished(script.name, false, e.message ?: "执行异常")
        } finally {
            stopRequested = false
        }
    }

    private fun finishFailed(script: Script, pc: Int, result: StepResult) {
        _state.value = RunnerState.Finished(
            script.name,
            false,
            "第 ${pc + 1} 步失败：${result.message ?: "未知错误"}"
        )
    }

    /** 建立 标签名 -> 步骤下标 的映射；同名标签以最后一个为准。 */
    private fun buildLabelMap(steps: List<Step>): Map<String, Int> {
        val map = HashMap<String, Int>()
        steps.forEachIndexed { index, step ->
            if (step is Step.Label) map[step.name] = index
        }
        return map
    }

    /** 把文本中的 `${变量名}` 替换为变量值（P3）。单遍从左到右扫描，替换后的值不再参与匹配。 */
    private fun interpolate(text: String, vars: Map<String, String>): String {
        if (text.indexOf('$') < 0 || vars.isEmpty()) return text
        val builder = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '$' && i + 1 < text.length && text[i + 1] == '{') {
                val end = text.indexOf('}', i + 2)
                if (end >= 0) {
                    val value = vars[text.substring(i + 2, end)]
                    if (value != null) {
                        builder.append(value)
                        i = end + 1
                        continue
                    }
                }
            }
            builder.append(c)
            i++
        }
        return builder.toString()
    }

    /** 求值条件（P3）。 */
    private suspend fun evaluateCondition(condition: Condition, vars: Map<String, String>): Boolean =
        when (condition) {
            is Condition.ElementExists -> {
                val selector = NodeSelector(
                    text = condition.text,
                    viewId = condition.viewId,
                    contentDesc = condition.contentDesc,
                    className = condition.className,
                    index = condition.index
                )
                val node = NodeFinder.awaitNode(selector, condition.timeoutMs)
                val found = node != null
                node?.let { NodeFinder.recycleQuietly(it) }
                found
            }

            is Condition.ColorFound -> colorFound(condition)

            is Condition.VarCompare -> compareVar(vars[condition.name], condition.op, condition.value)
        }

    private suspend fun colorFound(condition: Condition.ColorFound): Boolean {
        if (!ScreenCaptureService.isReady) return false
        val rect = buildRegion(
            condition.regionLeft, condition.regionTop,
            condition.regionWidth, condition.regionHeight
        )
        val screen = withContext(Dispatchers.IO) { ScreenCaptureService.capture(0) } ?: return false
        return try {
            withContext(Dispatchers.IO) {
                ColorMatcher.findColor(screen, condition.color, condition.tolerance, rect)
            } != null
        } finally {
            if (!screen.isRecycled) screen.recycle()
        }
    }

    private fun compareVar(actual: String?, op: CompareOp, expected: String): Boolean {
        val a = actual ?: ""
        return when (op) {
            CompareOp.EQ -> a == expected
            CompareOp.NE -> a != expected
            CompareOp.CONTAINS -> a.contains(expected)
            CompareOp.GT -> (a.toDoubleOrNull() ?: 0.0) > (expected.toDoubleOrNull() ?: 0.0)
            CompareOp.LT -> (a.toDoubleOrNull() ?: 0.0) < (expected.toDoubleOrNull() ?: 0.0)
        }
    }

    /** 可取消、且会跟随暂停状态挂起的等待（用于循环间隔）。 */
    private suspend fun awaitInterruptible(totalMs: Long) {
        var remaining = totalMs
        while (remaining > 0) {
            coroutineContext.ensureActive()
            if (paused.value) {
                paused.first { !it }
            }
            val slice = if (remaining > PAUSE_SLICE_MS) PAUSE_SLICE_MS else remaining
            delay(slice)
            remaining -= slice
        }
    }

    private suspend fun executeStep(script: Script, step: Step, vars: Map<String, String>): StepResult = when (step) {
        is Step.Tap -> {
            val point = resolvePoint(
                step.x, step.y,
                step.boundsLeft, step.boundsTop, step.boundsRight, step.boundsBottom,
                step.relX, step.relY
            )
            val x = jitterCoordinate(point.x, script.jitterRadiusPx)
            val y = jitterCoordinate(point.y, script.jitterRadiusPx)
            if (GestureExecutor.click(x, y)) {
                StepResult(true, "点击成功")
            } else {
                StepResult(false, "点击失败（无障碍服务未连接或手势被拒绝）")
            }
        }

        is Step.LongPress -> {
            val point = resolvePoint(
                step.x, step.y,
                step.boundsLeft, step.boundsTop, step.boundsRight, step.boundsBottom,
                step.relX, step.relY
            )
            val x = jitterCoordinate(point.x, script.jitterRadiusPx)
            val y = jitterCoordinate(point.y, script.jitterRadiusPx)
            if (GestureExecutor.longPress(x, y, step.durationMs)) {
                StepResult(true, "长按成功")
            } else {
                StepResult(false, "长按失败（无障碍服务未连接或手势被拒绝）")
            }
        }

        // 滑动不做坐标扰动；有轨迹时按完整轨迹派发。
        is Step.Swipe -> {
            val path = step.path
            val ok = if (path != null && path.size > 1) {
                GestureExecutor.dispatchPath(listOf(path))
            } else {
                GestureExecutor.swipe(step.x1, step.y1, step.x2, step.y2, step.durationMs)
            }
            if (ok) {
                StepResult(true, "滑动成功")
            } else {
                StepResult(false, "滑动失败（无障碍服务未连接或手势被拒绝）")
            }
        }

        // 多指手势（P0）：一次派发全部轨迹。
        is Step.MultiGesture -> {
            if (step.strokes.isEmpty()) {
                StepResult(false, "多指手势为空")
            } else if (GestureExecutor.dispatchPath(step.strokes)) {
                StepResult(true, "多指手势完成")
            } else {
                StepResult(false, "多指手势失败（无障碍服务未连接或手势被拒绝）")
            }
        }

        // 输入不做坐标扰动；支持 ${变量} 替换。
        is Step.Input -> {
            if (GlobalActions.inputText(interpolate(step.text, vars))) {
                StepResult(true, "输入成功")
            } else {
                StepResult(false, "输入失败（未找到输入焦点）")
            }
        }

        is Step.Wait -> {
            awaitInterruptible(jitterDelay(step.durationMs, script.jitterDelayPercent))
            StepResult(true, "等待完成")
        }

        // 启动应用不做坐标扰动；支持 ${变量} 替换。
        is Step.LaunchApp -> launchApp(interpolate(step.packageName, vars))

        is Step.WaitForElement -> waitForElement(step, vars)

        // 返回键不做坐标扰动。
        is Step.Back -> {
            if (GlobalActions.back()) {
                StepResult(true, "返回成功")
            } else {
                StepResult(false, "返回失败（无障碍服务未连接）")
            }
        }

        // 主页键不做坐标扰动。
        is Step.Home -> {
            if (GlobalActions.home()) {
                StepResult(true, "回到主页成功")
            } else {
                StepResult(false, "回到主页失败（无障碍服务未连接）")
            }
        }

        is Step.Burst -> burst(script, step)

        is Step.TapElement -> tapElement(script, step, vars)

        is Step.ImageTap -> imageTap(script, step)

        is Step.ColorTap -> colorTap(script, step)

        // 以下类型由 execute 处理（控制流/变量），此处仅为 when 穷尽兜底。
        is Step.SetVar, is Step.Label, is Step.Jump, is Step.IfElse -> StepResult(true, null)
    }

    /**
     * P1：优先用录制时保存的控件包围盒重新定位控件，并按真实触点在该控件内的相对比例算落点；
     * 定位失败时退回绝对坐标。
     */
    private fun resolvePoint(
        x: Float,
        y: Float,
        boundsLeft: Int?,
        boundsTop: Int?,
        boundsRight: Int?,
        boundsBottom: Int?,
        relX: Float?,
        relY: Float?
    ): PointF {
        if (boundsLeft != null && boundsTop != null && boundsRight != null && boundsBottom != null) {
            val node = NodeFinder.findByBounds(boundsLeft, boundsTop, boundsRight, boundsBottom)
            if (node != null) {
                try {
                    val rect = Rect()
                    node.getBoundsInScreen(rect)
                    if (rect.width() > 0 && rect.height() > 0) {
                        val rx = (relX ?: 0.5f).coerceIn(0f, 1f)
                        val ry = (relY ?: 0.5f).coerceIn(0f, 1f)
                        return PointF(rect.left + rect.width() * rx, rect.top + rect.height() * ry)
                    }
                } finally {
                    NodeFinder.recycleQuietly(node)
                }
            }
        }
        return PointF(x, y)
    }

    private suspend fun launchApp(packageName: String): StepResult {
        val context = appContext ?: return StepResult(false, "启动应用失败：上下文未初始化")
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return StepResult(false, "未安装：$packageName")
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            // 用可中断等待替代普通 delay：暂停/停止时能立即响应。
            awaitInterruptible(1000L)
            StepResult(true, "已启动 $packageName")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            StepResult(false, "启动失败：${e.message ?: "未知错误"}")
        }
    }

    private suspend fun waitForElement(step: Step.WaitForElement, vars: Map<String, String>): StepResult {
        val selector = NodeSelector(
            text = step.text?.let { interpolate(it, vars) },
            viewId = step.viewId,
            contentDesc = step.contentDesc,
            className = step.className
        )
        val node = NodeFinder.awaitNode(selector, step.timeoutMs)
        val found = node != null
        node?.let { NodeFinder.recycleQuietly(it) }
        return if (found) {
            delay(200)
            StepResult(true, "元素已出现")
        } else if (step.onTimeout == OnTimeout.SKIP) {
            StepResult(true, "元素未出现，已跳过")
        } else {
            StepResult(false, "等待元素超时")
        }
    }

    /**
     * 连点：循环 count 次点击同一坐标。
     *
     * 取舍说明：GestureExecutor.click 的触摸时长固定为 50ms，不支持自定义时长，
     * 因此当 touchDurationMs > 60 时退化为 longPress(x, y, touchDurationMs) 来近似更长的按压，
     * 否则使用 click。每一次派发前都重新做坐标扰动（拟人化）。
     */
    private suspend fun burst(script: Script, step: Step.Burst): StepResult {
        if (step.count <= 0) {
            return StepResult(false, "连点次数无效：${step.count}")
        }
        for (i in 0 until step.count) {
            val x = jitterCoordinate(step.x, script.jitterRadiusPx)
            val y = jitterCoordinate(step.y, script.jitterRadiusPx)
            val ok = if (step.touchDurationMs > 60L) {
                GestureExecutor.longPress(x, y, step.touchDurationMs)
            } else {
                GestureExecutor.click(x, y)
            }
            if (!ok) {
                return StepResult(false, "连点第 ${i + 1} 次失败（无障碍服务未连接或手势被拒绝）")
            }
            if (i < step.count - 1) {
                delay(jitterDelay(step.intervalMs, script.jitterDelayPercent))
            }
        }
        return StepResult(true, "连点完成 ×${step.count}")
    }

    private suspend fun tapElement(script: Script, step: Step.TapElement, vars: Map<String, String>): StepResult {
        val selector = NodeSelector(
            text = step.text?.let { interpolate(it, vars) },
            viewId = step.viewId,
            contentDesc = step.contentDesc,
            className = step.className,
            index = step.index
        )
        val node = NodeFinder.awaitNode(selector, step.timeoutMs)
            ?: return timeoutResult(step.onTimeout, "智能定位超时")
        val center = NodeFinder.centerOf(node)
        NodeFinder.recycleQuietly(node)
        val x = jitterCoordinate(center.x, script.jitterRadiusPx)
        val y = jitterCoordinate(center.y, script.jitterRadiusPx)
        return if (GestureExecutor.click(x, y)) {
            StepResult(true, "智能定位点击成功")
        } else {
            StepResult(false, "点击失败（无障碍服务未连接或手势被拒绝）")
        }
    }

    private suspend fun imageTap(script: Script, step: Step.ImageTap): StepResult {
        if (!ScreenCaptureService.isReady) {
            return StepResult(false, "截屏未授权，请先在识图页授权")
        }
        val context = appContext ?: return StepResult(false, "识图失败：上下文未初始化")
        val repo = ImageTemplateRepository.get(context)
        val templateMeta = repo.load(step.templateId)
            ?: return StepResult(false, "模板不存在")
        val template = repo.loadBitmap(templateMeta)
            ?: return StepResult(false, "模板不存在")
        try {
            val rect = buildRegion(
                step.regionLeft,
                step.regionTop,
                step.regionWidth,
                step.regionHeight
            )
            val threshold = step.thresholdPercent / 100f
            val matched = pollMatch(step.timeoutMs) { screen ->
                TemplateMatcher.findMatch(screen, template, rect, threshold)
            } ?: return timeoutResult(step.onTimeout, "识图超时")
            return clickAt(
                script,
                matched.centerX + step.offsetX,
                matched.centerY + step.offsetY,
                "识图点击成功"
            )
        } finally {
            if (!template.isRecycled) {
                template.recycle()
            }
        }
    }

    private suspend fun colorTap(script: Script, step: Step.ColorTap): StepResult {
        if (!ScreenCaptureService.isReady) {
            return StepResult(false, "截屏未授权，请先在识图页授权")
        }
        val rect = buildRegion(step.regionLeft, step.regionTop, step.regionWidth, step.regionHeight)
        val matched = pollMatch(step.timeoutMs) { screen ->
            ColorMatcher.findColor(screen, step.color, step.tolerance, rect)
        } ?: return timeoutResult(step.onTimeout, "识色超时")
        return clickAt(
            script,
            matched.centerX + step.offsetX,
            matched.centerY + step.offsetY,
            "识色点击成功"
        )
    }

    /**
     * 在超时时间内每隔 [MATCH_POLL_INTERVAL_MS] 截屏并匹配一次，直到命中或超时。
     * 截屏与匹配均在 Dispatchers.IO 上执行；每次使用的屏幕截图都会被回收。
     */
    private suspend fun pollMatch(
        timeoutMs: Long,
        matcher: (Bitmap) -> MatchResult?
    ): MatchResult? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            coroutineContext.ensureActive()
            val screen = withContext(Dispatchers.IO) { ScreenCaptureService.capture(0) }
            if (screen != null) {
                try {
                    val result = withContext(Dispatchers.IO) { matcher(screen) }
                    if (result != null) {
                        return result
                    }
                } finally {
                    if (!screen.isRecycled) {
                        screen.recycle()
                    }
                }
            }
            if (System.currentTimeMillis() >= deadline) {
                return null
            }
            delay(MATCH_POLL_INTERVAL_MS)
        }
    }

    /** 命中点加偏移与扰动后点击。 */
    private suspend fun clickAt(
        script: Script,
        centerX: Int,
        centerY: Int,
        successMessage: String
    ): StepResult {
        val x = jitterCoordinate(centerX.toFloat(), script.jitterRadiusPx)
        val y = jitterCoordinate(centerY.toFloat(), script.jitterRadiusPx)
        return if (GestureExecutor.click(x, y)) {
            StepResult(true, successMessage)
        } else {
            StepResult(false, "点击失败（无障碍服务未连接或手势被拒绝）")
        }
    }

    /** 区域语义：宽或高 <= 0 表示全屏，返回 null。 */
    private fun buildRegion(left: Int, top: Int, width: Int, height: Int): Rect? =
        if (width <= 0 || height <= 0) null else Rect(left, top, left + width, top + height)

    private fun timeoutResult(onTimeout: OnTimeout, message: String): StepResult =
        if (onTimeout == OnTimeout.SKIP) {
            StepResult(true, "$message，已跳过")
        } else {
            StepResult(false, message)
        }

    /** 坐标拟人化扰动：偏移量在 [-radius, radius] 内随机，偏移后不小于 0。 */
    private fun jitterCoordinate(value: Float, radius: Int): Float {
        if (radius <= 0) {
            return value
        }
        val offset = Random.nextInt(-radius, radius + 1)
        return (value + offset).coerceAtLeast(0f)
    }

    /** 延时拟人化扰动：乘以随机系数 1 ± percent/100，结果不小于 1ms。 */
    private fun jitterDelay(durationMs: Long, percent: Int): Long {
        if (percent <= 0) {
            return durationMs
        }
        val factor = 1.0 + Random.nextInt(-percent, percent + 1) / 100.0
        return (durationMs * factor).toLong().coerceAtLeast(1L)
    }
}