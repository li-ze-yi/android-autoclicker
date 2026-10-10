package com.autoclicker.core.automation

import com.autoclicker.MyApplication
import com.autoclicker.core.engine.GestureExecutor
import com.autoclicker.core.engine.PackageResolver
import com.autoclicker.core.engine.PlaybackControl
import com.autoclicker.core.engine.PlaybackException
import com.autoclicker.core.engine.ScriptPlayer
import com.autoclicker.domain.model.RepeatPolicy
import com.autoclicker.domain.model.TargetScriptMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 自动化总协调器：连接「目标配置 → 脚本 → 回放引擎 → 状态总线」。
 *
 * 由 MyApplication 装配为进程单例。悬浮窗/界面只调用本类的 start/pause/resume/stop。
 */
class AutomationCoordinator(private val app: MyApplication) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val playbackControl = PlaybackControl()

    private val player = ScriptPlayer(
        gestureExecutor = gestureExecutor,
        packageResolver = packageResolver,
    )

    /** 当前回放 Job（用于停止时立即取消） */
    private var playbackJob: Job? = null

    /**
     * 由主页设置的期望重复策略；悬浮窗播放按钮未显式传参时使用该策略。
     */
    @Volatile
    var desiredPolicy: RepeatPolicy = RepeatPolicy.UntilStopped

    /** 使用 [desiredPolicy] 启动（悬浮窗播放入口） */
    fun startWithDesiredPolicy(): Boolean = startTargets(desiredPolicy)

    /**
     * 按当前屏幕目标启动回放。
     *
     * @param policy 全局重复策略（次数/总时长/直到停止）
     * @return true=已启动；false=无法启动（原因为空目标等，已通过总线发中文提示）
     */
    fun startTargets(policy: RepeatPolicy): Boolean {
        val targets = app.targetController.targets.value
        if (targets.isEmpty()) {
            scope.launch { app.bus.publishEvent("请先点击 ＋ 添加点击或滑动目标") }
            return false
        }
        val script = TargetScriptMapper.toScript(
            scriptId = LIVE_SCRIPT_ID,
            name = "当前配置",
            targets = targets,
            policy = policy,
        )
        // 确保暂停态复位
        playbackControl.resume()
        playbackJob = scope.launch {
            try {
                app.bus.start(LIVE_SCRIPT_ID)
                player.play(script, playbackControl)
            } catch (e: PlaybackException) {
                app.bus.publishEvent(e.message ?: "执行失败")
            } finally {
                playbackControl.resume()
                if (app.bus.engineState.value != com.autoclicker.core.bus.EngineState.Idle) {
                    runCatching { app.bus.stop() }
                }
            }
        }
        return true
    }

    /** 暂停：当前手势结束后挂起 */
    fun pause() {
        playbackControl.pause()
        scope.launch { runCatching { app.bus.pause() } }
    }

    /** 继续 */
    fun resume() {
        playbackControl.resume()
        scope.launch { runCatching { app.bus.resume() } }
    }

    /** 停止：取消回放 Job（延时立即中断），状态回空闲 */
    fun stop() {
        playbackControl.resume()
        playbackJob?.cancel()
        playbackJob = null
        scope.launch { runCatching { app.bus.stop() } }
    }

    private companion object {
        /** 目标直配模式使用的虚拟脚本 ID */
        const val LIVE_SCRIPT_ID = "__live_targets__"
    }
}

/**
 * 引擎依赖装配点（后续替换为真实实现/DI）。
 */

private val gestureExecutor: GestureExecutor
    get() = com.autoclicker.service.accessibility.AndroidGestureExecutor()

private val packageResolver: PackageResolver
    // 函数包仓库在 Task 12A 接入；此前调用包步骤会在引擎内报"函数包不存在"
    get() = PackageResolver { null }
