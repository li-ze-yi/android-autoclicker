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
 * 纯 Kotlin（StateFlow），普通/精确两种模式的事件源都调用本类：
 * - [recordGesture]：归并后的手势转为步骤；按 [autoInterval] 自动插入相邻手势的延时；
 * - [removeStep]：删除任意一条（含延时步骤）；
 * - 生命周期：[begin] → [recordGesture]* → [end]。
 */
class Recorder {

    private val _steps = MutableStateFlow<List<ScriptStep>>(emptyList())
    /** 已录制步骤（含自动延时），按时间顺序 */
    val steps: StateFlow<List<ScriptStep>> = _steps.asStateFlow()

    private val _mode = MutableStateFlow(RecordMode.Normal)
    /** 当前录制模式 */
    val mode: StateFlow<RecordMode> = _mode.asStateFlow()

    private val stepSeq = AtomicLong(0)
    private var lastGestureTimeMs: Long = 0L

    /** 是否自动记录相邻手势间隔为延时步骤（可由设置开关改变） */
    @Volatile
    var autoInterval: Boolean = true

    /** 开始一次录制会话 */
    fun begin(mode: RecordMode) {
        _steps.value = emptyList()
        _mode.value = mode
        stepSeq.set(0)
        lastGestureTimeMs = 0L
    }

    /**
     * 记录一条归并手势。
     *
     * @param timeMs 该手势抬起时间（毫秒），用于计算与上一手势的间隔
     */
    fun recordGesture(gesture: RecordedGesture, timeMs: Long) {
        val newSteps = mutableListOf<ScriptStep>()
        newSteps += _steps.value

        // 自动延时：与上一手势的间隔（第一条不插入）
        if (autoInterval && lastGestureTimeMs != 0L && newSteps.isNotEmpty()) {
            val gap = (timeMs - lastGestureTimeMs).coerceAtLeast(0L)
            if (gap > 0) {
                newSteps += ScriptStep.BasicStep(
                    id = nextId(),
                    action = Action.Delay(gap),
                )
            }
        }

        newSteps += ScriptStep.BasicStep(
            id = nextId(),
            action = when (gesture) {
                is RecordedGesture.Tap -> Action.Tap(gesture.x, gesture.y)
                is RecordedGesture.Swipe -> Action.Swipe(
                    gesture.x1, gesture.y1, gesture.x2, gesture.y2, gesture.durationMs,
                )
            },
        )

        lastGestureTimeMs = timeMs
        _steps.value = newSteps
    }

    /**
     * 直接录入一个动作（手动添加 Home/返回等系统键时使用）。
     * 同样遵循自动延时插入规则。
     */
    fun recordRawAction(action: Action, timeMs: Long) {
        val newSteps = mutableListOf<ScriptStep>()
        newSteps += _steps.value

        if (autoInterval && lastGestureTimeMs != 0L && newSteps.isNotEmpty()) {
            val gap = (timeMs - lastGestureTimeMs).coerceAtLeast(0L)
            if (gap > 0) {
                newSteps += ScriptStep.BasicStep(nextId(), Action.Delay(gap))
            }
        }
        newSteps += ScriptStep.BasicStep(nextId(), action)
        lastGestureTimeMs = timeMs
        _steps.value = newSteps
    }

    /** 删除一条步骤（时间轴单选删除） */
    fun removeStep(stepId: String) {
        _steps.value = _steps.value.filterNot { it.id == stepId }
    }

    /**
     * 更新一条基础步骤的动作（如修改延时时长）。
     * 仅 BasicStep 可更新；找不到步骤抛 IllegalArgumentException。
     */
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
    }

    /** 清空本次录制 */
    fun clear() {
        _steps.value = emptyList()
        lastGestureTimeMs = 0L
    }

    private fun nextId(): String = "rec-${stepSeq.incrementAndGet()}"
}
