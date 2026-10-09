package com.autoclicker.core.vision

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * 截屏授权页：申请 MediaProjection 权限，授权成功后启动 [ScreenCaptureService]。
 *
 * 采用静态 [request] 启动，context 可能不是 Activity，因此带上 NEW_TASK 标志。
 * 页面本身不设置布局（使用透明主题），仅做授权与结果反馈。
 */
class CapturePermissionActivity : ComponentActivity() {

    companion object {
        /** 请求截屏授权（内部以 Toast 反馈结果）。 */
        fun request(context: Context) {
            try {
                context.startActivity(
                    Intent(context, CapturePermissionActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                // 忽略启动异常
            }
        }
    }

    // 成员属性，在 onCreate 之前完成注册。
    private val launcher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                ScreenCaptureService.start(this, result.resultCode, data)
                Toast.makeText(this, "已授权截屏", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "已取消截屏授权", Toast.LENGTH_SHORT).show()
            }
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            launcher.launch(manager.createScreenCaptureIntent())
        } catch (e: Exception) {
            Toast.makeText(this, "已取消截屏授权", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}