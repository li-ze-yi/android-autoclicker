package com.autoclicker.service.capture

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.autoclicker.R
import com.autoclicker.core.notify.NotificationChannels
import com.autoclicker.service.overlay.ScreenMetrics

/**
 * 持续屏幕帧来源（FR-7 / Task 11）。
 *
 * 与单次抓帧的 [ScreenCaptureService] 不同，本类保持**一次** MediaProjection 授权
 * 持续出帧：同一个 [MediaProjection] + 仅一次 createVirtualDisplay +
 * [ImageReader.acquireLatestImage] 持续取最新帧，供识图等待（AndroidImageWaiter）轮询。
 *
 * 生命周期：
 * 1. UI 通过授权 launcher 取得 resultCode/data 后调用 [start]；
 * 2. [ContinuousCaptureService] 先 startForeground(mediaProjection) 再一次性消费令牌、
 *    getMediaProjection、createVirtualDisplay；
 * 3. 采集线程每收到一帧 → [publishFrame]（替换并回收旧帧，帧版本号自增）；
 * 4. 调用 [stop] 或投影被系统停止 → 按顺序释放并 [markInactive]。
 *
 * 本类为 object，但所有可变状态均为 private 且经 [lock] 串行访问，
 * 是持续投屏唯一被允许的会话持有点（与 [CaptureCoordinator] 同性质），
 * 不属于散落静态状态。
 */
object ContinuousScreenSource {

    private val lock = Any()

    /** 内部持有的最近一帧（归本类所有，外部只能取副本） */
    private var latest: Bitmap? = null

    /** 帧版本号：每发布一帧 / 会话结束时自增，供等待方判断帧是否更新 */
    private var version: Long = 0

    /** 是否存在活动投屏会话 */
    private var active: Boolean = false

    /** 是否存在活动投屏会话 */
    fun isActive(): Boolean = synchronized(lock) { active }

    /** 当前帧版本号（与 [latestBitmap] 配合判断帧是否更新） */
    fun frameVersion(): Long = synchronized(lock) { version }

    /**
     * 返回最近一帧的**副本**（调用方负责回收）；
     * 无帧 / 帧已被回收时返回 null。拷贝在锁内完成，保证源帧被替换时不影响副本。
     */
    fun latestBitmap(): Bitmap? = synchronized(lock) {
        val b = latest ?: return null
        if (b.isRecycled) return null
        runCatching { b.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
    }

    /**
     * 采集线程发布新帧：替换内部最近帧并回收旧帧，版本号自增。
     */
    internal fun publishFrame(bitmap: Bitmap) {
        synchronized(lock) {
            val old = latest
            latest = bitmap
            version += 1
            active = true
            old?.takeIf { !it.isRecycled }?.recycle()
        }
    }

    /** 投影与会话已建立（服务完成 createVirtualDisplay 后调用） */
    internal fun markActive() {
        synchronized(lock) { active = true }
    }

    /** 会话结束：清空并回收内部帧，版本号自增以唤醒等待方 */
    internal fun markInactive() {
        synchronized(lock) {
            active = false
            val old = latest
            latest = null
            version += 1
            old?.takeIf { !it.isRecycled }?.recycle()
        }
    }

    /**
     * UI 入口：携带**本次**授权结果启动持续采集前台服务。
     *
     * 必须在系统授权结果回调中立即调用（Android 14+ 对授权后启动 FGS 有时效要求）。
     * 令牌只被服务消费一次，服务内仅 createVirtualDisplay 一次。
     */
    fun start(context: Context, resultCode: Int, data: Intent) {
        ContinuousTokenHolder.attach(resultCode, data)
        try {
            val intent = Intent(context, ContinuousCaptureService::class.java)
            ContextCompat.startForegroundService(context, intent)
        } catch (t: Throwable) {
            // 启动调用失败：清令牌、置非活动，交由调用方提示
            ContinuousTokenHolder.clear()
            markInactive()
            throw t
        }
    }

    /**
     * UI 入口：停止持续采集会话并释放全部资源。
     * 向已在运行的服务发送停止动作；服务不在运行时为空操作。
     */
    fun stop(context: Context) {
        val intent = Intent(context, ContinuousCaptureService::class.java)
            .setAction(ACTION_STOP_CONTINUOUS)
        // 目标服务已处于前台运行，普通 startService 即可投递 onStartCommand
        runCatching { context.startService(intent) }
    }
}

/**
 * 持续投屏一次性令牌的进程内持有器（仅 [ContinuousCaptureService] 可消费一次）。
 * 与 [CaptureCoordinator] 分离，保证单次抓帧与持续投屏两种会话互不串扰。
 */
private object ContinuousTokenHolder {

    class Token(val resultCode: Int, val data: Intent)

    @Volatile
    private var token: Token? = null

    /** 登记本次授权令牌（登记新令牌会直接顶替旧令牌） */
    fun attach(resultCode: Int, data: Intent) {
        token = Token(resultCode, data)
    }

    /** 消费令牌：取出后立即清除，保证只被读取一次 */
    fun consume(): Token? {
        val current = token
        token = null
        return current
    }

    /** 清除令牌（服务启动失败等异常路径） */
    fun clear() {
        token = null
    }
}

/** 停止持续采集的 Intent Action */
internal const val ACTION_STOP_CONTINUOUS = "com.autoclicker.action.STOP_CONTINUOUS_CAPTURE"

/**
 * 持续屏幕采集前台服务（FR-7 / Task 11）。
 *
 * 严格遵守 Android 14/15 约束：
 * - onStartCommand 先以 foregroundServiceType=mediaProjection 调用 startForeground，
 *   再一次性消费令牌并 getMediaProjection；
 * - 同一个 MediaProjection 只 createVirtualDisplay 一次；
 * - VirtualDisplay 存活期间 ImageReader 持续收帧并写入 [ContinuousScreenSource]；
 * - 释放顺序：VirtualDisplay → ImageReader → 注销回调 → MediaProjection.stop()
 *   → HandlerThread，最后通知来源 markInactive。
 */
class ContinuousCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var worker: HandlerThread? = null
    private var handler: Handler? = null

    /** 资源释放是否已执行（幂等保护） */
    @Volatile
    private var released: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 停止动作：释放资源并退出
        if (intent?.action == ACTION_STOP_CONTINUOUS) {
            releaseResources()
            stopSelf()
            return START_NOT_STICKY
        }

        // 1) 先以前台服务身份启动：Android 14+ 要求 startForeground 先于 getMediaProjection
        try {
            startAsForeground()
        } catch (t: Throwable) {
            ContinuousTokenHolder.clear()
            ContinuousScreenSource.markInactive()
            stopSelf()
            return START_NOT_STICKY
        }

        // 2) 一次性消费授权令牌；为空说明是陈旧/重复启动，安静退出
        val token = ContinuousTokenHolder.consume()
        if (token == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        // 3) 后台线程建立投影链路，避免任何主线程阻塞（NFR-1）
        val thread = HandlerThread(WORKER_THREAD_NAME).apply { start() }
        worker = thread
        val bg = Handler(thread.looper)
        handler = bg
        bg.post { buildSession(token) }

        return START_NOT_STICKY
    }

    /** 在后台线程完成「建读取器 → 建投影 → 仅一次虚拟显示」，之后持续出帧 */
    private fun buildSession(token: ContinuousTokenHolder.Token) {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val width = ScreenMetrics.width(wm)
        val height = ScreenMetrics.height(wm)
        val densityDpi = resources.displayMetrics.densityDpi

        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mediaProjection = try {
            projectionManager.getMediaProjection(token.resultCode, token.data)
        } catch (e: SecurityException) {
            // 令牌被系统判定失效：清理并退出（调用方 UI 可据状态重新授权）
            releaseResources()
            stopSelf()
            return
        }
        projection = mediaProjection

        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                // 投影被系统/用户停止：后台线程释放并退出
                handler?.post {
                    releaseResources()
                    stopSelf()
                }
            }
        }
        projectionCallback = callback
        mediaProjection.registerCallback(callback, handler)

        val reader = ImageReader.newInstance(
            width,
            height,
            PixelFormat.RGBA_8888,
            MAX_IMAGES,
        )
        imageReader = reader
        reader.setOnImageAvailableListener({ r ->
            val image = runCatching { r.acquireLatestImage() }.getOrNull()
                ?: return@setOnImageAvailableListener
            val bitmap = runCatching { image.toBitmap(width, height) }
                .also { runCatching { image.close() } }
                .getOrNull()
                ?: return@setOnImageAvailableListener
            // 发布到来源（来源负责回收上一帧）
            ContinuousScreenSource.publishFrame(bitmap)
        }, handler)

        // 整个会话只创建一次 VirtualDisplay（Android 14+ 硬约束），兼容重载差异
        virtualDisplay = createVirtualDisplayCompat(
            projection = mediaProjection,
            name = VIRTUAL_DISPLAY_NAME,
            width = width,
            height = height,
            densityDpi = densityDpi,
            surface = reader.surface,
            handler = handler,
        )
        ContinuousScreenSource.markActive()
    }

    /** 以前台服务方式启动（API 29+ 显式声明 mediaProjection 类型） */
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

    /** 低重要性、静默的持续投屏通知 */
    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, NotificationChannels.CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("正在持续投屏")
            .setContentText("用于脚本中的识图等待动作")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    /**
     * 释放全部采集资源（幂等）：
     * VirtualDisplay → ImageReader → 注销回调 → MediaProjection.stop() → HandlerThread。
     * 每一步单独兜底，任何一步失败都不影响后续释放；最后通知来源会话结束。
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
        handler = null

        ContinuousScreenSource.markInactive()
    }

    override fun onDestroy() {
        // 系统回收/异常销毁兜底（正常路径已在停止时释放，此处幂等）
        releaseResources()
        super.onDestroy()
    }

    private companion object {
        const val NOTIFICATION_ID = 0x7102

        /** ImageReader 缓存帧数（持续会话，5 帧缓冲足够 acquireLatest 丢弃旧帧） */
        const val MAX_IMAGES = 5

        const val WORKER_THREAD_NAME = "continuous-capture"
        const val VIRTUAL_DISPLAY_NAME = "auto-clicker-continuous"
    }
}

/**
 * 把 [ImageReader] 的一帧 RGBA 图像转为 [Bitmap]（处理行对齐填充）。
 *
 * 投影帧每行可能带对齐填充（rowStride > width * pixelStride），
 * 先按 paddedWidth 建位图 copyPixelsFromBuffer，再裁掉右侧填充列，
 * 保证输出尺寸恰为屏幕尺寸。
 */
private fun Image.toBitmap(expectedWidth: Int, expectedHeight: Int): Bitmap {
    val plane = planes[0]
    val buffer = plane.buffer
    val pixelStride = plane.pixelStride
    val rowStride = plane.rowStride

    val paddedWidth = rowStride / pixelStride
    val raw = Bitmap.createBitmap(paddedWidth, expectedHeight, Bitmap.Config.ARGB_8888)
    buffer.rewind()
    raw.copyPixelsFromBuffer(buffer)

    return if (paddedWidth == expectedWidth) {
        raw
    } else {
        Bitmap.createBitmap(raw, 0, 0, expectedWidth, expectedHeight).also {
            if (it !== raw) raw.recycle()
        }
    }
}
