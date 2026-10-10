package com.autoclicker.vision

import android.content.Context
import com.autoclicker.di.ServiceLocator

/**
 * 图像/识别层装配入口：把本层的实现注册进 [ServiceLocator] 的运行期能力槽位。
 *
 * 由应用启动流程在合适时机调用一次即可（重复调用会覆盖为新的无状态实例，无副作用）。
 */
object VisionModule {

    @Suppress("UNUSED_PARAMETER")
    fun install(context: Context) {
        ServiceLocator.imageFinder = TemplateImageFinder(ServiceLocator.templates)
        ServiceLocator.colorFinder = PixelColorFinder()
        ServiceLocator.ocrEngine = NodeTextOcrEngine { ServiceLocator.nodeLocator }
    }
}