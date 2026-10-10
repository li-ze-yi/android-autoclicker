package com.autoclicker.ui.record

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autoclicker.MyApplication
import com.autoclicker.core.bus.EngineState
import com.autoclicker.core.data.scripts.ScriptFileRepository
import com.autoclicker.core.record.Recorder
import com.autoclicker.domain.model.Action
import com.autoclicker.domain.model.Script
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 录制界面 ViewModel：连接 Recorder/总线/脚本仓库。
 */
class RecordingViewModel(
    private val app: MyApplication,
    private val scriptRepository: ScriptFileRepository,
) : ViewModel() {

    val steps: StateFlow<List<com.autoclicker.domain.model.ScriptStep>> = app.recorder.steps
    val engineState: StateFlow<EngineState> = app.bus.engineState

    fun addGlobalHome() {
        // S2 修复：必须与手势事件的 eventTime（uptimeMillis 基准）一致，
        // 传墙钟时间会算出约 53 年的超长延时。
        app.recorder.recordRawAction(
            Action.GlobalHome,
            android.os.SystemClock.uptimeMillis(),
        )
    }

    fun addGlobalBack() {
        app.recorder.recordRawAction(
            Action.GlobalBack,
            android.os.SystemClock.uptimeMillis(),
        )
    }

    fun removeStep(stepId: String) = app.recorder.removeStep(stepId)

    fun updateDelay(stepId: String, durationMs: Long) {
        app.recorder.updateStep(stepId, Action.Delay(durationMs.coerceAtLeast(0)))
    }

    /** 仅保存 */
    fun save(name: String, onDone: () -> Unit) {
        viewModelScope.launch {
            val result = runCatching { persist(name) }
            result.onFailure {
                app.bus.publishEvent(it.message ?: "保存失败")
            }
            if (result.isSuccess) onDone()
        }
    }

    /** 保存并运行：结束录制态后启动脚本 */
    fun saveAndRun(name: String, onDone: () -> Unit) {
        viewModelScope.launch {
            val script = runCatching { persist(name) }
                .onFailure { app.bus.publishEvent(it.message ?: "保存失败") }
                .getOrNull() ?: return@launch
            app.coordinator.stopRecording()
            app.coordinator.startScript(script)
            onDone()
        }
    }

    private suspend fun persist(name: String): Script {
        val trimmed = name.trim().ifEmpty { "未命名脚本" }
        val script = Script(
            id = scriptRepository.newId(),
            name = trimmed,
            steps = app.recorder.steps.value,
        )
        scriptRepository.upsert(script)
        return script
    }

    fun stopRecording() = app.coordinator.stopRecording()
}

@Composable
internal fun rememberRecordingViewModel(): RecordingViewModel {
    val context = LocalContext.current
    val app = context.applicationContext as MyApplication
    return remember { RecordingViewModel(app, ScriptFileRepository(app)) }
}
