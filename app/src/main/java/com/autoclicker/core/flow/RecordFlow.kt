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
 * 「建任务 → 检查/跳转权限 → 弹悬浮球 → 录制入库 → 录完保存并关球」的一条龙编排。
 *
 * 各步骤实现分散在仓库/会话/录制器/悬浮窗中，本类负责把它们串成完整用户流程。
 */
object RecordFlow {

    /** 开始录制的结果。 */
    sealed interface StartResult {
        /** 缺权限：由 UI 跳转到对应设置页，返回后重试。 */
        data class NeedPermission(val kind: PermissionChecker.Kind) : StartResult

        /** 已开始录制。 */
        data class Started(val script: Script) : StartResult

        /** 失败。 */
        data class Failed(val message: String) : StartResult
    }

    /** 新建任务并开始录制。 */
    suspend fun createTaskAndRecord(context: Context, name: String): StartResult {
        val missing = PermissionChecker.firstMissing(context)
        if (missing != null) return StartResult.NeedPermission(missing)

        val script = Script(id = Ids.newId(), name = name.trim().ifBlank { "录制任务" })
        return runCatching {
            ServiceLocator.scripts.save(script)
            bindAndRecord(context, script)
        }.getOrElse { t ->
            RuntimeBus.log(LogLevel.ERROR, "新建任务失败：${t.message}")
            StartResult.Failed(t.message ?: "新建任务失败")
        }
    }

    /** 绑定已有任务并开始录制。 */
    suspend fun bindAndRecord(context: Context, script: Script): StartResult {
        val missing = PermissionChecker.firstMissing(context)
        if (missing != null) return StartResult.NeedPermission(missing)

        ServiceLocator.scripts.save(script)
        RecordingSession.begin(script)
        OverlayService.start(context)
        Recorder.start(context)
        RuntimeBus.log("已开始录制到任务「${script.name}」，悬浮球已弹出")
        return StartResult.Started(script)
    }

    /** 结束录制：停止并保存到任务，然后关闭悬浮球。 */
    suspend fun finishAndClose(context: Context) {
        Recorder.stop(context)
        runCatching { RecordingSession.finish() }
        OverlayService.stop(context)
        RuntimeBus.log("录制已结束并保存到任务，悬浮球已关闭")
    }
}