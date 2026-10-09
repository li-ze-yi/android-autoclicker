package com.autoclicker.core.runner

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.autoclicker.core.accessibility.GestureExecutor
import com.autoclicker.core.accessibility.GlobalActions
import com.autoclicker.core.accessibility.NodeFinder
import com.autoclicker.core.accessibility.NodeSelector
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

    /** 暂停执行，仅在 Running 时生效；状态由执行循环更新为 Paused。 */
    fun pause() {
        if (_state.value is RunnerState.Running) {
            paused.value = true
        }
    }

    /** 恢复执行。 */
    fun resume() {
        paused.value = false
    }

    /** 停止执行并取消协程，状态置为已停止。 */
    fun stop() {
        val scriptName = when (val current = _state.value) {
            is RunnerState.Running -> current.scriptName
            is RunnerState.Paused -> current.scriptName
            else -> null
        }
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

            val total = script.steps.size
            val totalLoops = if (script.loopInfinite) -1 else maxOf(1, script.loopCount)

            var loopIndex = 0
            while (true) {
                coroutineContext.ensureActive()
                if (!script.loopInfinite && loopIndex >= totalLoops) {
                    break
                }

                script.steps.forEachIndexed { index, step ->
                    coroutineContext.ensureActive()

                    if (paused.value) {
                        _state.value = RunnerState.Paused(
                            script.id,
                            script.name,
                            index,
                            total,
                            step.describe(),
                            loopIndex,
                            totalLoops
                        )
                        paused.first { !it }
                    }

                    _state.value = RunnerState.Running(
                        script.id,
                        script.name,
                        index,
                        total,
                        step.describe(),
                        loopIndex,
                        totalLoops
                    )

                    val stepDelay = if (step.delayBeforeMs > 0L) {
                        jitterDelay(step.delayBeforeMs, script.jitterDelayPercent)
                    } else {
                        0L
                    }
                    if (stepDelay > 0L) {
                        awaitInterruptible(stepDelay)
                    }

                    val result = executeStep(script, step)
                    if (!result.success && script.stopOnError) {
                        _state.value = RunnerState.Finished(
                            script.name,
                            false,
                            "第 ${index + 1} 步失败：${result.message ?: "未知错误"}"
                        )
                        return
                    }
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

    private suspend fun executeStep(script: Script, step: Step): StepResult = when (step) {
        is Step.Tap -> {
            val x = jitterCoordinate(step.x, script.jitterRadiusPx)
            val y = jitterCoordinate(step.y, script.jitterRadiusPx)
            if (GestureExecutor.click(x, y)) {
                StepResult(true, "点击成功")
            } else {
                StepResult(false, "点击失败（无障碍服务未连接或手势被拒绝）")
            }
        }

        is Step.LongPress -> {
            val x = jitterCoordinate(step.x, script.jitterRadiusPx)
            val y = jitterCoordinate(step.y, script.jitterRadiusPx)
            if (GestureExecutor.longPress(x, y, step.durationMs)) {
                StepResult(true, "长按成功")
            } else {
                StepResult(false, "长按失败（无障碍服务未连接或手势被拒绝）")
            }
        }

        // 滑动不做坐标扰动。
        is Step.Swipe -> {
            if (GestureExecutor.swipe(step.x1, step.y1, step.x2, step.y2, step.durationMs)) {
                StepResult(true, "滑动成功")
            } else {
                StepResult(false, "滑动失败（无障碍服务未连接或手势被拒绝）")
            }
        }

        // 输入不做坐标扰动。
        is Step.Input -> {
            if (GlobalActions.inputText(step.text)) {
                StepResult(true, "输入成功")
            } else {
                StepResult(false, "输入失败（未找到输入焦点）")
            }
        }

        is Step.Wait -> {
            awaitInterruptible(jitterDelay(step.durationMs, script.jitterDelayPercent))
            StepResult(true, "等待完成")
        }

        // 启动应用不做坐标扰动。
        is Step.LaunchApp -> launchApp(step.packageName)

        is Step.WaitForElement -> waitForElement(step)

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

        is Step.TapElement -> tapElement(script, step)

        is Step.ImageTap -> imageTap(script, step)

        is Step.ColorTap -> colorTap(script, step)
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

    private suspend fun waitForElement(step: Step.WaitForElement): StepResult {
        val selector = NodeSelector(
            text = step.text,
            viewId = step.viewId,
            contentDesc = step.contentDesc,
            className = step.className
        )
        val node = NodeFinder.awaitNode(selector, step.timeoutMs)
        val found = node != null
        recycleNode(node)
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

    private suspend fun tapElement(script: Script, step: Step.TapElement): StepResult {
        val selector = NodeSelector(
            text = step.text,
            viewId = step.viewId,
            contentDesc = step.contentDesc,
            className = step.className,
            index = step.index
        )
        val node = NodeFinder.awaitNode(selector, step.timeoutMs)
            ?: return timeoutResult(step.onTimeout, "智能定位超时")
        val center = NodeFinder.centerOf(node)
        recycleNode(node)
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

    /** 安全回收无障碍节点，重复回收或已回收时忽略异常。 */
    @Suppress("DEPRECATION")
    private fun recycleNode(node: AccessibilityNodeInfo?) {
        try {
            node?.recycle()
        } catch (e: Exception) {
            // 忽略回收异常
        }
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