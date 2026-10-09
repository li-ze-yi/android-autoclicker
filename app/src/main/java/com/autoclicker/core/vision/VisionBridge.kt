package com.autoclicker.core.vision

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 跨组件传递「刚截取、待框选保存」的屏幕截图。 */
object VisionBridge {
    private val _pendingCapture = MutableStateFlow<Bitmap?>(null)
    val pendingCapture: StateFlow<Bitmap?> = _pendingCapture.asStateFlow()

    fun publishCapture(bitmap: Bitmap) {
        _pendingCapture.value = bitmap
    }

    /** 取出并清空。 */
    fun consumeCapture(): Bitmap? {
        val b = _pendingCapture.value
        _pendingCapture.value = null
        return b
    }
}