package com.autoclicker.core.bus

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 播放状态。 */
enum class PlaybackState { IDLE, RUNNING, PAUSED, STOPPED, ERROR }

/** 录制状态。 */
enum class RecordingState { IDLE, RECORDING, PAUSED }

enum class LogLevel { DEBUG, INFO, SUCCESS, WARN, ERROR }

/** 一条运行日志。 */
data class LogEntry(
    val id: Long,
    val timeMs: Long,
    val level: LogLevel,
    val message: String,
)

/** 当前正在执行的步骤信息（用于控制台高亮与屏幕顶部实时提示）。 */
data class StepExecutionInfo(
    val scriptId: String,
    val scriptName: String,
    val stepIndex: Int,
    val stepTotal: Int,
    val description: String,
    /** 该步骤的动作后延时（ms），供顶部提示显示。 */
    val delayAfterMs: Long = 0L,
)

/**
 * 运行期全局总线：引擎写入日志/状态，悬浮控制台与 App 内 UI 共同订阅。
 * 采用进程内单例，避免在服务与 Activity 之间传递引用。
 */
object RuntimeBus {
    private const val MAX_LOGS = 500

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val _state = MutableStateFlow(PlaybackState.IDLE)
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _recording = MutableStateFlow(RecordingState.IDLE)
    val recording: StateFlow<RecordingState> = _recording.asStateFlow()

    private val _currentStep = MutableStateFlow<StepExecutionInfo?>(null)
    val currentStep: StateFlow<StepExecutionInfo?> = _currentStep.asStateFlow()

    private var counter = 0L

    fun log(level: LogLevel, message: String) {
        val entry = LogEntry(++counter, System.currentTimeMillis(), level, message)
        _logs.update { current ->
            val next = current + entry
            if (next.size > MAX_LOGS) next.takeLast(MAX_LOGS) else next
        }
    }

    fun log(message: String) = log(LogLevel.INFO, message)

    fun clearLogs() {
        _logs.value = emptyList()
    }

    fun setState(state: PlaybackState) {
        _state.value = state
    }

    fun setRecording(state: RecordingState) {
        _recording.value = state
    }

    fun setCurrentStep(info: StepExecutionInfo?) {
        _currentStep.value = info
    }
}