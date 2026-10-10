package com.autoclicker.core.flow

import android.content.Context
import com.autoclicker.core.bus.LogLevel
import com.autoclicker.core.bus.RecordingSession
import com.autoclicker.core.bus.RuntimeBus
import com.autoclicker.core.permission.PermissionChecker
import com.autoclicker.di.ServiceLocator
import com.autoclicker.domain.model.Ids
import com.autoclicker.domain.model.Script
import com.autoclicker.service.overlay.OverlayService
import com.autoclicker.service.record.Recorder

/**
 * 「建任务 → 检查/跳转权限 → 弹悬浮球并绑定任务 → 录制入库 → 录完保存并关球」的一条龙编排。
 */
object RecordFlow {

    /** 开始录制/绑定任务的结果。 */
    sealed interface StartResult {
        /** 缺权限：由 UI 跳转到对应设置页，返回后重试。 */
        data class NeedPermission(val kind: PermissionChecker.Kind) : StartResult

        /** 已就绪。 */
        data class Started(val script: Script) : StartResult

        /** 失败。 */
        data class Failed(val message: String) : StartResult
    }

    /** 新建任务并开始录制。 */
    suspend fun createTaskAndRecord(context: Context, name: String): StartResult {
        val script = Script(id = Ids.newId(), name = name.trim().ifBlank { "录制任务" })
        return runCatching {
            val bound = bindAndShowBall(context, script, requireAccessibility = true)
            if (bound !is StartResult.Started) return@runCatching bound
            Recorder.start(context)
            RuntimeBus.log("已开始录制到任务「${script.name}」，悬浮球已弹出")
            StartResult.Started(script)
        }.getOrElse { t ->
            RuntimeBus.log(LogLevel.ERROR, "新建任务失败：${t.message}")
            StartResult.Failed(t.message ?: "新建任务失败")
        }
    }

    /** 绑定已有任务并开始录制。 */
    suspend fun bindAndRecord(context: Context, script: Script): StartResult {
        val bound = bindAndShowBall(context, script, requireAccessibility = true)
        if (bound !is StartResult.Started) return bound
        Recorder.start(context)
        RuntimeBus.log("已开始录制到任务「${script.name}」，悬浮球已弹出")
        return StartResult.Started(script)
    }

    /**
     * 绑定任务 + 弹出悬浮球（不自动开始录制）。
     * 用于「新建任务成功后自动弹出悬浮球」。
     */
    suspend fun bindAndShowBall(
        context: Context,
        script: Script,
        requireAccessibility: Boolean,
    ): StartResult {
        val missing = if (requireAccessibility) {
            PermissionChecker.firstMissing(context)
        } else {
            // 仅弹悬浮球只需悬浮窗权限。
            if (PermissionChecker.isOverlayGranted(context)) null else PermissionChecker.Kind.OVERLAY
        }
        if (missing != null) return StartResult.NeedPermission(missing)

        runCatching { ServiceLocator.scripts.save(script) }
        RecordingSession.begin(script)
        OverlayService.start(context)
        RuntimeBus.log("悬浮球已弹出并绑定任务「${script.name}」")
        return StartResult.Started(script)
    }

    /** 结束录制：停止并保存到任务，然后关闭悬浮球（会话仍绑定该任务，便于再次编辑）。 */
    suspend fun finishAndClose(context: Context) {
        Recorder.stop(context)
        runCatching { RecordingSession.saveNow() }
        OverlayService.stop(context)
        RuntimeBus.log("录制已结束并保存到任务，悬浮球已关闭")
    }
}