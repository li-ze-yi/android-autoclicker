package com.autoclicker.core.automation

import com.autoclicker.MyApplication
import com.autoclicker.core.data.templates.ImageTemplateRepository
import com.autoclicker.core.data.packages.FunctionPackageRepository
import com.autoclicker.core.engine.GestureExecutor
import com.autoclicker.core.engine.ImageWaiter
import com.autoclicker.core.engine.PackageResolver
import com.autoclicker.core.engine.PlaybackControl
import com.autoclicker.core.engine.PlaybackException
import com.autoclicker.core.engine.ScriptPlayer
import com.autoclicker.core.vision.AndroidImageWaiter
import com.autoclicker.service.capture.ContinuousScreenSource
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

    /**
     * 识图等待器（Task 11，FR-7）：基于持续投屏会话与模板仓库。
     * 仅当脚本含 WaitImage 动作时才会被 ScriptPlayer 使用；
     * 不含识图的脚本不会触发任何投屏相关操作，行为与此前一致。
     */
    private val imageWaiter: ImageWaiter = AndroidImageWaiter(
        source = ContinuousScreenSource,
        repository = ImageTemplateRepository(app),
    )

    private val player = ScriptPlayer(
        gestureExecutor = gestureExecutor,
        packageResolver = createPackageResolver(app),
        imageWaiter = imageWaiter,
        globalActions = com.autoclicker.service.accessibility.AndroidGlobalActions(),
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
            } catch (e: com.autoclicker.core.bus.AutomationCommandException) {
                // 例如录制中/已有任务运行时被再次启动：给中文提示而非崩溃
                app.bus.publishEvent(e.message ?: "无法启动，请先停止当前任务")
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

    /**
     * 开始录制：初始化录制会话并迁移总线到 Recording。
     * @param mode 普通（无障碍事件）/ 精确（全屏触摸捕获）
     */
    fun beginRecording(mode: com.autoclicker.core.bus.RecordMode) {
        app.recorder.begin(mode)
        // S3 修复：精确模式的触摸捕获层由 OverlayService 监听状态挂载，
        // 必须先确保悬浮窗服务运行，否则进入 Recording 后无任何通道捕获触摸。
        if (mode == com.autoclicker.core.bus.RecordMode.Precise) {
            runCatching {
                com.autoclicker.service.overlay.OverlayService.start(
                    app,
                    com.autoclicker.service.overlay.OverlayService.MODE_MULTI,
                )
            }
        }
        scope.launch { runCatching { app.bus.startRecording(mode) } }
    }

    /** 结束录制：总线回空闲，已录步骤保留（供保存/回放） */
    fun stopRecording() {
        app.recorder.end()
        scope.launch { runCatching { app.bus.stopRecording() } }
    }

    /**
     * 直接运行一个完整脚本（录制后"保存并运行"、脚本列表运行入口）。
     * @return true 已启动
     */
    fun startScript(script: com.autoclicker.domain.model.Script): Boolean {
        playbackControl.resume()
        playbackJob = scope.launch {
            try {
                app.bus.start(script.id)
                player.play(script, playbackControl)
            } catch (e: PlaybackException) {
                app.bus.publishEvent(e.message ?: "执行失败")
            } catch (e: com.autoclicker.core.bus.AutomationCommandException) {
                app.bus.publishEvent(e.message ?: "无法启动，请先停止当前任务")
            } finally {
                playbackControl.resume()
                if (app.bus.engineState.value != com.autoclicker.core.bus.EngineState.Idle) {
                    runCatching { app.bus.stop() }
                }
            }
        }
        return true
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

/** I1 修复：按 ID 实时读取函数包仓库（单一数据源） */
private fun createPackageResolver(app: MyApplication): PackageResolver =
    PackageResolver { id ->
        FunctionPackageRepository(app).get(id)
    }
