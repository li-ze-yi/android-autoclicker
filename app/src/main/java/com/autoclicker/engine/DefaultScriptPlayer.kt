package com.autoclicker.engine

import android.content.Context
import com.autoclicker.core.bus.LogLevel
import com.autoclicker.core.bus.PlaybackState
import com.autoclicker.core.bus.RuntimeBus
import com.autoclicker.core.bus.StepExecutionInfo
import com.autoclicker.di.ServiceLocator
import com.autoclicker.domain.model.CallFunctionAction
import com.autoclicker.domain.model.LaunchType
import com.autoclicker.domain.model.Script
import com.autoclicker.domain.model.StepNode
import com.autoclicker.platform.PlaybackController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * 默认脚本播放器：在 [Dispatchers.Default] 上异步执行编译后的执行计划，
 * 通过协程挂起实现暂停、通过取消实现停止。状态与日志写入 [RuntimeBus]。
 */
class DefaultScriptPlayer(context: Context) : PlaybackController {

    private val appContext: Context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val paused = MutableStateFlow(false)
    private val runToken = AtomicLong(0L)
    private var job: Job? = null

    // 仅按需读取仓库，避免构造期强依赖 ServiceLocator 初始化。
    private val scripts by lazy { ServiceLocator.scripts }
    private val packages by lazy { ServiceLocator.packages }

    private val gate: PauseGate = object : PauseGate {
        override suspend fun awaitResume() = this@DefaultScriptPlayer.awaitResume()
        override suspend fun delay(ms: Long) = this@DefaultScriptPlayer.pausableDelay(ms)
    }

    private val executor = ActionExecutor(appContext, gate, ConditionEvaluator(gate)) { action, ctx ->
        invokeFunction(action, ctx)
    }

    override val state: StateFlow<PlaybackState> = RuntimeBus.state

    override suspend fun play(script: Script) {
        val token = runToken.incrementAndGet()
        job?.cancel()
        RuntimeBus.clearLogs()
        RuntimeBus.setCurrentStep(null)
        paused.value = false
        RuntimeBus.setState(PlaybackState.RUNNING)
        val knownScripts = runCatching { scripts.list().size }.getOrDefault(0)
        RuntimeBus.log(LogLevel.INFO, "开始运行：${script.name}（共 ${script.actionCount} 个动作 / 已存 $knownScripts 个任务）")
        job = scope.launch {
            try {
                runScript(script)
                if (runToken.get() == token) RuntimeBus.setState(PlaybackState.IDLE)
            } catch (c: CancellationException) {
                if (runToken.get() == token) RuntimeBus.setState(PlaybackState.STOPPED)
                throw c
            } catch (t: Throwable) {
                if (runToken.get() == token) {
                    RuntimeBus.log(LogLevel.ERROR, "运行异常：${t.message}")
                    RuntimeBus.setState(PlaybackState.ERROR)
                }
            } finally {
                if (runToken.get() == token) {
                    RuntimeBus.setCurrentStep(null)
                    executor.release()
                }
            }
        }
    }

    override fun pause() {
        if (RuntimeBus.state.value == PlaybackState.RUNNING) {
            paused.value = true
            RuntimeBus.setState(PlaybackState.PAUSED)
            RuntimeBus.log(LogLevel.INFO, "已暂停")
        }
    }

    override fun resume() {
        if (RuntimeBus.state.value == PlaybackState.PAUSED) {
            paused.value = false
            RuntimeBus.setState(PlaybackState.RUNNING)
            RuntimeBus.log(LogLevel.INFO, "继续运行")
        }
    }

    override fun stop() {
        paused.value = false
        val current = job
        job = null
        if (current != null && current.isActive) {
            current.cancel()
            RuntimeBus.setCurrentStep(null)
            RuntimeBus.setState(PlaybackState.STOPPED)
            RuntimeBus.log(LogLevel.INFO, "已停止运行")
        }
    }

    // ---------------- 内部执行 ----------------

    private suspend fun runScript(script: Script) {
        prepareLaunch(script)
        val ctx = ExecutionContext()
        val plan = PlaybackPlan.compile(script.nodes)
        RuntimeBus.log("执行计划：${plan.instructions.size} 条指令 / ${plan.stepIndex.size} 个步骤")
        val interpreter = PlaybackInterpreter(plan)
        interpreter.run(
            ctx = ctx,
            gate = gate,
            runner = StepRunner { step, stepCtx -> executor.execute(step, stepCtx) },
            onStep = { step, index, total ->
                RuntimeBus.setCurrentStep(StepExecutionInfo(script.id, script.name, index, total, describe(step)))
            },
        )
        RuntimeBus.log(LogLevel.SUCCESS, "运行结束：${script.name}")
    }

    private suspend fun prepareLaunch(script: Script) {
        when (script.launchType) {
            LaunchType.FROM_HOME -> {
                RuntimeBus.log("启动前回到桌面")
                val global = Platform.global()
                if (global == null) RuntimeBus.log(LogLevel.WARN, "全局操作不可用") else global.home()
            }

            LaunchType.FROM_APP -> {
                val target = script.launchPackage
                if (target.isNullOrBlank()) {
                    RuntimeBus.log(LogLevel.WARN, "未配置启动应用包名，跳过打开应用")
                } else {
                    RuntimeBus.log("启动前打开应用：$target")
                    val global = Platform.global()
                    if (global == null) RuntimeBus.log(LogLevel.WARN, "全局操作不可用") else global.openApp(target, null)
                }
            }

            LaunchType.MANUAL -> Unit
        }
        gate.delay(500)
    }

    private suspend fun invokeFunction(action: CallFunctionAction, ctx: ExecutionContext) {
        if (ctx.callDepth >= MAX_CALL_DEPTH) {
            RuntimeBus.log(LogLevel.ERROR, "函数包调用层数过深，已终止：${action.packageId}")
            return
        }
        val pkg = packages.get(action.packageId)
        if (pkg == null) {
            RuntimeBus.log(LogLevel.ERROR, "函数包不存在：${action.packageId}")
            return
        }
        val args = action.args.mapValues { (_, expr) -> resolveValueExpr(expr, ctx) }
        ctx.pushFrame(args)
        RuntimeBus.log("调用函数包：${pkg.name}")
        try {
            val plan = PlaybackPlan.compile(pkg.nodes)
            PlaybackInterpreter(plan).run(
                ctx = ctx,
                gate = gate,
                runner = StepRunner { step, stepCtx -> executor.execute(step, stepCtx) },
                onStep = { _, _, _ -> },
            )
        } finally {
            val returns = ctx.popFrame()
            action.resultVars.forEach { (returnName, varName) ->
                ctx.set(varName, returns[returnName] ?: "")
            }
        }
    }

    private suspend fun awaitResume() {
        if (paused.value) paused.first { !it }
    }

    private suspend fun pausableDelay(ms: Long) {
        if (ms <= 0) return
        var remaining = ms
        while (remaining > 0) {
            awaitResume()
            val chunk = if (remaining > CHUNK_MS) CHUNK_MS else remaining
            delay(chunk)
            remaining -= chunk
        }
    }

    private fun describe(step: StepNode): String =
        step.note.ifBlank { step.action::class.simpleName ?: "步骤" }

    companion object {
        private const val MAX_CALL_DEPTH = 32
        private const val CHUNK_MS = 50L
    }
}