package com.autoclicker.service.overlay

import com.autoclicker.core.bus.AutomationBus
import com.autoclicker.core.bus.EngineState
import com.autoclicker.core.targets.TargetController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 悬浮窗 UI 操作抽象：控制面板与目标控件只依赖此接口，不直接绑定回放引擎。
 *
 * Task 7 可注入真实引擎实现（开始/暂停/继续/停止经总线驱动 ScriptPlayer）；
 * 默认使用 [DefaultOverlayController]，目标增删直接转发到 [TargetController]。
 */
interface OverlayController {

    /** 播放（或暂停后的继续） */
    fun onPlay()

    /** 暂停 */
    fun onPause()

    /** 停止 */
    fun onStop()

    /** 当前是否处于暂停态（控制面板据此切换图标与语义） */
    fun isPaused(): Boolean

    /**
     * 添加点击目标（屏幕绝对坐标）。
     * @return 新目标 ID
     */
    fun onAddTap(x: Int, y: Int): String

    /**
     * 添加滑动目标：从 S1 滑到 S2（屏幕绝对坐标）。
     * @return 新目标 ID
     */
    fun onAddSwipe(x1: Int, y1: Int, x2: Int, y2: Int): String

    /**
     * 切换目标控件隐藏/显示。
     * @return 切换后是否为隐藏态
     */
    fun onToggleHidden(): Boolean
}

/**
 * 默认控制器：目标增删直接转发到 [TargetController]；
 * 播放/暂停/停止在当前没有已装载脚本的情况下，经总线执行可执行的迁移并给出中文提示。
 * Task 7 接入真实引擎后可整体替换本实现。
 *
 * @param scope 由 OverlayService 提供的主线程作用域，onDestroy 时统一取消
 */
class DefaultOverlayController(
    private val bus: AutomationBus,
    private val targetController: TargetController,
    private val scope: CoroutineScope,
) : OverlayController {

    override fun onPlay() {
        scope.launch {
            when (bus.engineState.value) {
                EngineState.Paused ->
                    runCatching { bus.resume() }
                        .onFailure { bus.publishEvent(it.message ?: "继续失败") }

                EngineState.Running -> Unit
                EngineState.Idle ->
                    bus.publishEvent("请先在主界面配置目标或脚本，再点播放")

                EngineState.Recording ->
                    bus.publishEvent("正在录制，无法播放")
            }
        }
    }

    override fun onPause() {
        scope.launch {
            if (bus.engineState.value == EngineState.Running) {
                runCatching { bus.pause() }
                    .onFailure { bus.publishEvent(it.message ?: "暂停失败") }
            }
        }
    }

    override fun onStop() {
        scope.launch {
            when (bus.engineState.value) {
                EngineState.Running, EngineState.Paused ->
                    runCatching { bus.stop() }
                        .onFailure { bus.publishEvent(it.message ?: "停止失败") }

                else -> Unit
            }
        }
    }

    override fun isPaused(): Boolean = bus.engineState.value == EngineState.Paused

    override fun onAddTap(x: Int, y: Int): String = targetController.addTap(x, y)

    override fun onAddSwipe(x1: Int, y1: Int, x2: Int, y2: Int): String =
        targetController.addSwipe(x1, y1, x2, y2)

    override fun onToggleHidden(): Boolean {
        val hidden = !targetController.hidden.value
        targetController.setHidden(hidden)
        return hidden
    }
}
