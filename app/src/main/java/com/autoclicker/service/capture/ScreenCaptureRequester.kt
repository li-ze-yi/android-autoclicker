package com.autoclicker.service.capture

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager

/**
 * 屏幕采集授权辅助：给 UI 层使用，返回可直接交给
 * `ActivityResultContracts.StartActivityForResult` 发起的授权 Intent。
 * 授权成功后把 resultCode 与 data 原样交给 [ScreenCaptureService.start]。
 */
object ScreenCaptureRequester {

    fun buildRequestIntent(context: Context): Intent {
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        return manager.createScreenCaptureIntent()
    }
}