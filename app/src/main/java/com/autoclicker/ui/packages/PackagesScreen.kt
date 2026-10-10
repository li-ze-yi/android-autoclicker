@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.autoclicker.ui.packages

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.autoclicker.core.data.packages.FunctionPackageRepository
import com.autoclicker.core.data.packages.ReferenceChecker
import com.autoclicker.core.data.scripts.ScriptFileRepository
import com.autoclicker.domain.model.FunctionPackage
import com.autoclicker.domain.model.ScriptStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// =================================================================================
// 页面入口
// =================================================================================

/**
 * 函数包列表页（FR-6B / Task 12A）。
 *
 * 只负责函数包列表与元数据操作：新建、重命名、复制、删除（二次确认 + 引用保护）。
 * 点击卡片主体经 [onOpenPackage] 回调打开函数包内容编辑器（PackageEditorScreen），
 * 函数包是独立实体、内容可编辑（FR-6B）。
 *
 * 自带 ViewModel 工厂：以 applicationContext 构造 [FunctionPackageRepository]。
 *
 * @param onOpenPackage 打开函数包内容编辑器回调，参数为函数包 id（导航层接入）
 */
@Composable
fun PackagesScreen(
    onOpenPackage: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val appContext = LocalContext.current.applicationContext
    val viewModel: PackagesViewModel = viewModel(
        factory = remember(appContext) {
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val repository = FunctionPackageRepository(appContext)
                    // 真实引用检查器：扫描全部脚本步骤树中的 PackageCall（删除保护）
                    val referenceChecker = ScriptsReferenceChecker(
                        scriptRepository = ScriptFileRepository(appContext),
                    )
                    @Suppress("UNCHECKED_CAST")
                    return PackagesViewModel(
                        repository = repository,
                        referenceChecker = referenceChecker,
                    ) as T
                }
            }
        }
    )
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // 一次性消息：以消息对象为 key，相同文本的新消息也能再次弹出
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message.text)
        viewModel.onIntent(PackagesIntent.MessageShown)
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("函数包") },
                actions = {
                    IconButton(onClick = { viewModel.onIntent(PackagesIntent.ShowCreateDialog) }) {
                        Icon(Icons.Filled.Add, contentDescription = "新建函数包")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(modifier = Modifier
            .padding(padding)
            .fillMaxSize()) {
            when {
                state.loading ->
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

                state.packages.isEmpty() ->
                    EmptyPackages(
                        onCreate = { viewModel.onIntent(PackagesIntent.ShowCreateDialog) },
                        modifier = Modifier.align(Alignment.Center),
                    )

                else ->
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(state.packages, key = { it.id }) { pkg ->
                            PackageCard(
                                pkg = pkg,
                                expanded = state.expandedId == pkg.id,
                                onToggleExpand = {
                                    viewModel.onIntent(PackagesIntent.ToggleExpand(pkg))
                                },
                                onClickContent = {
                                    onOpenPackage(pkg.id)
                                },
                                onRename = {
                                    viewModel.onIntent(PackagesIntent.ShowRenameDialog(pkg))
                                },
                                onDuplicate = {
                                    viewModel.onIntent(PackagesIntent.Duplicate(pkg))
                                },
                                onDelete = {
                                    viewModel.onIntent(PackagesIntent.RequestDelete(pkg))
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
        is PackagesDialog.Create ->
            NameInputDialog(
                title = "新建函数包",
                initialName = "",
                onConfirm = { viewModel.onIntent(PackagesIntent.Create(it)) },
                onDismiss = { viewModel.onIntent(PackagesIntent.DismissDialog) },
            )

        is PackagesDialog.Rename ->
            NameInputDialog(
                title = "重命名函数包",
                initialName = dialog.pkg.name,
                onConfirm = { viewModel.onIntent(PackagesIntent.Rename(dialog.pkg, it)) },
                onDismiss = { viewModel.onIntent(PackagesIntent.DismissDialog) },
            )

        is PackagesDialog.ConfirmDelete ->
            AlertDialog(
                onDismissRequest = { viewModel.onIntent(PackagesIntent.DismissDialog) },
                title = { Text("删除函数包") },
                text = {
                    Text("确定删除函数包「${dialog.pkg.name}」吗？此操作不可撤销。")
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.onIntent(PackagesIntent.ConfirmDelete(dialog.pkg))
                    }) {
                        Text("删除")
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        viewModel.onIntent(PackagesIntent.DismissDialog)
                    }) {
                        Text("取消")
                    }
                },
            )
    }
}

// =================================================================================
// 子组件
// =================================================================================

/** 单个函数包卡片：点击主体触发打开内容编辑器回调，展开后显示元数据操作 */
@Composable
private fun PackageCard(
    pkg: FunctionPackage,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onClickContent: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClickContent)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = pkg.name,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "${countSteps(pkg.steps)} 个步骤",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onToggleExpand) {
                    Icon(
                        imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "收起操作" else "展开操作",
                    )
                }
            }
            AnimatedVisibility(visible = expanded) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(onClick = onRename) {
                        Icon(Icons.Filled.DriveFileRenameOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("重命名")
                    }
                    TextButton(onClick = onDuplicate) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("复制")
                    }
                    TextButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("删除")
                    }
                }
            }
        }
    }
}

/** 空列表状态 */
@Composable
private fun EmptyPackages(
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("还没有函数包", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "函数包是一组可复用的步骤，可在任意脚本中通过「调用函数包」引用。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onCreate) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("新建函数包")
        }
    }
}

/** 名称输入弹窗（新建 / 重命名共用），空白名称不可确认 */
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
                label = { Text("名称") },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank(),
            ) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

// =================================================================================
// MVI：状态 / 意图 / 弹窗
// =================================================================================

/** 函数包列表页 UI 状态（单一数据源） */
data class PackagesUiState(
    val loading: Boolean = true,
    val packages: List<FunctionPackage> = emptyList(),
    /** 当前展开操作区的函数包 ID，同一时刻只展开一个 */
    val expandedId: String? = null,
    val dialog: PackagesDialog? = null,
    val message: PackagesMessage? = null,
)

/** 一次性提示消息（带序号，确保相同文本也能重新触发 Snackbar） */
data class PackagesMessage(
    val seq: Long,
    val text: String,
)

/** 弹窗类型 */
sealed interface PackagesDialog {
    data object Create : PackagesDialog
    data class Rename(val pkg: FunctionPackage) : PackagesDialog
    data class ConfirmDelete(val pkg: FunctionPackage) : PackagesDialog
}

/** 用户意图 */
sealed interface PackagesIntent {
    data object Refresh : PackagesIntent
    data object ShowCreateDialog : PackagesIntent
    data class Create(val name: String) : PackagesIntent
    data class ShowRenameDialog(val pkg: FunctionPackage) : PackagesIntent
    data class Rename(val pkg: FunctionPackage, val name: String) : PackagesIntent
    data class Duplicate(val pkg: FunctionPackage) : PackagesIntent
    data class RequestDelete(val pkg: FunctionPackage) : PackagesIntent
    data class ConfirmDelete(val pkg: FunctionPackage) : PackagesIntent
    data object DismissDialog : PackagesIntent
    data class ToggleExpand(val pkg: FunctionPackage) : PackagesIntent
    data object MessageShown : PackagesIntent
}

// =================================================================================
// ViewModel
// =================================================================================

/**
 * 函数包列表页 ViewModel：持有单一 [PackagesUiState]，所有 IO 操作经 viewModelScope 发起。
 *
 * @param repository 函数包文件仓库
 * @param referenceChecker 删除引用检查；由页面工厂注入真实实现
 *        [ScriptsReferenceChecker]（扫描全部脚本步骤树中的 PackageCall）
 */
class PackagesViewModel(
    private val repository: FunctionPackageRepository,
    private val referenceChecker: ReferenceChecker,
) : ViewModel() {

    private val _state = MutableStateFlow(PackagesUiState())
    val state: StateFlow<PackagesUiState> = _state.asStateFlow()

    /** 消息自增序号 */
    private var messageSeq: Long = 0

    init {
        refresh()
    }

    fun onIntent(intent: PackagesIntent) {
        when (intent) {
            PackagesIntent.Refresh -> refresh()
            PackagesIntent.ShowCreateDialog ->
                _state.update { it.copy(dialog = PackagesDialog.Create) }

            is PackagesIntent.Create -> createPackage(intent.name)

            is PackagesIntent.ShowRenameDialog ->
                _state.update { it.copy(dialog = PackagesDialog.Rename(intent.pkg)) }

            is PackagesIntent.Rename -> renamePackage(intent.pkg, intent.name)
            is PackagesIntent.Duplicate -> duplicatePackage(intent.pkg)
            is PackagesIntent.RequestDelete -> requestDelete(intent.pkg)
            is PackagesIntent.ConfirmDelete -> confirmDelete(intent.pkg)

            PackagesIntent.DismissDialog ->
                _state.update { it.copy(dialog = null) }

            is PackagesIntent.ToggleExpand ->
                _state.update { current ->
                    current.copy(
                        expandedId = if (current.expandedId == intent.pkg.id) null else intent.pkg.id
                    )
                }

            PackagesIntent.MessageShown ->
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
                    onSuccess = { packages -> current.copy(loading = false, packages = packages) },
                    onFailure = { e ->
                        current.copy(
                            loading = false,
                            message = PackagesMessage(nextSeq(), "加载函数包失败：${e.message ?: "未知错误"}"),
                        )
                    },
                )
            }
        }
    }

    /** 新建：空步骤列表，名称去空白后不能为空 */
    private fun createPackage(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            postMessage("函数包名称不能为空")
            return
        }
        mutate {
            val pkg = FunctionPackage(
                id = repository.newId(),
                name = trimmed,
                steps = emptyList(),
            )
            repository.upsert(pkg)
        }
    }

    /** 重命名：仅改名称，保留 id 与步骤 */
    private fun renamePackage(pkg: FunctionPackage, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) {
            postMessage("函数包名称不能为空")
            return
        }
        mutate { repository.upsert(pkg.copy(name = trimmed)) }
    }

    /** 复制：生成新 ID，名称追加“副本”，步骤整体复制 */
    private fun duplicatePackage(pkg: FunctionPackage) = mutate {
        val copy = pkg.copy(
            id = repository.newId(),
            name = "${pkg.name} 副本",
        )
        repository.upsert(copy)
    }

    /**
     * 删除第一步：引用检查。
     * 被引用则默认阻止删除并提示先解除引用（FR-6B / AC-16）；
     * 未被引用才弹出二次确认对话框。
     */
    private fun requestDelete(pkg: FunctionPackage) {
        viewModelScope.launch {
            // 调用点位于 IO：兼容 Task 12 接入的阻塞式扫描实现
            val referenced = runCatching {
                withContext(Dispatchers.IO) { referenceChecker.isReferenced(pkg.id) }
            }.getOrDefault(false)
            if (referenced) {
                // Task 12 接入真实实现后，此处改为弹窗并列出引用它的脚本来源
                postMessage("函数包「${pkg.name}」正被脚本引用，请先解除引用后再删除")
            } else {
                _state.update { it.copy(dialog = PackagesDialog.ConfirmDelete(pkg)) }
            }
        }
    }

    /**
     * 删除第二步：二次确认后的最终删除。
     * 此处保留删除前的最终引用检查调用点（Task 12 接入真实检查），
     * 确认后状态变为被引用则中止删除。
     */
    private fun confirmDelete(pkg: FunctionPackage) = mutate {
        val stillReferenced = withContext(Dispatchers.IO) {
            referenceChecker.isReferenced(pkg.id)
        }
        require(!stillReferenced) { "函数包「${pkg.name}」正被脚本引用，请先解除引用" }
        repository.delete(pkg.id)
    }

    /**
     * 执行一次写操作：成功则关闭弹窗并重新加载列表；
     * 失败则把异常消息（如仓库校验抛出的中文原因）通过 Snackbar 展示。
     */
    private fun mutate(block: suspend () -> Unit) {
        viewModelScope.launch {
            val result = runCatching { block() }
            val error = result.exceptionOrNull()
            if (error != null) {
                postMessage(error.message ?: "操作失败，请重试")
                return@launch
            }
            val refreshed = runCatching { repository.list() }
            refreshed.onSuccess { packages ->
                _state.update { current ->
                    val existingIds = packages.map { it.id }.toSet()
                    current.copy(
                        packages = packages,
                        dialog = null,
                        expandedId = current.expandedId?.takeIf { it in existingIds },
                    )
                }
            }
        }
    }

    private fun nextSeq(): Long {
        messageSeq += 1
        return messageSeq
    }

    private fun postMessage(text: String) {
        _state.update { it.copy(message = PackagesMessage(nextSeq(), text)) }
    }
}

// =================================================================================
// 删除保护：真实引用检查器
// =================================================================================

/**
 * 基于脚本文件仓库的真实引用检查器（删除保护，FR-6B / AC-16）。
 *
 * 扫描全部脚本的步骤树（含 [ScriptStep.LoopGroup] 任意嵌套）中的
 * [ScriptStep.PackageCall]，只要任一脚本以相同 packageId 调用目标函数包，
 * 即判定为被引用、阻止删除。
 *
 * 注意：[isReferenced] 为阻塞式实现（内部以 runBlocking 读脚本文件），
 * 调用方必须在 [Dispatchers.IO] 上调用——PackagesViewModel 的两处调用点
 * （requestDelete / confirmDelete）均已按此约定包在 withContext(Dispatchers.IO) 内。
 */
private class ScriptsReferenceChecker(
    private val scriptRepository: ScriptFileRepository,
) : ReferenceChecker {

    override fun isReferenced(packageId: String): Boolean {
        // 阻塞调用：UI 已在 Dispatchers.IO 内调用本方法；
        // 读取失败（如目录/文件异常）时保守视为未引用，避免误锁死删除入口
        val scripts = runCatching {
            kotlinx.coroutines.runBlocking { scriptRepository.list() }
        }.getOrNull() ?: return false
        return scripts.any { script -> containsCall(script.steps, packageId) }
    }
}

// =================================================================================
// 工具
// =================================================================================

/**
 * 递归判断步骤树中是否存在对指定函数包的调用：
 * BasicStep 无引用；LoopGroup 递归其内部 steps；PackageCall 比较 packageId。
 */
private fun containsCall(steps: List<ScriptStep>, packageId: String): Boolean =
    steps.any { step ->
        when (step) {
            is ScriptStep.BasicStep -> false
            is ScriptStep.LoopGroup -> containsCall(step.steps, packageId)
            is ScriptStep.PackageCall -> step.packageId == packageId
        }
    }

/**
 * 统计步骤总数：顶层步骤各计 1，循环段内的嵌套步骤递归计入；
 * 函数包调用计 1（包内步骤不在此展开）。
 */
private fun countSteps(steps: List<ScriptStep>): Int =
    steps.sumOf { step ->
        1 + if (step is ScriptStep.LoopGroup) countSteps(step.steps) else 0
    }
