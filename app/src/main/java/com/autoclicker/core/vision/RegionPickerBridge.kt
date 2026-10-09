package com.autoclicker.core.vision

import android.graphics.Rect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 悬浮窗框选「识图搜索区域」的桥接。 */
object RegionPickerBridge {
    /** 正在为哪个步骤框选（步骤 id）。 */
    @Volatile
    var targetStepId: String? = null

    private val _result = MutableStateFlow<Rect?>(null)
    val result: StateFlow<Rect?> = _result.asStateFlow()

    fun request(stepId: String) {
        targetStepId = stepId
        _result.value = null
    }

    fun publish(rect: Rect) {
        _result.value = rect
    }

    /** 取出并清空；同时清掉 targetStepId。 */
    fun consume(): Rect? {
        val r = _result.value
        _result.value = null
        targetStepId = null
        return r
    }

    fun clear() {
        _result.value = null
        targetStepId = null
    }
}