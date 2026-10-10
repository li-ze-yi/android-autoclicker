@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.autoclicker.ui.scripts

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.autoclicker.MyApplication
import com.autoclicker.core.data.scripts.ScriptFileRepository
import com.autoclicker.domain.codec.ScriptCodec
import com.autoclicker.domain.model.Action
import com.autoclicker.domain.model.Script
import com.autoclicker.domain.model.ScriptStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

// =================================================================================
// 页面入口
// =================================================================================

/**
 * 脚本列表页（FR-6 / Task 12）。
 *
 * 功能：
 * - 展示脚本仓库中的全部脚本（名称、步骤数）；
 * - 新建空脚本并进入编辑器；
 * - 运行（coordinator.startScript）、重命名、复制（深拷贝 + 新 id）、删除（二次确认）；
 * - 导出脚本 JSON（SAF CreateDocument）；导入脚本 JSON（SAF OpenDocument，自动分配新 id）。
 *
 * @param onOpenScript 打开步骤编辑器回调，参数为脚本 id（由导航层接入）
 */
@Composable
fun ScriptsScreen(
    onOpenScript: (scriptId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val app = LocalContext.current.applicationContext as MyApplication
    val viewModel: ScriptsViewModel = viewModel(
        factory = remember(app) {
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    @Suppress("UNCHECKED_CAST")
                    return ScriptsViewModel(
                        app = app,
                        repository = ScriptFileRepository(app),
                    ) as T
                }
            }
        }
    )
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // 一次性中文提示
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message.text)
        viewModel.onIntent(ScriptsIntent.MessageShown)
    }

    // 一次性「打开编辑器」事件（新建后或点击条目时触发）
    LaunchedEffect(state.openEvent) {
        val event = state.openEvent ?: return@LaunchedEffect
        onOpenScript(event.scriptId)
        viewModel.onIntent(ScriptsIntent.OpenConsumed)
    }

    // 导出：先记住待导出脚本，再由 SAF 创建目标文件
    var scriptToExport by remember { mutableStateOf<Script?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val pending = scriptToExport
        if (uri != null && pending != null) {
            viewModel.exportTo(pending, uri)
        }
        scriptToExport = null
    }

    // 导入：SAF 选择 JSON 文件
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importFrom(uri)
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("脚本库") },
                actions = {
                    IconButton(onClick = { importLauncher.launch(arrayOf("application/json")) }) {
                        Icon(Icons.Filled.FileUpload, contentDescription = "导入脚本")
                    }
                    IconButton(onClick = { viewModel.onIntent(ScriptsIntent.CreateNew) }) {
                        Icon(Icons.Filled.Add, contentDescription = "新建脚本")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                state.loading ->
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

                state.scripts.isEmpty() ->
                    EmptyScripts(
                        onCreate = { viewModel.onIntent(ScriptsIntent.CreateNew) },
                        onImport = { importLauncher.launch(arrayOf("application/json")) },
                        modifier = Modifier.align(Alignment.Center),
                    )

                else ->
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(state.scripts, key = { it.id }) { script ->
                            ScriptCard(
                                script = script,
                                onOpen = { viewModel.onIntent(ScriptsIntent.Open(script.id)) },
                                onRun = { viewModel.onIntent(ScriptsIntent.Run(script)) },
                                onRename = {
                                    viewModel.onIntent(ScriptsIntent.ShowRenameDialog(script))
                                },
                                onDuplicate = {
                                    viewModel.onIntent(ScriptsIntent.Duplicate(script))
                                },
                                onDelete = {
                                    viewModel.onIntent(ScriptsIntent.RequestDelete(script))
                                },
                                onExport = {
                                    scriptToExport = script
                                    exportLauncher.launch("${script.name}.json")
                                },
                            )
                        }
                    }
            }
        }
    }

    // 弹窗层
    when (val dialog = state.dialog) {
        null -> Unit

        is ScriptsDialog.Rename ->
            NameInputDialog(
                title = "重命名脚本",
                initialName = dialog.script.name,
                onConfirm = { newName ->
                    viewModel.onIntent(ScriptsIntent.Rename(dialog.script, newName))
                },
                onDismiss = { viewModel.onIntent(ScriptsIntent.DismissDialog) },
            )

        is ScriptsDialog.ConfirmDelete ->
            AlertDialog(
                onDismissRequest = { viewModel.onIntent(ScriptsIntent.DismissDialog) },
                title = { Text("删除脚本") },
                text = {
                    Text("确定删除脚本「${dialog.script.name}」吗？此操作不可恢复。")
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.onIntent(ScriptsIntent.ConfirmDelete(dialog.script))
                    }) { Text("删除") }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.onIntent(ScriptsIntent.DismissDialog) }) {
                        Text("取消")
                    }
                },
            )
    }
}

// =================================================================================
// 子组件
// =================================================================================

/** 单个脚本卡片：名称 + 步骤数，右侧运行/重命名/复制/删除/导出操作 */
@Composable
private fun ScriptCard(
    script: Script,
    onOpen: () -> Unit,
    onRun: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 点击名称区域进入编辑器（IconButton 自身消费点击，互不干扰）
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onOpen)
                    .padding(vertical = 4.dp)
            ) {
                Text(script.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${countSteps(script.steps)} 个步骤",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onRun) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "运行脚本")
            }
            IconButton(onClick = onRename) {
                Icon(Icons.Filled.DriveFileRenameOutline, contentDescription = "重命名")
            }
            IconButton(onClick = onDuplicate) {
                Icon(Icons.Filled.ContentCopy, contentDescription = "复制脚本")
            }
            IconButton(onClick = onExport) {
                Icon(Icons.Filled.FileUpload, contentDescription = "导出脚本")
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "删除脚本",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** 空列表状态 */
@Composable
private fun EmptyScripts(
    onCreate: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("还没有脚本", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "新建脚本后可添加点击、滑动、延时等动作，也可把常用步骤封装成循环段或函数包。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onCreate) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("新建脚本")
            }
            OutlinedButton(onClick = onImport) {
                Icon(Icons.Filled.FileUpload, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("导入脚本")
            }
        }
    }
}

/** 名称输入弹窗（重命名用），空白名称不可确认 */
@Composable
private fun NameInputDialog(
    title: String,
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("脚本名称") },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank(),
            ) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

// =================================================================================
// MVI：状态 / 意图 / 弹窗
// =================================================================================

/** 脚本列表页 UI 状态（单一数据源） */
data class ScriptsUiState(
    val loading: Boolean = true,
    val scripts: List<Script> = emptyList(),
    val dialog: ScriptsDialog? = null,
    val message: ScriptsMessage? = null,
    /** 一次性编辑器导航事件 */
    val openEvent: OpenScriptEvent? = null,
)

/** 一次性提示消息（带序号，相同文本也能重新触发） */
data class ScriptsMessage(
    val seq: Long,
    val text: String,
)

/** 一次性打开编辑器事件 */
data class OpenScriptEvent(
    val seq: Long,
    val scriptId: String,
)

/** 弹窗类型 */
sealed interface ScriptsDialog {
    data class Rename(val script: Script) : ScriptsDialog
    data class ConfirmDelete(val script: Script) : ScriptsDialog
}

/** 用户意图 */
sealed interface ScriptsIntent {
    data object CreateNew : ScriptsIntent
    data class Open(val scriptId: String) : ScriptsIntent
    data object OpenConsumed : ScriptsIntent
    data class Run(val script: Script) : ScriptsIntent
    data class ShowRenameDialog(val script: Script) : ScriptsIntent
    data class Rename(val script: Script, val name: String) : ScriptsIntent
    data class Duplicate(val script: Script) : ScriptsIntent
    data class RequestDelete(val script: Script) : ScriptsIntent
    data class ConfirmDelete(val script: Script) : ScriptsIntent
    data object DismissDialog : ScriptsIntent
    data object MessageShown : ScriptsIntent
}

// =================================================================================
// ViewModel
// =================================================================================

/**
 * 脚本列表页 ViewModel：持有单一 [ScriptsUiState]，所有 IO 经 viewModelScope 发起。
 *
 * @param app 应用实例（用于 coordinator 运行脚本、contentResolver 读写 SAF 文件）
 * @param repository 脚本文件仓库
 */
class ScriptsViewModel(
    private val app: MyApplication,
    private val repository: ScriptFileRepository,
) : ViewModel() {

    private val appContext: Context = app.applicationContext

    private val _state = MutableStateFlow(ScriptsUiState())
    val state: StateFlow<ScriptsUiState> = _state.asStateFlow()

    private var messageSeq: Long = 0
    private var openSeq: Long = 0

    init {
        refresh()
    }

    fun onIntent(intent: ScriptsIntent) {
        when (intent) {
            ScriptsIntent.CreateNew -> createNew()

            is ScriptsIntent.Open ->
                _state.update { it.copy(openEvent = OpenScriptEvent(nextOpenSeq(), intent.scriptId)) }

            ScriptsIntent.OpenConsumed ->
                _state.update { it.copy(openEvent = null) }

            is ScriptsIntent.Run -> runScript(intent.script)

            is ScriptsIntent.ShowRenameDialog ->
                _state.update { it.copy(dialog = ScriptsDialog.Rename(intent.script)) }

            is ScriptsIntent.Rename -> rename(intent.script, intent.name)
            is ScriptsIntent.Duplicate -> duplicate(intent.script)
            is ScriptsIntent.RequestDelete ->
                _state.update { it.copy(dialog = ScriptsDialog.ConfirmDelete(intent.script)) }

            is ScriptsIntent.ConfirmDelete -> delete(intent.script)

            ScriptsIntent.DismissDialog ->
                _state.update { it.copy(dialog = null) }

            ScriptsIntent.MessageShown ->
                _state.update { it.copy(message = null) }
        }
    }

    /** 从仓库重新加载列表 */
    private fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val result = runCatching { repository.list() }
            _state.update { current ->
                result.fold(
                    onSuccess = { scripts -> current.copy(loading = false, scripts = scripts) },
                    onFailure = { e ->
                        current.copy(
                            loading = false,
                            message = ScriptsMessage(nextMessageSeq(), "加载脚本失败：${e.message ?: "未知错误"}"),
                        )
                    },
                )
            }
        }
    }

    /** 新建空步骤脚本：先持久化，再派发打开编辑器事件 */
    private fun createNew() {
        viewModelScope.launch {
            val script = Script(
                id = repository.newId(),
                name = "新建脚本",
                steps = emptyList(),
            )
            val result = runCatching { repository.upsert(script) }
            result.fold(
                onSuccess = {
                    _state.update {
                        it.copy(openEvent = OpenScriptEvent(nextOpenSeq(), script.id))
                    }
                    refresh()
                },
                onFailure = { e ->
                    postMessage("新建脚本失败：${e.message ?: "未知错误"}")
                },
            )
        }
    }

    /** 运行脚本：空脚本直接给出中文提示，不进入引擎 */
    private fun runScript(script: Script) {
        if (script.steps.isEmpty()) {
            postMessage("脚本为空，请先进入编辑器添加步骤")
            return
        }
        app.coordinator.startScript(script)
        postMessage("已开始运行：${script.name}")
    }

    /** 重命名：仅改名称，保留 id 与步骤树 */
    private fun rename(script: Script, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) {
            postMessage("脚本名称不能为空")
            return
        }
        mutate { repository.upsert(script.copy(name = trimmed)) }
    }

    /** 复制：脚本新 id、步骤树全部节点重新分配 id，名称追加「副本」 */
    private fun duplicate(script: Script) = mutate {
        val copy = Script(
            id = repository.newId(),
            name = "${script.name} 副本",
            steps = script.steps.regenIds(),
            repeatPolicy = script.repeatPolicy,
        )
        repository.upsert(copy)
    }

    /** 删除脚本 */
    private fun delete(script: Script) = mutate {
        repository.delete(script.id)
    }

    /**
     * 执行一次写操作：成功后关闭弹窗并重新加载；
     * 失败（含仓库 StructureValidator 的中文校验原因）通过 Snackbar 展示。
     */
    private fun mutate(block: suspend () -> Unit) {
        viewModelScope.launch {
            val result = runCatching { block() }
            val error = result.exceptionOrNull()
            if (error != null) {
                postMessage(error.message ?: "操作失败，请重试")
                return@launch
            }
            _state.update { it.copy(dialog = null) }
            refresh()
        }
    }

    /**
     * 导出脚本：把 [ScriptCodec.encode] 的 JSON 文本直接写入 SAF 创建的文件。
     */
    fun exportTo(script: Script, uri: Uri) {
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val text = ScriptCodec.encode(script)
                    appContext.contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.write(text.toByteArray(Charsets.UTF_8))
                    } ?: throw IllegalStateException("无法打开要写入的文件")
                }
            }
            result.fold(
                onSuccess = { postMessage("已导出：${script.name}.json") },
                onFailure = { postMessage("导出失败：${it.message ?: "未知错误"}") },
            )
        }
    }

    /**
     * 导入脚本：SAF 读取文本 → [ScriptCodec.decode] → 分配新脚本 id 与新步骤 id → upsert。
     * 任何失败（文件不可读、JSON 损坏、版本不支持、结构非法）均给中文 Snackbar。
     */
    fun importFrom(uri: Uri) {
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val text = appContext.contentResolver.openInputStream(uri)?.use { stream ->
                        stream.readBytes().toString(Charsets.UTF_8)
                    } ?: throw IllegalStateException("无法读取所选文件")
                    val decoded = ScriptCodec.decode(text)
                    val reidentified = decoded.copy(
                        id = repository.newId(),
                        steps = decoded.steps.regenIds(),
                    )
                    repository.upsert(reidentified)
                }
            }
            result.fold(
                onSuccess = {
                    postMessage("导入成功")
                    refresh()
                },
                onFailure = { postMessage("导入失败：${it.message ?: "文件不是有效的脚本 JSON"}") },
            )
        }
    }

    private fun nextMessageSeq(): Long {
        messageSeq += 1
        return messageSeq
    }

    private fun nextOpenSeq(): Long {
        openSeq += 1
        return openSeq
    }

    private fun postMessage(text: String) {
        _state.update { it.copy(message = ScriptsMessage(nextMessageSeq(), text)) }
    }
}

// =================================================================================
// 跨文件共享的步骤树工具（同包 com.autoclicker.ui.scripts，编辑器复用）
// =================================================================================

/** 生成步骤节点新 id */
internal fun newStepId(): String = "step-${UUID.randomUUID()}"

/**
 * 为步骤树中每个节点重新分配 id（深拷贝）。
 * - BasicStep / PackageCall：仅换节点 id，动作内容与 packageId 不变；
 * - LoopGroup：换 id 并递归处理子节点。
 */
internal fun ScriptStep.regenIds(): ScriptStep = when (this) {
    is ScriptStep.BasicStep -> copy(id = newStepId())
    is ScriptStep.PackageCall -> copy(id = newStepId())
    is ScriptStep.LoopGroup -> copy(
        id = newStepId(),
        steps = steps.map { it.regenIds() },
    )
}

/** 列表版本的重新分配 id */
internal fun List<ScriptStep>.regenIds(): List<ScriptStep> = map { it.regenIds() }

/**
 * 统计步骤总数：顶层节点各计 1，循环段内子节点递归计入；
 * PackageCall 计 1（不展开包内步骤）。
 */
internal fun countSteps(steps: List<ScriptStep>): Int =
    steps.sumOf { step ->
        1 + if (step is ScriptStep.LoopGroup) countSteps(step.steps) else 0
    }

/**
 * 返回包含 [targetId] 节点的父层级列表（按引用相等判断）；
 * 节点位于根层级时返回根列表，找不到返回 null。
 */
internal fun parentListOf(
    steps: List<ScriptStep>,
    targetId: String,
): List<ScriptStep>? {
    if (steps.any { it.id == targetId }) return steps
    steps.forEach { node ->
        if (node is ScriptStep.LoopGroup) {
            parentListOf(node.steps, targetId)?.let { return it }
        }
    }
    return null
}

/** 动作中文摘要（列表页与编辑器共用，写法参考 RecordingScreen） */
internal fun Action?.summaryZh(): String = when (this) {
    is Action.Tap -> "点击 ($x, $y)"
    is Action.LongPress -> "长按 ($x, $y) ${durationMs}ms"
    is Action.Swipe -> "滑动 ($x1,$y1)→($x2,$y2) ${durationMs}ms"
    is Action.Delay -> "等待 ${durationMs}ms"
    is Action.WaitImage -> "等待识图：$templateId"
    Action.GlobalHome -> "Home 键"
    Action.GlobalBack -> "返回键"
    null -> ""
}
