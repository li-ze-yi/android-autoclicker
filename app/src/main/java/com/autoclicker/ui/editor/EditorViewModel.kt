package com.autoclicker.ui.editor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.autoclicker.core.script.Script
import com.autoclicker.core.script.ScriptRepository
import com.autoclicker.core.script.Step
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 脚本编辑器状态容器。持有可变的 [Script]，所有编辑操作都产生新的不可变副本。
 */
class EditorViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = ScriptRepository.get(app)

    private val _script = MutableStateFlow<Script?>(null)
    val script: StateFlow<Script?> = _script.asStateFlow()

    private var loadedId: String? = null

    /** 按 id 载入脚本；同一 id 重复调用不会覆盖正在编辑的内容。 */
    fun load(id: String) {
        if (loadedId == id && _script.value != null) return
        loadedId = id
        val loaded = repository.load(id)
        _script.value = loaded ?: Script.create("新脚本").copy(id = id)
    }

    fun addStep(step: Step) {
        _script.value = _script.value?.let { it.copy(steps = it.steps + step) }
    }

    fun updateStep(step: Step) {
        _script.value = _script.value?.let { script ->
            script.copy(steps = script.steps.map { if (it.id == step.id) step else it })
        }
    }

    fun removeStep(id: String) {
        _script.value = _script.value?.let { script ->
            script.copy(steps = script.steps.filterNot { it.id == id })
        }
    }

    fun moveStep(from: Int, to: Int) {
        _script.value = _script.value?.let { script ->
            val steps = script.steps.toMutableList()
            if (from in steps.indices && to in steps.indices && from != to) {
                val moved = steps.removeAt(from)
                steps.add(to, moved)
            }
            script.copy(steps = steps)
        }
    }

    fun updateName(name: String) {
        _script.value = _script.value?.copy(name = name)
    }

    fun updateStopOnError(value: Boolean) {
        _script.value = _script.value?.copy(stopOnError = value)
    }

    fun save() {
        _script.value?.let { _script.value = repository.save(it) }
    }
}