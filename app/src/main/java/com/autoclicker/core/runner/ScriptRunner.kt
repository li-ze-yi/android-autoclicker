package com.autoclicker.core.runner

import android.content.Context
import android.content.Intent
import com.autoclicker.core.accessibility.GestureExecutor
import com.autoclicker.core.accessibility.GlobalActions
import com.autoclicker.core.accessibility.NodeFinder
import com.autoclicker.core.accessibility.NodeSelector
import com.autoclicker.core.script.OnTimeout
import com.autoclicker.core.script.Script
import com.autoclicker.core.script.Step
import kotlin.coroutines.coroutineContext
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

/** 单步执行结果。success=false 时 message 为失败原因。 */
data class StepResult(val success: Boolean, val message: String? = null)

/**
 * 脚本执行引擎：顺序执行 [Script] 中的步骤，支持暂停 / 恢复 / 停止。
 * 全局单例，状态通过 [state] 暴露给 UI。
 */
object ScriptRunner {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var job: Job? = null

    /** 应用上下文，用于启动 App 步骤。 */
    private var appContext: Context? = null

    /** 手动停止标记，用于在取消回调中区分「手动停止」与「其他取消」。 */
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
            script.steps.forEachIndexed { index, step ->
                coroutineContext.ensureActive()

                if (paused.value) {
                    _state.value = RunnerState.Paused(script.id, script.name, index, total)
                    paused.first { !it }
                }

                _state.value = RunnerState.Running(script.id, script.name, index, total)

                val result = executeStep(step)
                if (!result.success) {
                    if (script.stopOnError) {
                        _state.value = RunnerState.Finished(
                            script.name,
                            false,
                            "第 ${index + 1} 步失败：${result.message ?: "未知错误"}"
                        )
                        return
                    }
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

    private suspend fun executeStep(step: Step): StepResult = when (step) {
        is Step.Tap -> {
            if (GestureExecutor.click(step.x, step.y)) {
                StepResult(true, "点击成功")
            } else {
                StepResult(false, "点击失败（无障碍服务未连接或手势被拒绝）")
            }
        }

        is Step.LongPress -> {
            if (GestureExecutor.longPress(step.x, step.y, step.durationMs)) {
                StepResult(true, "长按成功")
            } else {
                StepResult(false, "长按失败（无障碍服务未连接或手势被拒绝）")
            }
        }

        is Step.Swipe -> {
            if (GestureExecutor.swipe(step.x1, step.y1, step.x2, step.y2, step.durationMs)) {
                StepResult(true, "滑动成功")
            } else {
                StepResult(false, "滑动失败（无障碍服务未连接或手势被拒绝）")
            }
        }

        is Step.Input -> {
            if (GlobalActions.inputText(step.text)) {
                StepResult(true, "输入成功")
            } else {
                StepResult(false, "输入失败（未找到输入焦点）")
            }
        }

        is Step.Wait -> {
            delay(step.durationMs)
            StepResult(true, "等待完成")
        }

        is Step.LaunchApp -> launchApp(step.packageName)

        is Step.WaitForElement -> waitForElement(step)

        is Step.Back -> {
            if (GlobalActions.back()) {
                StepResult(true, "返回成功")
            } else {
                StepResult(false, "返回失败（无障碍服务未连接）")
            }
        }

        is Step.Home -> {
            if (GlobalActions.home()) {
                StepResult(true, "回到主页成功")
            } else {
                StepResult(false, "回到主页失败（无障碍服务未连接）")
            }
        }
    }

    private suspend fun launchApp(packageName: String): StepResult {
        val context = appContext ?: return StepResult(false, "启动应用失败：上下文未初始化")
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return StepResult(false, "未安装：$packageName")
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            delay(1000)
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
        return if (node != null) {
            delay(200)
            StepResult(true, "元素已出现")
        } else if (step.onTimeout == OnTimeout.SKIP) {
            StepResult(true, "元素未出现，已跳过")
        } else {
            StepResult(false, "等待元素超时")
        }
    }
}