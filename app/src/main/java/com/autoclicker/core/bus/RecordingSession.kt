package com.autoclicker.core.bus

import com.autoclicker.di.ServiceLocator
import com.autoclicker.domain.model.Script
import com.autoclicker.domain.model.ScriptNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 任务编辑会话：绑定一个任务，并以 [nodes] 作为该任务顶层步骤的**唯一数据源**。
 *
 * 悬浮球编辑器、App 内编辑器、录制器都通过本类读写步骤；任何修改都会自动保存回任务，
 * 因此不会再出现「两套列表互相覆盖」的问题。
 */
object RecordingSession {

    private val _scriptId = MutableStateFlow<String?>(null)
    val scriptId: StateFlow<String?> = _scriptId.asStateFlow()

    private val _scriptName = MutableStateFlow<String?>(null)
    val scriptName: StateFlow<String?> = _scriptName.asStateFlow()

    private val _nodes = MutableStateFlow<List<ScriptNode>>(emptyList())
    val nodes: StateFlow<List<ScriptNode>> = _nodes.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var saveJob: Job? = null

    val isActive: Boolean get() = _scriptId.value != null

    /** 绑定任务（载入其顶层节点）。 */
    fun begin(script: Script) {
        _scriptId.value = script.id
        _scriptName.value = script.name
        _nodes.value = script.nodes
        startAutoSave()
    }

    /** 结束会话。 */
    fun end() {
        _scriptId.value = null
        _scriptName.value = null
        _nodes.value = emptyList()
        saveJob?.cancel()
        saveJob = null
    }

    // ---------------- 编辑操作（自动保存） ----------------

    fun rename(name: String) {
        _scriptName.value = name.trim().ifBlank { "录制任务" }
        requestSave()
    }

    /**
     * App 内编辑器保存后，把最新内容同步回会话（名称 + 顶层步骤）。
     *
     * 悬浮窗控制台与编辑器共用本会话作为步骤数据源；不同步会出现「编辑器改了、悬浮窗还是旧的」，
     * 且悬浮窗后续的改动会把整份旧列表覆盖回去。
     */
    fun syncFrom(script: Script) {
        if (_scriptId.value != script.id) return
        _scriptName.value = script.name
        _nodes.value = script.nodes
    }

    fun addNode(node: ScriptNode) {
        _nodes.value = _nodes.value + node
    }

    fun addNodeAt(index: Int, node: ScriptNode) {
        val list = _nodes.value.toMutableList()
        list.add(index.coerceIn(0, list.size), node)
        _nodes.value = list
    }

    fun updateNode(node: ScriptNode) {
        _nodes.value = _nodes.value.map { if (it.id == node.id) node else it }
    }

    fun removeNode(id: String) {
        _nodes.value = _nodes.value.filterNot { it.id == id }
    }

    /** 上/下移动步骤（delta = -1 上移，+1 下移）。 */
    fun moveNode(id: String, delta: Int) {
        val list = _nodes.value.toMutableList()
        val from = list.indexOfFirst { it.id == id }
        if (from < 0) return
        val to = (from + delta).coerceIn(0, list.size - 1)
        if (from == to) return
        val item = list.removeAt(from)
        list.add(to, item)
        _nodes.value = list
    }

    // ---------------- 持久化 ----------------

    /** 立即保存到任务。 */
    suspend fun saveNow(): Boolean {
        val id = _scriptId.value ?: return false
        val script = ServiceLocator.scripts.get(id) ?: return false
        val name = _scriptName.value ?: script.name
        val nodes = _nodes.value
        if (script.name == name && script.nodes == nodes) return true
        ServiceLocator.scripts.save(script.copy(name = name, nodes = nodes))
        return true
    }

    private fun requestSave() {
        scope.launch { runCatching { saveNow() } }
    }

    private fun startAutoSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            _nodes.collect {
                if (_scriptId.value != null) runCatching { saveNow() }
            }
        }
    }
}