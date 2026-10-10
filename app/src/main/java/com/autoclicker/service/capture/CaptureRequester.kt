package com.autoclicker.service.capture

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.ActivityResultLauncher
import kotlinx.coroutines.CompletableDeferred

/**
 * 屏幕采集授权控制器：给 Activity 接入的小控制器（普通类，非 Compose 依赖）。
 *
 * MediaProjection 的 createScreenCaptureIntent 授权只能由 Activity 通过
 * [androidx.activity.result.ActivityResultLauncher] 发起。本类封装固定三步：
 * 1. [bind]：在 Activity onCreate（STARTED 之前）注册授权结果 launcher；
 * 2. [request]：每次都重新取 createScreenCaptureIntent 并弹系统授权窗（禁止复用旧 Intent）；
 * 3. [awaitBitmap]：挂起等待本次授权对应的服务会话返回全屏 Bitmap。
 *
 * 典型接入（单 Activity + Compose）：
 * ```
 * // MainActivity.onCreate 中
 * val captureRequester = CaptureRequester()
 * captureRequester.bind(this)
 *
 * // 协程内（如点击「截取屏幕」）
 * captureRequester.request()
 * val bitmap = captureRequester.awaitBitmap()
 * ```
 *
 * Compose 中可 `remember { CaptureRequester() }` 并在 Activity 作用域 bind；
 * 连续两次调用必须各自重新授权，两者都会成功（令牌状态在每次会话结束即清空）。
 */
class CaptureRequester {

    private var host: ComponentActivity? = null
    private var launcher: ActivityResultLauncher<android.content.Intent>? = null

    /** 本次采集的结果挂起点；[request] 时新建，完成后由 [awaitBitmap] 清空 */
    private var pending: CompletableDeferred<Bitmap>? = null

    /**
     * 绑定 Activity 并注册授权 launcher。
     *
     * 注意：registerForActivityResult 必须在 Activity 到达 STARTED 之前调用
     * （通常在 onCreate / 初始化阶段）。重复绑定会先解除旧绑定。
     */
    fun bind(activity: ComponentActivity) {
        unbind()
        host = activity
        launcher = activity.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result -> handleResult(result) }
    }

    /**
     * 解除绑定：若仍有进行中的采集，以中文异常取消。
     * 可在 Activity onDestroy 调用。
     */
    fun unbind() {
        pending?.takeIf { it.isActive }
            ?.completeExceptionally(CaptureException("页面已关闭，屏幕采集被取消"))
        pending = null
        launcher = null
        host = null
    }

    /**
     * 发起一次全新的屏幕采集授权（弹出系统确认窗）。
     *
     * 每次调用都重新获取 createScreenCaptureIntent，绝不复用旧令牌；
     * 同一时刻只允许一个进行中的采集。
     *
     * @throws CaptureException 未绑定、已有采集中等调用错误（中文消息）
     */
    fun request() {
        val activity = host
            ?: throw CaptureException("采集控制器尚未绑定页面，请先调用 bind")
        val launcher = launcher
            ?: throw CaptureException("采集控制器尚未绑定页面，请先调用 bind")

        if (pending?.isActive == true) {
            throw CaptureException("已有正在进行的屏幕采集，请等待其结束后再试")
        }

        pending = CompletableDeferred()
        val projectionManager =
            activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        launcher.launch(projectionManager.createScreenCaptureIntent())
    }

    /**
     * 挂起等待本次采集结果。
     *
     * @return 全屏 Bitmap（尺寸与 WindowManager.maximumWindowMetrics 一致）
     * @throws CaptureException 授权拒绝/取消、服务启动失败、抓帧超时等（均为中文消息）
     */
    suspend fun awaitBitmap(): Bitmap {
        val current = pending
            ?: throw CaptureException("尚未发起屏幕采集，请先调用 request")
        return try {
            current.await()
        } finally {
            if (!current.isActive && pending === current) {
                pending = null
            }
        }
    }

    /**
     * 处理系统授权结果：
     * - 用户拒绝/取消或未返回 data：本次结果直接以中文异常结束；
     * - 授权通过：登记一次性会话并启动 [ScreenCaptureService]；启动失败同样中文报错，不崩溃。
     */
    private fun handleResult(result: ActivityResult) {
        val current = pending ?: return
        val data = result.data

        if (result.resultCode != Activity.RESULT_OK || data == null) {
            current.takeIf { it.isActive }
                .completeExceptionally(CaptureException("用户取消或拒绝了屏幕录制授权"))
            return
        }

        val activity = host
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            current.takeIf { it.isActive }
                .completeExceptionally(CaptureException("页面已关闭，无法完成屏幕采集"))
            return
        }

        // 1) 进程内登记一次性授权结果（仅服务可消费一次）
        CaptureCoordinator.attach(result.resultCode, data, current)
        // 2) 启动前台服务；启动调用本身失败（系统限制等）时让会话失败结束
        try {
            ScreenCaptureService.start(activity)
        } catch (t: Throwable) {
            CaptureCoordinator.failCurrent("无法启动屏幕采集服务：${t.message ?: "未知错误"}")
        }
    }
}
