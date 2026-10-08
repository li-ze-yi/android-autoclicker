package com.autoclicker.core.overlay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 一次坐标拾取结果（屏幕原始坐标）。 */
data class PickPoint(val x: Float, val y: Float)

/**
 * 坐标拾取结果的进程内桥接：悬浮窗覆盖层拾取到坐标后写入，
 * 由 UI 订阅 [picked] 消费并调用 [clear] 清除。
 */
object PickPointBridge {

    private val _picked = MutableStateFlow<PickPoint?>(null)
    val picked: StateFlow<PickPoint?> = _picked.asStateFlow()

    /** 由 OverlayService 在拾取到坐标时调用。 */
    fun notifyPicked(point: PickPoint) {
        _picked.value = point
    }

    /** UI 消费后清除。 */
    fun clear() {
        _picked.value = null
    }
}