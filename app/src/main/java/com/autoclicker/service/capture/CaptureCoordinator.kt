package com.autoclicker.service.capture

import android.content.Intent
import android.graphics.Bitmap
import kotlinx.coroutines.CompletableDeferred

/**
 * 屏幕采集会话协调器（进程内单例持有器）。
 *
 * 职责：
 * 1. 临时保存**一次**授权结果（resultCode + data），供 [ScreenCaptureService] 读取；
 * 2. 持有本次采集的结果挂起点（[CompletableDeferred]），服务抓帧成功/失败后完成它；
 * 3. 串行化会话，保证「一次授权 ↔ 一次服务会话」，连续两次采集互不串扰。
 *
 * 关键约束（Android 14+，FR-7）：
 * - MediaProjection 令牌每次会话只能使用一次：每个新会话都会整体替换旧状态，
 *   resultCode/data 只被一个服务实例消费一次，消费后立即从持有器移除，绝不复用；
 * - 用户每次采集都必须重新走 createScreenCaptureIntent 授权（由 [CaptureRequester] 发起）。
 *
 * 本类虽是 object，但所有可变状态均为 private 且经 [lock] 串行访问，
 * 是本功能唯一被允许的会话持有点，不属于散落静态状态。
 */
object CaptureCoordinator {

    /** 一次采集会话的全部进程内数据 */
    private class Session(
        /** MediaProjection 授权结果码（Activity.RESULT_OK） */
        val resultCode: Int,
        /** MediaProjection 授权返回的 Intent（一次性令牌，禁止复用） */
        val data: Intent,
        /** 本次会话最终结果：成功为全屏 Bitmap，失败为 [CaptureException] */
        val result: CompletableDeferred<Bitmap>,
    )

    /** 授权结果（服务消费用）：消费后 data 即从持有器清除，保证只被读取一次 */
    class Authorization(
        val resultCode: Int,
        val data: Intent,
        val result: CompletableDeferred<Bitmap>,
    )

    /** 当前待消费的会话；为空表示没有进行中的采集 */
    @Volatile
    private var session: Session? = null

    /** 会话锁：保证登记/消费/失败的原子性 */
    private val lock = Any()

    /**
     * 登记一次新的授权结果，开启新会话。
     * 必须在用户本次授权成功后、启动 [ScreenCaptureService] 前调用。
     *
     * 若此前仍有未完成会话（异常流程），先用中文异常将其收尾，避免调用方永久挂起。
     *
     * @param result 调用方（[CaptureRequester]）创建并等待的结果挂起点
     */
    fun attach(resultCode: Int, data: Intent, result: CompletableDeferred<Bitmap>) {
        val newSession = Session(resultCode, data, result)
        synchronized(lock) {
            session?.result?.takeIf { it.isActive }
                ?.completeExceptionally(
                    CaptureException("已发起新的屏幕采集，上一次会话被取消")
                )
            session = newSession
        }
    }

    /**
     * 服务消费授权结果：取出后立即清除持有器引用。
     * 保证 resultCode/data 只会被一个服务实例读取一次，从机制上杜绝令牌复用。
     *
     * @return 授权结果；为 null 表示当前没有待消费会话（重复启动/进程重建等异常情况）
     */
    fun consume(): Authorization? {
        synchronized(lock) {
            val current = session ?: return null
            session = null
            return Authorization(current.resultCode, current.data, current.result)
        }
    }

    /**
     * 让当前待消费会话以失败结束（如前台服务启动失败）。
     * 失败后会话被清除，不会影响后续重新发起的新会话。
     */
    fun failCurrent(message: String) {
        val current = synchronized(lock) {
            val current = session
            session = null
            current
        }
        current?.result?.takeIf { it.isActive }
            ?.completeExceptionally(CaptureException(message))
    }
}

/**
 * 屏幕采集异常：消息一律为中文，可直接向用户展示；
 * 授权拒绝、服务启动失败、抓帧超时等全部走该异常，不崩溃。
 */
class CaptureException(message: String) : RuntimeException(message)
