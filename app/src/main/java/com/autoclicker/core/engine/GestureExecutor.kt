package com.autoclicker.core.engine

/**
 * 手势派发结果。
 */
sealed interface GestureResult {
    /** 手势成功派发并被系统完成 */
    data object Completed : GestureResult

    /**
     * 手势派发失败。
     * @property reason 中文原因，可用于日志/提示
     */
    data class Failed(val reason: String) : GestureResult

    /** 手势在执行中被取消（如脚本停止） */
    data object Cancelled : GestureResult
}

/**
 * 手势派发器：引擎层只依赖该接口，把屏幕坐标手势交给底层执行。
 *
 * 真实实现走无障碍服务 dispatchGesture（见 AndroidGestureExecutor）；
 * 单元测试可注入假实现记录调用序列。
 *
 * 所有方法为挂起函数且在后台线程完成，禁止在主线程阻塞等待。
 */
interface GestureExecutor {

    /** 在 (x, y) 处单击；[holdMs] 为按下时长（快速点击一般 1~50ms） */
    suspend fun tap(x: Int, y: Int, holdMs: Long = DEFAULT_TAP_HOLD_MS): GestureResult

    /** 在 (x, y) 处按住 [durationMs] 毫秒（长按） */
    suspend fun longPress(x: Int, y: Int, durationMs: Long): GestureResult

    /** 从 (x1, y1) 滑动到 (x2, y2)，耗时 [durationMs] 毫秒 */
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): GestureResult
}

/** 快速点击默认按下时长（毫秒） */
const val DEFAULT_TAP_HOLD_MS: Long = 1L
