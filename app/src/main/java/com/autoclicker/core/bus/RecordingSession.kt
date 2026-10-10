package com.autoclicker.core.bus

import com.autoclicker.di.ServiceLocator
import com.autoclicker.domain.model.ScriptNode
import com.autoclicker.domain.model.Script
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 录制会话：把「录制」绑定到某个已存在的任务上。
 *
 * 流程：先在首页/编辑器创建任务 → [begin] 绑定该任务 → 录制的新步骤写入 [RecorderBus]，
 * 并**自动追加保存**回该任务（基础节点在 [begin] 时快照，之后每次步骤变化都重算，
 * 因此重复保存不会产生重复步骤）。
 */
object RecordingSession {

    private val _scriptId = MutableStateFlow<String?>(null)
    val scriptId: StateFlow<String?> = _scriptId.asStateFlow()

    private val _scriptName = MutableStateFlow<String?>(null)
    val scriptName: StateFlow<String?> = _scriptName.asStateFlow()

    /** 会话开始时该任务已有的顶层节点（快照），新录步骤追加在其后。 */
    private var baseNodes: List<ScriptNode> = emptyList()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var saveJob: Job? = null

    val isActive: Boolean get() = _scriptId.value != null

    /** 绑定任务并开始自动保存。会清空上一轮录制的临时步骤。 */
    fun begin(script: Script) {
        _scriptId.value = script.id
        _scriptName.value = script.name
        baseNodes = script.nodes
        RecorderBus.clear()
        startAutoSave()
    }

    /** 结束会话（不自动保存，调用方应先 [saveNow]）。 */
    fun end() {
        _scriptId.value = null
        _scriptName.value = null
        baseNodes = emptyList()
        saveJob?.cancel()
        saveJob = null
    }

    /** 立即把当前录制的步骤写入任务，返回是否成功。 */
    suspend fun saveNow(): Boolean {
        val id = _scriptId.value ?: return false
        val script = ServiceLocator.scripts.get(id) ?: return false
        val recorded = RecorderBus.steps.value
        val merged = baseNodes + recorded
        if (script.nodes == merged) return true
        ServiceLocator.scripts.save(script.copy(nodes = merged))
        return true
    }

    /** 结束会话并保存。 */
    suspend fun finish(): Boolean {
        val ok = saveNow()
        end()
        return ok
    }

    private fun startAutoSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            RecorderBus.steps.collect {
                if (_scriptId.value != null) {
                    runCatching { saveNow() }
                }
            }
        }
    }
}