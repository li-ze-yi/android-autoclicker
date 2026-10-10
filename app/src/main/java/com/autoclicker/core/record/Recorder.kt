package com.autoclicker.core.record

import com.autoclicker.core.bus.RecordMode
import com.autoclicker.domain.model.Action
import com.autoclicker.domain.model.ScriptStep
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * 录制会话状态：保存本次录制产生的脚本步骤（单一数据源，UI 时间轴直接渲染）。
 *
 * 规则（对应“延迟自动添加到每个动作中”）：
 * - 每个手势录入后，自动追加一条 [Action.Delay]，保证每个动作后都带延时；
 * - 下一个手势到来时，用两者的真实时间间隔替换上一条待定时延时；
 * - 最后一个动作保留 [DEFAULT_TRAILING_DELAY_MS]（用户可在时间轴编辑）；
 * - [autoInterval] 关闭则不追加延时。
 *
 * 生命周期：[begin] → [recordGesture]/[recordRawAction]* → [end]。
 */
class Recorder {

    private val _steps = MutableStateFlow<List<ScriptStep>>(emptyList())
    /** 已录制步骤（动作 + 自动延时交替），按时间顺序 */
    val steps: StateFlow<List<ScriptStep>> = _steps.asStateFlow()

    private val _mode = MutableStateFlow(RecordMode.Normal)
    /** 当前录制模式 */
    val mode: StateFlow<RecordMode> = _mode.asStateFlow()

    private val stepSeq = AtomicLong(0)

    /** 上一手势后那条“待真实间隔替换”的延时步骤 ID */
    private var pendingDelayId: String? = null
    private var lastGestureTimeMs: Long = 0L

    /** 是否在每个动作后自动添加延时（可由设置开关改变） */
    @Volatile
    var autoInterval: Boolean = true

    /** 开始一次录制会话 */
    fun begin(mode: RecordMode) {
        _steps.value = emptyList()
        _mode.value = mode
        stepSeq.set(0)
        pendingDelayId = null
        lastGestureTimeMs = 0L
    }

    /** 记录一条归并手势（点击/滑动） */
    fun recordGesture(gesture: RecordedGesture, timeMs: Long) {
        val action: Action = when (gesture) {
            is RecordedGesture.Tap -> Action.Tap(gesture.x, gesture.y)
            is RecordedGesture.Swipe -> Action.Swipe(
                gesture.x1, gesture.y1, gesture.x2, gesture.y2, gesture.durationMs,
            )
        }
        appendAction(action, timeMs)
    }

    /** 直接录入一个动作（手动添加 Home/返回等系统键），同样自动带延时 */
    fun recordRawAction(action: Action, timeMs: Long) {
        appendAction(action, timeMs)
    }

    /** 统一追加：先把上一条待定时延时替换为真实间隔，再加入动作与新的尾随延时 */
    private fun appendAction(action: Action, timeMs: Long) {
        val list = _steps.value.toMutableList()

        // 有上一手势：用真实间隔替换其尾随延时
        if (lastGestureTimeMs != 0L) {
            val gap = (timeMs - lastGestureTimeMs).coerceAtLeast(0L)
            val pid = pendingDelayId
            if (pid != null) {
                val idx = list.indexOfFirst { it.id == pid }
                if (idx >= 0) {
                    list[idx] = ScriptStep.BasicStep(pid, Action.Delay(gap))
                }
            }
        }

        // 加入当前动作
        list += ScriptStep.BasicStep(nextId(), action)

        // 每个动作后自动添加一条延时（最后一个用默认值，可编辑）
        pendingDelayId = if (autoInterval) {
            val did = nextId()
            list += ScriptStep.BasicStep(did, Action.Delay(DEFAULT_TRAILING_DELAY_MS))
            did
        } else {
            null
        }

        lastGestureTimeMs = timeMs
        _steps.value = list
    }

    /**
     * 删除一条步骤（时间轴单选删除）。
     * 删除手势步骤时一并移除紧随其后的延时；删除待定时延时则清除其标记。
     */
    fun removeStep(stepId: String) {
        val list = _steps.value
        val idx = list.indexOfFirst { it.id == stepId }
        if (idx < 0) return
        val removedAction = (list[idx] as? ScriptStep.BasicStep)?.action

        val newList = list.toMutableList()
        newList.removeAt(idx)
        if (removedAction != null && removedAction !is Action.Delay) {
            val next = newList.getOrNull(idx)
            if ((next as? ScriptStep.BasicStep)?.action is Action.Delay) {
                newList.removeAt(idx)
            }
        }

        if (pendingDelayId != null && newList.none { it.id == pendingDelayId }) {
            pendingDelayId = null
        }
        _steps.value = newList
    }

    /** 更新一条基础步骤的动作（如修改延时时长） */
    fun updateStep(stepId: String, newAction: Action) {
        _steps.value = _steps.value.map { step ->
            when {
                step.id != stepId -> step
                step is ScriptStep.BasicStep -> step.copy(action = newAction)
                else -> throw IllegalArgumentException("该步骤不支持编辑参数")
            }
        }
    }

    /** 结束录制（步骤保留，供保存/回放） */
    fun end() {
        lastGestureTimeMs = 0L
        pendingDelayId = null
    }

    /** 清空本次录制 */
    fun clear() {
        _steps.value = emptyList()
        lastGestureTimeMs = 0L
        pendingDelayId = null
    }

    private fun nextId(): String = "rec-${stepSeq.incrementAndGet()}"

    companion object {
        /** 最后一个动作后默认延时（毫秒），可在时间轴编辑 */
        const val DEFAULT_TRAILING_DELAY_MS: Long = 1000L
    }
}
