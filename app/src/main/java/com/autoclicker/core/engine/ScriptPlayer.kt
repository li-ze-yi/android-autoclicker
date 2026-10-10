package com.autoclicker.core.engine

import com.autoclicker.domain.model.Action
import com.autoclicker.domain.model.RepeatPolicy
import com.autoclicker.domain.model.Script
import com.autoclicker.domain.model.ScriptStep
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * 脚本回放引擎：基于协程递归执行脚本步骤树。
 *
 * 能力：
 * - 脚本级重复策略：[RepeatPolicy.Count]/[RepeatPolicy.UntilTime]/[RepeatPolicy.UntilStopped]；
 * - 步骤树递归：基础动作直接执行、[ScriptStep.LoopGroup] 整体循环、[ScriptStep.PackageCall] 实时解析函数包；
 * - 暂停/继续：经 [PlaybackControl]，在步骤之间检查；
 * - 立即停止：由调用方取消所在协程（延时为可取消挂起）；
 * - 任何手势失败/数据非法均抛 [PlaybackException]（中文消息）。
 *
 * @property gestureExecutor 手势派发器（真机走无障碍，测试可注入假实现）
 * @property packageResolver 函数包解析器
 * @property imageWaiter 识图等待器，null 时遇到 WaitImage 报"功能尚未就绪"
 */
class ScriptPlayer(
    private val gestureExecutor: GestureExecutor,
    private val packageResolver: PackageResolver,
    private val imageWaiter: ImageWaiter? = null,
    private val clock: Clock = SystemClock,
) {

    /**
     * 在调用方给定的协程上下文中执行 [script]，直到重复策略完成或协程被取消。
     *
     * @param control 暂停/继续控制
     * @param listener 步骤事件回调（可选，用于 UI 高亮当前步骤）
     */
    suspend fun play(
        script: Script,
        control: PlaybackControl = PlaybackControl(),
        listener: PlaybackListener? = null,
    ) {
        when (val policy = script.repeatPolicy) {
            is RepeatPolicy.Count -> {
                if (policy.times < 1) throw PlaybackException("脚本重复次数必须 >= 1")
                repeat(policy.times) { pass ->
                    runPass(script, control, listener)
                    listener?.onPassCompleted(pass + 1)
                }
            }

            is RepeatPolicy.UntilTime -> {
                val durationMs = policy.durationMs
                if (durationMs < 0) throw PlaybackException("脚本总时长不能为负")
                val startMs = clock.nowMs()
                val deadline = startMs + durationMs
                var pass = 0
                while (clock.nowMs() < deadline) {
                    coroutineContext.ensureActive()
                    runPass(script, control, listener)
                    pass += 1
                    listener?.onPassCompleted(pass)
                }
            }

            RepeatPolicy.UntilStopped -> {
                var pass = 0
                while (true) {
                    coroutineContext.ensureActive()
                    runPass(script, control, listener)
                    pass += 1
                    listener?.onPassCompleted(pass)
                }
            }
        }
    }

    /** 执行一遍脚本的全部顶层步骤 */
    private suspend fun runPass(
        script: Script,
        control: PlaybackControl,
        listener: PlaybackListener?,
    ) {
        executeSteps(script.steps, control, listener)
    }

    /** 顺序执行一组步骤（循环段/函数包内部复用） */
    private suspend fun executeSteps(
        steps: List<ScriptStep>,
        control: PlaybackControl,
        listener: PlaybackListener?,
        packageChain: List<String> = emptyList(),
    ) {
        for (step in steps) {
            // 停止信号：协程取消点
            coroutineContext.ensureActive()
            // 暂停信号：步骤之间挂起等待
            awaitWhilePaused(control)
            listener?.onStepStarted(step.id)
            executeStep(step, control, listener, packageChain)
        }
    }

    /** 执行单个步骤节点 */
    private suspend fun executeStep(
        step: ScriptStep,
        control: PlaybackControl,
        listener: PlaybackListener?,
        packageChain: List<String>,
    ) {
        when (step) {
            is ScriptStep.BasicStep -> executeAction(step.action, control, listener)

            is ScriptStep.LoopGroup -> {
                if (step.count < 1) {
                    throw PlaybackException("循环段「${step.name}」循环次数必须 >= 1")
                }
                repeat(step.count) { iteration ->
                    coroutineContext.ensureActive()
                    awaitWhilePaused(control)
                    listener?.onLoopIteration(step.id, iteration + 1)
                    executeSteps(step.steps, control, listener, packageChain)
                }
            }

            is ScriptStep.PackageCall -> executePackageCall(step.packageId, control, listener, packageChain)
        }
    }

    /** 执行函数包调用：实时解析、检查深度与环 */
    private suspend fun executePackageCall(
        packageId: String,
        control: PlaybackControl,
        listener: PlaybackListener?,
        packageChain: List<String>,
    ) {
        if (packageId in packageChain) {
            throw PlaybackException(
                "函数包调用链存在环：${(packageChain + packageId).joinToString(" → ")}"
            )
        }
        val nextDepth = packageChain.size + 1
        if (nextDepth > MAX_PACKAGE_DEPTH) {
            throw PlaybackException(
                "函数包调用链深度最多 $MAX_PACKAGE_DEPTH 层，当前已达 $nextDepth 层"
            )
        }
        val pkg = packageResolver.resolve(packageId)
            ?: throw PlaybackException("函数包（$packageId）不存在，可能已被删除")
        listener?.onPackageEntered(pkg.id)
        executeSteps(pkg.steps, control, listener, packageChain + packageId)
    }

    /** 执行基础动作 */
    private suspend fun executeAction(
        action: Action,
        control: PlaybackControl,
        listener: PlaybackListener?,
    ) {
        when (action) {
            is Action.Tap ->
                gestureExecutor.tap(action.x, action.y).requireCompleted("点击(${action.x}, ${action.y})")

            is Action.LongPress ->
                gestureExecutor.longPress(action.x, action.y, action.durationMs)
                    .requireCompleted("长按(${action.x}, ${action.y})")

            is Action.Swipe ->
                gestureExecutor.swipe(action.x1, action.y1, action.x2, action.y2, action.durationMs)
                    .requireCompleted("滑动(${action.x1}, ${action.y1})→(${action.x2}, ${action.y2})")

            is Action.Delay -> {
                if (action.durationMs < 0) throw PlaybackException("延时时长不能为负")
                delay(action.durationMs)
            }

            is Action.WaitImage -> {
                val waiter = imageWaiter
                    ?: throw PlaybackException("识图功能尚未就绪")
                val result = waiter.await(action)
                if (result is ImageAwaitResult.Found && action.tapWhenFound) {
                    gestureExecutor.tap(result.x, result.y)
                        .requireCompleted("点击识图匹配点(${result.x}, ${result.y})")
                }
                if (result is ImageAwaitResult.Timeout) {
                    listener?.onWaitImageTimeout(action.templateId)
                }
            }
        }
    }

    /** 暂停态下挂起；使用短轮询以便 resume 及时生效 */
    private suspend fun awaitWhilePaused(control: PlaybackControl) {
        while (control.isPaused()) {
            coroutineContext.ensureActive()
            delay(PAUSE_POLL_MS)
        }
    }

    /** 手势结果非成功时统一转 PlaybackException */
    private fun GestureResult.requireCompleted(actionLabel: String) {
        when (this) {
            GestureResult.Completed -> Unit
            GestureResult.Cancelled ->
                throw PlaybackException("$actionLabel 被取消")
            is GestureResult.Failed ->
                throw PlaybackException("$actionLabel 失败：$reason")
        }
    }

    private companion object {
        /** 函数包调用最大深度（与 StructureValidator 保持一致） */
        const val MAX_PACKAGE_DEPTH = 3

        /** 暂停轮询间隔（毫秒） */
        const val PAUSE_POLL_MS = 50L
    }
}

/**
 * 回放过程事件监听器（UI 可据此高亮/记录）。所有方法默认空实现。
 */
interface PlaybackListener {
    /** 一个步骤即将执行 */
    fun onStepStarted(stepId: String) {}

    /** 一遍脚本执行完成 */
    fun onPassCompleted(pass: Int) {}

    /** 循环段进入第 [iteration] 次 */
    fun onLoopIteration(groupId: String, iteration: Int) {}

    /** 进入函数包 */
    fun onPackageEntered(packageId: String) {}

    /** WaitImage 超时 */
    fun onWaitImageTimeout(templateId: String) {}
}
