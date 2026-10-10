package com.autoclicker.service.capture

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.content.pm.ServiceInfo
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.autoclicker.R
import com.autoclicker.core.notify.NotificationChannels
import com.autoclicker.service.overlay.ScreenMetrics
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.cancellation.CancellationException

/**
 * 屏幕采集前台服务（FR-7，Task 10）。
 *
 * 单次会话生命周期（严格遵守 Android 14/15 约束）：
 * 1. [onStartCommand] 先以 foregroundServiceType=mediaProjection 调用 startForeground
 *    （必须先于 getMediaProjection），再从 [CaptureCoordinator] **一次性**消费授权结果；
 * 2. 后台线程启动 HandlerThread「screen-capture」，创建
 *    [ImageReader]（[PixelFormat.RGBA_8888]），getMediaProjection 后注册
 *    [MediaProjection.Callback]，并**仅创建一次** createVirtualDisplay；
 * 3. ImageReader 回调取到第一帧有效画面 → 转为 [Bitmap]（正确处理 rowStride/pixelStride）；
 * 4. 无论成功失败，finally 中依次释放 VirtualDisplay → ImageReader → 注销回调 →
 *    MediaProjection.stop() → HandlerThread，最后 stopSelf。
 *
 * 注意：
 * - 同一个 MediaProjection 只 createVirtualDisplay 一次；服务结束即停止，不复用任何令牌；
 * - 服务不接受 resultCode/data extras，统一经进程内 [CaptureCoordinator] 传递；
 * - 不做手势派发；异常消息一律中文，经结果挂起点返回，不崩溃。
 */
class ScreenCaptureService : Service() {

    /** 后台协程作用域（Default 调度器，全部投影操作在此完成） */
    private var scope: CoroutineScope? = null

    /** 本次授权与结果（服务消费后持有，完成后清空） */
    private var authorization: CaptureCoordinator.Authorization? = null

    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var worker: HandlerThread? = null

    /** 资源释放是否已执行（幂等保护） */
    @Volatile
    private var released: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 1) 先以前台服务身份启动：Android 14+ 要求 startForeground 先于 getMediaProjection
        try {
            startAsForeground()
        } catch (t: Throwable) {
            // startForeground 失败（权限/系统限制）：让待消费会话带中文错误结束
            CaptureCoordinator.failCurrent("无法启动屏幕采集服务：${t.message ?: "未知错误"}")
            stopSelf()
            return START_NOT_STICKY
        }

        // 2) 一次性消费授权结果；为空说明是重复/陈旧启动，安静退出即可
        val auth = CaptureCoordinator.consume()
        if (auth == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        authorization = auth

        // 3) 全部投影工作放后台线程，避免任何主线程阻塞（NFR-1）
        val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = sessionScope
        sessionScope.launch {
            try {
                captureFirstFrame(auth)
            } catch (e: TimeoutCancellationException) {
                // 注意：TimeoutCancellationException 是 CancellationException 子类，必须先于其捕获
                failAuth(auth, "获取屏幕画面超时，请重试")
            } catch (c: CancellationException) {
                failAuth(auth, "屏幕采集已取消")
                throw c
            } catch (e: CaptureException) {
                failAuth(auth, e.message ?: "屏幕采集失败")
            } catch (t: Throwable) {
                failAuth(auth, "屏幕采集失败：${t.message ?: "未知错误"}")
            } finally {
                // 结构化收尾：即使协程被取消，也必须在 NonCancellable 中把资源释放跑完
                withContext(NonCancellable) {
                    releaseResources()
                    stopSelf()
                }
            }
        }
        // 不使用 sticky：服务不自动恢复，每次采集均由用户重新授权后显式启动
        return START_NOT_STICKY
    }

    /**
     * 以前台服务方式启动：通知渠道为低重要性 [NotificationChannels.CHANNEL_RUNNING]。
     * API 29+ 需显式传入 mediaProjection 前台服务类型；低版本传 0（清单类型在旧系统被忽略）。
     */
    private fun startAsForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
    }

    /** 低重要性、静默的采集状态通知 */
    private fun buildNotification(): Notification {
        // 渠道已在 MyApplication.onCreate 由 NotificationChannels.ensure 创建
        return NotificationCompat.Builder(this, NotificationChannels.CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("正在截取屏幕")
            .setContentText("用于识图模板采集")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * 完成一次「建读取器 → 建投影 → 仅一次虚拟显示 → 等首帧」流程。
     * 成功时把 [Bitmap] 写入 [auth] 的结果挂起点。
     */
    private suspend fun captureFirstFrame(auth: CaptureCoordinator.Authorization) {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        // 屏幕尺寸统一走 maximumWindowMetrics（与投影输出、raw 坐标同一空间）
        val width = ScreenMetrics.width(wm)
        val height = ScreenMetrics.height(wm)
        val densityDpi = resources.displayMetrics.densityDpi

        val thread = HandlerThread(WORKER_THREAD_NAME).apply { start() }
        worker = thread
        val handler = Handler(thread.looper)

        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = try {
            projectionManager.getMediaProjection(auth.resultCode, auth.data)
        } catch (e: SecurityException) {
            // 令牌被系统判定失效（如异常复用）：明确提示重新授权
            throw CaptureException("屏幕录制授权已失效，请重新授权")
        }
        this.projection = projection

        // 首帧到达信号（仅表示投影开始出帧）；投影被系统停止时以此异常打断等待
        val firstFrame = CompletableDeferred<Unit>()
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                firstFrame.takeIf { it.isActive }
                    ?.completeExceptionally(CaptureException("屏幕采集会话被系统停止"))
            }
        }
        projectionCallback = callback
        projection.registerCallback(callback, handler)

        val reader = ImageReader.newInstance(
            width,
            height,
            PixelFormat.RGBA_8888,
            MAX_IMAGES,
        )
        imageReader = reader
        // 持续保存最近一帧：首帧可能为黑帧/合成中帧，稳定期后取最新帧更可靠
        @Volatile
        var latestBitmap: Bitmap? = null
        reader.setOnImageAvailableListener({ r ->
            val image = runCatching { r.acquireLatestImage() }.getOrNull()
                ?: return@setOnImageAvailableListener
            val bitmap = runCatching { image.toBitmap(width, height) }
                .also { runCatching { image.close() } }
                .getOrNull()
                ?: return@setOnImageAvailableListener
            // 替换并回收旧帧（此时结果尚未提交，外部无引用）
            val old = latestBitmap
            latestBitmap = bitmap
            old?.takeIf { !it.isRecycled }?.recycle()
            firstFrame.takeIf { it.isActive }?.complete(Unit)
        }, handler)

        // 整个会话只允许创建一次 VirtualDisplay（Android 14+ 硬约束）。
        // 带 Handler 的重载为 API 33+；低版本用 7 参重载。
        virtualDisplay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            projection.createVirtualDisplay(
                VIRTUAL_DISPLAY_NAME,
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                handler,
            )
        } else {
            @Suppress("DEPRECATION")
            projection.createVirtualDisplay(
                VIRTUAL_DISPLAY_NAME,
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
            )
        }

        // 等待首帧出图，最长 FRAME_TIMEOUT_MS，避免异常设备永久挂起
        withTimeout(FRAME_TIMEOUT_MS) { firstFrame.await() }
        // 稳定期：等合成器再刷若干帧，避开首帧黑屏/半合成帧
        delay(FRAME_SETTLE_MS)
        val bitmap = latestBitmap
            ?: throw CaptureException("屏幕画面为空，请重试")
        auth.result.takeIf { it.isActive }?.complete(bitmap)
    }

    /** 以中文 [CaptureException] 结束本次会话结果（若尚未完成） */
    private fun failAuth(auth: CaptureCoordinator.Authorization, message: String) {
        auth.result.takeIf { it.isActive }?.completeExceptionally(CaptureException(message))
    }

    /**
     * 释放全部采集资源（幂等）：
     * VirtualDisplay → ImageReader → 注销回调 → MediaProjection.stop() → HandlerThread。
     * 每一步单独兜底，任何一步失败都不影响后续释放。
     */
    private fun releaseResources() {
        if (released) return
        released = true

        runCatching { virtualDisplay?.release() }
        virtualDisplay = null

        runCatching { imageReader?.setOnImageAvailableListener(null, null) }
        runCatching { imageReader?.close() }
        imageReader = null

        val p = projection
        val cb = projectionCallback
        if (p != null && cb != null) {
            runCatching { p.unregisterCallback(cb) }
        }
        runCatching { p?.stop() }
        projection = null
        projectionCallback = null

        worker?.quitSafely()
        worker = null
        authorization = null
    }

    override fun onDestroy() {
        // 系统回收/异常销毁：取消协程并兜底释放（正常路径已在 finally 释放，此处幂等）
        scope?.cancel()
        releaseResources()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 0x7101

        /** ImageReader 缓存帧数（首帧采集，2 帧足够） */
        private const val MAX_IMAGES = 2

        /** 等待首帧超时时间（毫秒） */
        private const val FRAME_TIMEOUT_MS = 3_000L

        /** 首帧到达后的画面稳定时间（毫秒），用于跳过黑帧/半合成帧 */
        private const val FRAME_SETTLE_MS = 150L

        private const val WORKER_THREAD_NAME = "screen-capture"
        private const val VIRTUAL_DISPLAY_NAME = "auto-clicker-capture"

        /**
         * 启动屏幕采集服务（前台服务方式）。
         * 必须在用户本次授权成功、[CaptureCoordinator.attach] 之后调用。
         */
        fun start(context: Context) {
            val intent = Intent(context, ScreenCaptureService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}

/**
 * 把 [ImageReader] 的一帧 RGBA 图像转为 [Bitmap]。
 *
 * 投影帧每行可能带对齐填充（rowStride > width * pixelStride），
 * 因此先按 rowStride / pixelStride 建位图再 copyPixelsFromBuffer，
 * 若宽度大于期望宽度，裁掉右侧填充区域，保证输出尺寸恰为屏幕尺寸。
 */
private fun Image.toBitmap(expectedWidth: Int, expectedHeight: Int): Bitmap {
    val plane = planes[0]
    val buffer = plane.buffer
    val pixelStride = plane.pixelStride
    val rowStride = plane.rowStride

    // 含行尾填充时的实际位图宽度
    val paddedWidth = rowStride / pixelStride
    val raw = Bitmap.createBitmap(paddedWidth, expectedHeight, Bitmap.Config.ARGB_8888)
    // 复位到缓冲区起点再拷贝，避免位置非 0 导致读不全
    buffer.rewind()
    raw.copyPixelsFromBuffer(buffer)

    return if (paddedWidth == expectedWidth) {
        raw
    } else {
        // 裁掉右侧因行对齐产生的填充列
        Bitmap.createBitmap(raw, 0, 0, expectedWidth, expectedHeight).also {
            if (it !== raw) raw.recycle()
        }
    }
}
