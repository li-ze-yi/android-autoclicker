package com.autoclicker.core.bus

import com.autoclicker.core.bus.RecordingState
import com.autoclicker.domain.model.StepNode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 录制总线：录制服务写入步骤，录制页面订阅展示与编辑。
 */
object RecorderBus {
    private val _steps = MutableStateFlow<List<StepNode>>(emptyList())
    val steps: StateFlow<List<StepNode>> = _steps.asStateFlow()

    fun addStep(step: StepNode) {
        _steps.update { it + step }
    }

    fun updateStep(step: StepNode) {
        _steps.update { list -> list.map { if (it.id == step.id) step else it } }
    }

    fun removeStep(id: String) {
        _steps.update { list -> list.filterNot { it.id == id } }
    }

    fun clear() {
        _steps.value = emptyList()
    }
}