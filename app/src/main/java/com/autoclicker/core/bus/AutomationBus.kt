package com.autoclicker.core.bus

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 引擎整体状态。
 *
 * 合法迁移图：
 * - [Idle] → start → [Running] → pause → [Paused] → resume → [Running] → stop → [Idle]
 * - [Idle] → startRecording → [Recording] → stopRecording → [Idle]
 */
enum class EngineState {
    /** 空闲：无回放、无录制 */
    Idle,

    /** 脚本回放运行中 */
    Running,

    /** 脚本回放已暂停（可继续或停止） */
    Paused,

    /** 动作录制中 */
    Recording
}

/**
 * 录制模式（详见 FR-5）。仅在 [EngineState.Recording] 状态下有实际意义。
 */
enum class RecordMode {
    /** 普通模式：通过无障碍事件获取点击位置，零遮挡 */
    Normal,

    /** 精确模式：全屏透明触摸捕获层，直接捕获手指轨迹与绝对坐标 */
    Precise
}

/**
 * 无障碍服务连接状态（服务独立于引擎动作状态）。
 */
enum class AccessibilityState {
    /** 无障碍服务未连接 */
    Disconnected,

    /** 无障碍服务已连接，可派发手势 */
    Connected
}

/**
 * 非法的引擎状态迁移命令异常。
 *
 * 仅用于拒绝不合法的命令调用，不能作为正常流程的返回通道。
 * 异常消息一律为中文，便于直接向用户展示或记入日志。
 */
class AutomationCommandException(message: String) : IllegalStateException(message)

/**
 * 进程内状态中枢：服务层与 UI 层之间唯一的状态/命令通道。
 *
 * 设计约束：
 * - 本类是**普通可实例化类**（不是 object），单元测试可直接 new；单例装配由后续 MyApplication 持有唯一实例完成。
 * - 所有可变状态以 private [MutableStateFlow] 封装，外部只能拿到只读 [StateFlow]。
 * - 脚本标识统一使用 String，不依赖 domain 层任何类型。
 * - 实现为纯 Kotlin + 协程，不 import android.*，可在 JVM 单测中运行。
 *
 * @see EngineState 状态定义与合法迁移图
 */
class AutomationBus {

    private val _engineState = MutableStateFlow(EngineState.Idle)
    /** 引擎当前状态，初始为 [EngineState.Idle] */
    val engineState: StateFlow<EngineState> = _engineState.asStateFlow()

    private val _activeScriptId = MutableStateFlow<String?>(null)
    /** 当前回放脚本标识；仅 [EngineState.Running]/[EngineState.Paused] 时非空，停止后清空 */
    val activeScriptId: StateFlow<String?> = _activeScriptId.asStateFlow()

    private val _recordingMode = MutableStateFlow(RecordMode.Normal)
    /** 录制模式，默认 [RecordMode.Normal]；仅录制状态下有意义 */
    val recordingMode: StateFlow<RecordMode> = _recordingMode.asStateFlow()

    private val _accessibilityState = MutableStateFlow(AccessibilityState.Disconnected)
    /** 无障碍服务连接状态，初始 [AccessibilityState.Disconnected] */
    val accessibilityState: StateFlow<AccessibilityState> = _accessibilityState.asStateFlow()

    private val _eventMessages = MutableSharedFlow<String>(
        // 带缓冲：无订阅者时不丢弃、不阻塞发布方；缓冲溢出时淘汰最旧消息
        extraBufferCapacity = EVENT_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    /** 面向 UI 的一次性提示事件（如 Toast、Snackbar），不持久、不补放旧状态 */
    val eventMessages: SharedFlow<String> = _eventMessages.asSharedFlow()

    /** 串行化全部状态迁移，避免并发命令产生「检查后再写入」竞争 */
    private val commandMutex = Mutex()

    /**
     * 启动脚本回放：[EngineState.Idle] → [EngineState.Running]。
     *
     * @param scriptId 要运行的脚本标识，不可为空白
     * @throws AutomationCommandException 当前不是空闲状态
     * @throws IllegalArgumentException [scriptId] 为空白
     */
    suspend fun start(scriptId: String) {
        require(scriptId.isNotBlank()) { "脚本标识不能为空" }
        commandMutex.withLock {
            val current = _engineState.value
            if (current != EngineState.Idle) {
                throw AutomationCommandException(
                    "当前状态为「${current.displayName()}」，无法启动脚本，请先停止当前任务"
                )
            }
            _activeScriptId.value = scriptId
            _engineState.value = EngineState.Running
        }
    }

    /**
     * 暂停回放：[EngineState.Running] → [EngineState.Paused]，脚本标识保留。
     *
     * @throws AutomationCommandException 当前不是运行状态
     */
    suspend fun pause() = commandMutex.withLock {
        val current = _engineState.value
        if (current != EngineState.Running) {
            throw AutomationCommandException(
                "当前状态为「${current.displayName()}」，无法暂停，仅运行中的脚本可暂停"
            )
        }
        _engineState.value = EngineState.Paused
    }

    /**
     * 继续回放：[EngineState.Paused] → [EngineState.Running]，脚本标识保留。
     *
     * @throws AutomationCommandException 当前不是暂停状态
     */
    suspend fun resume() = commandMutex.withLock {
        val current = _engineState.value
        if (current != EngineState.Paused) {
            throw AutomationCommandException(
                "当前状态为「${current.displayName()}」，无法继续，仅暂停状态可继续"
            )
        }
        _engineState.value = EngineState.Running
    }

    /**
     * 停止回放：[EngineState.Running]/[EngineState.Paused] → [EngineState.Idle]，
     * 同时清空 [activeScriptId]。
     *
     * @throws AutomationCommandException 当前不在回放中
     */
    suspend fun stop() = commandMutex.withLock {
        val current = _engineState.value
        if (current != EngineState.Running && current != EngineState.Paused) {
            throw AutomationCommandException(
                "当前状态为「${current.displayName()}」，没有正在运行的脚本，无法停止"
            )
        }
        _engineState.value = EngineState.Idle
        _activeScriptId.value = null
    }

    /**
     * 开始录制：[EngineState.Idle] → [EngineState.Recording]，并记录录制模式。
     *
     * @param mode 普通 / 精确录制模式
     * @throws AutomationCommandException 当前不是空闲状态
     */
    suspend fun startRecording(mode: RecordMode) = commandMutex.withLock {
        val current = _engineState.value
        if (current != EngineState.Idle) {
            throw AutomationCommandException(
                "当前状态为「${current.displayName()}」，无法开始录制，请先停止当前任务"
            )
        }
        _recordingMode.value = mode
        _engineState.value = EngineState.Recording
    }

    /**
     * 结束录制：[EngineState.Recording] → [EngineState.Idle]。
     *
     * 录制模式复位为 [RecordMode.Normal]（录制模式仅在录制中有意义）。
     *
     * @throws AutomationCommandException 当前不在录制中
     */
    suspend fun stopRecording() = commandMutex.withLock {
        val current = _engineState.value
        if (current != EngineState.Recording) {
            throw AutomationCommandException(
                "当前状态为「${current.displayName()}」，录制未进行，无法结束录制"
            )
        }
        _engineState.value = EngineState.Idle
        _recordingMode.value = RecordMode.Normal
    }

    /**
     * 供同模块内部组件（回放引擎、录制器、各服务）向 UI 发布一次性提示。
     *
     * 使用带缓冲的 tryEmit：无订阅者时不挂起、不阻塞调用方，消息至多保留最近
     * [EVENT_BUFFER_CAPACITY] 条。外部模块只能收集 [eventMessages]，无法发布。
     */
    internal fun publishEvent(message: String) {
        _eventMessages.tryEmit(message)
    }

    /**
     * 无障碍服务已连接：标记 [AccessibilityState.Connected]。
     * 由 AccessibilityService.onServiceConnected 调用。
     */
    internal fun onAccessibilityConnected() {
        _accessibilityState.value = AccessibilityState.Connected
    }

    /**
     * 无障碍服务断开（被系统回收/用户关闭权限）：
     * 标记 [AccessibilityState.Disconnected]，并强制回到 [EngineState.Idle]、清空脚本标识，
     * 避免「状态显示运行中但已无法派发手势」的不一致。
     */
    internal fun onAccessibilityDisconnected() {
        _accessibilityState.value = AccessibilityState.Disconnected
        _engineState.value = EngineState.Idle
        _activeScriptId.value = null
    }

    /** 状态的中文名，用于拼装面向用户的异常消息 */
    private fun EngineState.displayName(): String = when (this) {
        EngineState.Idle -> "空闲"
        EngineState.Running -> "运行中"
        EngineState.Paused -> "已暂停"
        EngineState.Recording -> "录制中"
    }

    private companion object {
        /** 一次性提示事件的缓冲条数 */
        const val EVENT_BUFFER_CAPACITY = 16
    }
}
