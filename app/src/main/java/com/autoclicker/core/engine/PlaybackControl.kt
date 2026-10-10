package com.autoclicker.core.engine

/**
 * 回放期间的暂停/继续控制器。
 *
 * 引擎在每个步骤之间检查 [isPaused]；暂停时挂起等待，直到 resume。
 * "停止"不经由此类——由取消运行 [kotlinx.coroutines.CoroutineScope] 的 Job 实现
 * （结构化取消，保证延时等待被立即打断）。
 */
class PlaybackControl {

    @Volatile
    private var paused: Boolean = false

    /** 当前是否处于暂停态 */
    fun isPaused(): Boolean = paused

    /** 请求暂停：当前步骤执行完后挂起 */
    fun pause() {
        paused = true
    }

    /** 请求继续 */
    fun resume() {
        paused = false
    }
}
