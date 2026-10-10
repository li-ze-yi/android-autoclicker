package com.autoclicker.core.engine

import com.autoclicker.domain.model.Action

/**
 * 识图等待器：执行 [Action.WaitImage] 时在屏幕上轮询匹配模板。
 *
 * 本期引擎不默认提供实现（Task 11 接入真实实现）；
 * 未注入时遇到 WaitImage 动作报"功能尚未就绪"。
 */
interface ImageWaiter {

    /**
     * 在 [action.timeoutMs] 内等待模板出现。
     * @return 匹配结果（成功坐标或超时）
     */
    suspend fun await(action: Action.WaitImage): ImageAwaitResult
}

/**
 * WaitImage 等待结果。
 */
sealed interface ImageAwaitResult {
    /**
     * 模板已找到。
     * @property x 匹配点屏幕 x
     * @property y 匹配点屏幕 y
     */
    data class Found(val x: Int, val y: Int) : ImageAwaitResult

    /** 超时未找到 */
    data object Timeout : ImageAwaitResult
}
