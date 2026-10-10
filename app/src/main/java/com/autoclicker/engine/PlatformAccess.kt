package com.autoclicker.engine

import com.autoclicker.di.ServiceLocator
import com.autoclicker.platform.ColorFinder
import com.autoclicker.platform.GestureExecutor
import com.autoclicker.platform.GlobalActions
import com.autoclicker.platform.ImageFinder
import com.autoclicker.platform.NodeLocator
import com.autoclicker.platform.OcrEngine
import com.autoclicker.platform.ScreenSource

/**
 * 运行期平台能力访问。能力可能尚未注册（返回 null），调用方必须安全处理。
 */
internal object Platform {
    fun gesture(): GestureExecutor? = ServiceLocator.gestureExecutor

    fun global(): GlobalActions? = ServiceLocator.globalActions

    fun nodes(): NodeLocator? = ServiceLocator.nodeLocator

    fun screen(): ScreenSource? = ServiceLocator.screenSource

    fun images(): ImageFinder? = ServiceLocator.imageFinder

    fun colors(): ColorFinder? = ServiceLocator.colorFinder

    fun ocr(): OcrEngine? = ServiceLocator.ocrEngine
}