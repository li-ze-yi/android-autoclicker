package com.autoclicker.core.vision

import android.os.SystemClock
import com.autoclicker.core.data.templates.ImageTemplateRepository
import com.autoclicker.core.engine.ImageAwaitResult
import com.autoclicker.core.engine.ImageWaiter
import com.autoclicker.core.engine.PlaybackException
import com.autoclicker.domain.model.Action
import com.autoclicker.service.capture.ContinuousScreenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * 基于持续投屏的识图等待器（FR-7 / AC-8，Task 11）。
 *
 * 实现 [ImageWaiter]：在 [Action.WaitImage.timeoutMs] 内轮询
 * [ContinuousScreenSource] 的最新屏幕帧，调用 [TemplateMatcher] 做匹配：
 * - 匹配成功 → 返回 [ImageAwaitResult.Found]（模板匹配矩形中心点）；
 * - 超时未找到 → 返回 [ImageAwaitResult.Timeout]；
 * - 协程被取消（停止脚本）→ 立即退出，由结构化取消传播；
 * - 投屏会话缺失 / 中途结束 → 抛 [PlaybackException]（中文消息）。
 *
 * 会话缺失为何不返回 Timeout：Timeout 的语义是"模板在限时内未出现"，
 * 而缺少投屏会话属于环境配置错误（用户未授权屏幕录制），若伪装成超时
 * 会误导用户以为页面无变化；直接报中文错误更利于定位与权限引导。
 *
 * @param source 持续屏幕帧来源
 * @param repository 模板仓库（按 templateId 取模板位图）
 * @param matcher 模板匹配器
 */
class AndroidImageWaiter(
    private val source: ContinuousScreenSource,
    private val repository: ImageTemplateRepository,
    private val matcher: TemplateMatcher = TemplateMatcher(),
) : ImageWaiter {

    override suspend fun await(action: Action.WaitImage): ImageAwaitResult {
        if (!source.isActive()) {
            throw PlaybackException("识图需要先授权屏幕录制")
        }

        val template = repository.loadBitmap(action.templateId)
            ?: throw PlaybackException("识图模板不存在，可能已被删除")

        // 使用 elapsedRealtime 计时（单调时钟，不受系统时间被调整影响）
        val startMs = SystemClock.elapsedRealtime()
        val deadlineMs = startMs + action.timeoutMs.coerceAtLeast(0)

        // 上一次已匹配过的帧版本；首轮无条件匹配当前帧
        var matchedVersion = -1L
        var firstRound = true

        try {
            while (true) {
                coroutineContext.ensureActive()

                val version = source.frameVersion()
                // 首轮或帧已更新时才取帧匹配，避免对同一画面做重复计算
                val frame = if (firstRound || version != matchedVersion) {
                    source.latestBitmap()
                } else {
                    null
                }

                if (frame != null) {
                    matchedVersion = version
                    // 匹配为 CPU 密集操作：显式切到 Default 调度器，杜绝主线程占用（NFR-1）
                    val match = withContext(Dispatchers.Default) {
                        matcher.match(
                            screen = frame,
                            template = template,
                            similarity = action.similarity,
                            region = action.region,
                        )
                    }
                    // latestBitmap 返回的是副本，匹配完立即回收
                    frame.recycle()
                    if (match != null) {
                        return ImageAwaitResult.Found(match.centerX, match.centerY)
                    }
                }

                firstRound = false
                if (SystemClock.elapsedRealtime() >= deadlineMs) {
                    return ImageAwaitResult.Timeout
                }
                if (!source.isActive()) {
                    // 会话中途结束（投屏被系统/用户停止）：按配置错误处理
                    throw PlaybackException("屏幕录制会话已结束，识图中止")
                }
                // 帧未更新：短延时后重试（100~200ms 区间）
                delay(POLL_INTERVAL_MS)
            }
        } finally {
            // 模板位图由本次 await 加载，退出时回收
            template.recycle()
        }
    }

    private companion object {
        /** 无新帧时的轮询间隔（毫秒） */
        const val POLL_INTERVAL_MS = 150L
    }
}
