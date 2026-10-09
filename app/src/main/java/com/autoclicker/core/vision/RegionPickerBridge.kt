package com.autoclicker.core.vision

import android.graphics.Rect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 悬浮窗框选「识图搜索区域」的桥接。 */
object RegionPickerBridge {
    private val _result = MutableStateFlow<Rect?>(null)
    val result: StateFlow<Rect?> = _result.asStateFlow()

    /** 开始一次框选：清空上一次结果，等待 [publish]。 */
    fun request() {
        _result.value = null
    }

    fun publish(rect: Rect) {
        _result.value = rect
    }

    /** 取出并清空。 */
    fun consume(): Rect? {
        val r = _result.value
        _result.value = null
        return r
    }
}