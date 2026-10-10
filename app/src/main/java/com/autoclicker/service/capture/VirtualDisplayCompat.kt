package com.autoclicker.service.capture

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Handler
import android.view.Surface

/**
 * 创建镜像 VirtualDisplay 的兼容封装。
 *
 * 为什么需要：compileSdk 35 的桩中旧的 7 参重载已不可直接调用（编译报错），
 * 而 8 参（带 Handler）重载在不同系统版本上可用性不同。
 *
 * 策略：运行时枚举系统框架中 MediaProjection 的真实方法——
 * 优先 8 参重载，不存在时回退 7 参（反射）。
 * 整个会话只创建一次（Android 14+ 对令牌单次使用的硬约束由调用方保证）。
 */
internal fun createVirtualDisplayCompat(
    projection: MediaProjection,
    name: String,
    width: Int,
    height: Int,
    densityDpi: Int,
    surface: Surface,
    handler: Handler?,
): VirtualDisplay {
    val methods = projection.javaClass.methods.filter { it.name == METHOD_NAME }

    // 1) 优先 8 参（..., Surface, Callback, Handler）
    val eightArg = methods.firstOrNull { it.parameterTypes.size == 8 }
    if (eightArg != null) {
        return eightArg.invoke(
            projection,
            name,
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null, // VirtualDisplay.Callback
            handler,
        ) as VirtualDisplay
    }

    // 2) 回退 7 参（..., Surface, Callback）
    val sevenArg = methods.firstOrNull { it.parameterTypes.size == 7 }
        ?: throw IllegalStateException("当前系统不支持 MediaProjection.createVirtualDisplay")
    return sevenArg.invoke(
        projection,
        name,
        width,
        height,
        densityDpi,
        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
        surface,
        null,
    ) as VirtualDisplay
}

private const val METHOD_NAME = "createVirtualDisplay"
