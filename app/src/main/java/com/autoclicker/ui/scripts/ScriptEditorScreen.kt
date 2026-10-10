@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.autoclicker.ui.scripts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.autoclicker.MyApplication
import com.autoclicker.core.data.packages.FunctionPackageRepository
import com.autoclicker.core.data.scripts.ScriptFileRepository
import com.autoclicker.core.data.templates.ImageTemplateMetadata
import com.autoclicker.core.data.templates.ImageTemplateRepository
import com.autoclicker.domain.model.Action
import com.autoclicker.domain.model.FunctionPackage
import com.autoclicker.domain.model.Rect
import com.autoclicker.domain.model.RepeatPolicy
import com.autoclicker.domain.model.Script
import com.autoclicker.domain.model.ScriptStep
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// =================================================================================
// 页面入口
// =================================================================================

/**
 * 步骤编辑器（FR-6 / FR-6A / FR-6B，Task 12）。
 *
 * 功能：
 * - 加载脚本后在内存中编辑步骤树，顶部「保存」经仓库 StructureValidator 校验；
 * - 步骤树按层级缩进渲染：BasicStep 显示动作摘要、LoopGroup 显示名称与次数且可折叠、
 *   PackageCall 经函数包仓库解析显示包名；
 * - 单步：编辑参数（按动作类型的数字表单）、删除、同层级上移/下移；
 * - 底部菜单添加：点击/长按/滑动/延时/Home/返回，以及插入函数包调用；
 * - 多选同层级「起始步 ~ 结束步」一键设为循环段（输入次数 N）；
 *   LoopGroup 支持改名、改次数、解段、整段删除；
 * - 选中步骤另存为全局函数包。
 *
 * @param scriptId 要编辑的脚本 id
 * @param onBack 返回回调（由导航层接入）
 */
@Composable
fun ScriptEditorScreen(
    scriptId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val app = LocalContext.current.applicationContext as MyApplication
    val viewModel: ScriptEditorViewModel = viewModel(
        factory = remember(app, scriptId) {
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    @Suppress("UNCHECKED_CAST")
                    return ScriptEditorViewModel(
                        app = app,
                        scriptId = scriptId,
                        scriptRepository = ScriptFileRepository(app),
                        packageRepository = FunctionPackageRepository(app),
                        templateRepository = ImageTemplateRepository(app),
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
        viewModel.onIntent(EditorIntent.MessageShown)
    }

    when {
        state.loading ->
            Scaffold(modifier = modifier) { padding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

        state.notFound ->
            Scaffold(
                modifier = modifier,
                topBar = {
                    TopAppBar(
                        title = { Text("编辑脚本") },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                            }
                        },
                    )
                },
            ) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("脚本不存在或已被删除", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onBack) { Text("返回") }
                }
            }

        else ->
            EditorScaffold(
                state = state,
                onBack = onBack,
                onIntent = viewModel::onIntent,
                snackbarHostState = snackbarHostState,
                modifier = modifier,
            )
    }
}

// =================================================================================
// 主编排骨架
// =================================================================================

/** 正常编辑态的 Scaffold（加载失败态在外层处理） */
@Composable
private fun EditorScaffold(
    state: EditorUiState,
    onBack: () -> Unit,
    onIntent: (EditorIntent) -> Unit,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("编辑脚本") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = { onIntent(EditorIntent.Save) }) { Text("保存") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
        ) {
            // 脚本名称
            OutlinedTextField(
                value = state.scriptName,
                onValueChange = { onIntent(EditorIntent.UpdateName(it)) },
                label = { Text("脚本名称") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )

            // 步骤统计 + 多选开关
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "共 ${countSteps(state.steps)} 步",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (state.selectionMode) {
                    OutlinedButton(onClick = { onIntent(EditorIntent.ClearSelection) }) {
                        Text("退出多选")
                    }
                } else {
                    OutlinedButton(onClick = { onIntent(EditorIntent.ToggleSelectionMode) }) {
                        Text("多选")
                    }
                }
            }

            // 可见步骤节点（折叠的 LoopGroup 不展开子节点）
            val visible = remember(state.steps, state.collapsed) {
                buildList { flattenVisible(state.steps, depth = 0, collapsed = state.collapsed, out = this) }
            }
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(top = 4.dp),
            ) {
                items(visible, key = { it.step.id }) { flat ->
                    val pos = positionOf(state.steps, flat.step.id)
                    StepRowItem(
                        flat = flat,
                        canMoveUp = pos != null && pos.index > 0,
                        canMoveDown = pos != null && pos.index < pos.size - 1,
                        selectionMode = state.selectionMode,
                        selected = flat.step.id in state.selectedIds,
                        collapsed = flat.step is ScriptStep.LoopGroup &&
                            flat.step.id in state.collapsed,
                        packageName = (flat.step as? ScriptStep.PackageCall)
                            ?.let { state.packageNames[it.packageId] },
                        onToggleSelect = { onIntent(EditorIntent.ToggleSelect(flat.step.id)) },
                        onBodyClick = {
                            when (flat.step) {
                                is ScriptStep.BasicStep ->
                                    onIntent(EditorIntent.EditStep(flat.step.id))

                                is ScriptStep.LoopGroup ->
                                    onIntent(EditorIntent.ToggleCollapse(flat.step.id))

                                is ScriptStep.PackageCall -> Unit
                            }
                        },
                        onToggleCollapse = {
                            onIntent(EditorIntent.ToggleCollapse(flat.step.id))
                        },
                        onEdit = {
                            when (val step = flat.step) {
                                is ScriptStep.BasicStep ->
                                    onIntent(EditorIntent.EditStep(step.id))

                                is ScriptStep.LoopGroup ->
                                    onIntent(EditorIntent.EditLoop(step))

                                is ScriptStep.PackageCall -> Unit
                            }
                        },
                        onUngroup = { onIntent(EditorIntent.Ungroup(flat.step.id)) },
                        onMoveUp = { onIntent(EditorIntent.Move(flat.step.id, up = true)) },
                        onMoveDown = { onIntent(EditorIntent.Move(flat.step.id, up = false)) },
                        onDelete = { onIntent(EditorIntent.Delete(flat.step.id)) },
                    )
                }
            }

            // 底部操作条：多选模式显示封装操作；否则显示添加动作菜单
            if (state.selectionMode) {
                SelectionActionBar(
                    selectedCount = state.selectedIds.size,
                    onWrap = { onIntent(EditorIntent.RequestWrap) },
                    onSaveAsPackage = { onIntent(EditorIntent.RequestSaveAsPackage) },
                    onCancel = { onIntent(EditorIntent.ClearSelection) },
                )
            } else {
                AddActionBar(onIntent = onIntent)
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    // 弹窗层
    when (val dialog = state.dialog) {
        null -> Unit

        is EditorDialog.CreateAction ->
            ActionFormDialog(
                title = "添加动作",
                initial = dialog.action,
                templates = state.templates,
                onConfirm = { onIntent(EditorIntent.AddStep(it)) },
                onDismiss = { onIntent(EditorIntent.DismissDialog) },
            )

        is EditorDialog.EditAction ->
            ActionFormDialog(
                title = "编辑动作",
                initial = dialog.action,
                templates = state.templates,
                onConfirm = { onIntent(EditorIntent.UpdateAction(dialog.stepId, it)) },
                onDismiss = { onIntent(EditorIntent.DismissDialog) },
            )

        is EditorDialog.WrapSettings ->
            WrapFormDialog(
                onConfirm = { name, count ->
                    onIntent(
                        EditorIntent.ConfirmWrap(
                            startId = dialog.startId,
                            endId = dialog.endId,
                            name = name,
                            count = count,
                        )
                    )
                },
                onDismiss = { onIntent(EditorIntent.DismissDialog) },
            )

        is EditorDialog.LoopSettings ->
            LoopFormDialog(
                initial = dialog.loop,
                onConfirm = { name, count ->
                    onIntent(EditorIntent.UpdateLoop(dialog.loop.id, name, count))
                },
                onDismiss = { onIntent(EditorIntent.DismissDialog) },
            )

        EditorDialog.SaveAsPackageName ->
            TextInputDialog(
                title = "另存为函数包",
                initialText = "",
                label = "函数包名称",
                onConfirm = { onIntent(EditorIntent.ConfirmSaveAsPackage(it)) },
                onDismiss = { onIntent(EditorIntent.DismissDialog) },
            )

        EditorDialog.InsertPackageCall ->
            InsertPackageCallDialog(
                packages = state.packages,
                onConfirm = { onIntent(EditorIntent.InsertPackageCall(it)) },
                onDismiss = { onIntent(EditorIntent.DismissDialog) },
            )

        is EditorDialog.SaveFailed ->
            AlertDialog(
                onDismissRequest = { onIntent(EditorIntent.DismissDialog) },
                title = { Text("保存失败") },
                text = { Text(dialog.reason) },
                confirmButton = {
                    TextButton(onClick = { onIntent(EditorIntent.DismissDialog) }) {
                        Text("知道了")
                    }
                },
            )
    }
}

/** 多选模式底部操作条 */
@Composable
private fun SelectionActionBar(
    selectedCount: Int,
    onWrap: () -> Unit,
    onSaveAsPackage: () -> Unit,
    onCancel: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "已选 $selectedCount 项",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onWrap) { Text("设为循环段") }
            TextButton(onClick = onSaveAsPackage) { Text("另存为函数包") }
            TextButton(onClick = onCancel) { Text("取消") }
        }
    }
}

/** 普通模式底部「添加动作」菜单 */
@Composable
private fun AddActionBar(onIntent: (EditorIntent) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Box {
            Button(onClick = { expanded = true }) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("添加动作")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text("点击") },
                    onClick = {
                        expanded = false
                        onIntent(EditorIntent.OpenCreate(Action.Tap(x = 0, y = 0)))
                    },
                )
                DropdownMenuItem(
                    text = { Text("长按") },
                    onClick = {
                        expanded = false
                        onIntent(EditorIntent.OpenCreate(Action.LongPress(x = 0, y = 0, durationMs = 1000)))
                    },
                )
                DropdownMenuItem(
                    text = { Text("滑动") },
                    onClick = {
                        expanded = false
                        onIntent(
                            EditorIntent.OpenCreate(
                                Action.Swipe(x1 = 0, y1 = 0, x2 = 0, y2 = 0, durationMs = 300)
                            )
                        )
                    },
                )
                DropdownMenuItem(
                    text = { Text("延时") },
                    onClick = {
                        expanded = false
                        onIntent(EditorIntent.OpenCreate(Action.Delay(durationMs = 500)))
                    },
                )
                DropdownMenuItem(
                    text = { Text("等待识图") },
                    onClick = {
                        expanded = false
                        // templateId 先留空，由表单下拉选择；默认超时 5 秒、全屏、找到后点击
                        onIntent(
                            EditorIntent.OpenCreate(
                                Action.WaitImage(templateId = "", timeoutMs = DEFAULT_WAIT_TIMEOUT_MS)
                            )
                        )
                    },
                )
                DropdownMenuItem(
                    text = { Text("Home 键") },
                    onClick = {
                        expanded = false
                        onIntent(EditorIntent.AddStep(Action.GlobalHome))
                    },
                )
                DropdownMenuItem(
                    text = { Text("返回键") },
                    onClick = {
                        expanded = false
                        onIntent(EditorIntent.AddStep(Action.GlobalBack))
                    },
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("插入函数包调用") },
                    onClick = {
                        expanded = false
                        onIntent(EditorIntent.ShowInsertCall)
                    },
                )
            }
        }
    }
}

// =================================================================================
// 步骤行
// =================================================================================

/** 单个步骤行：按层级缩进；多选模式显示复选框，否则显示编辑/移动/删除操作 */
@Composable
private fun StepRowItem(
    flat: FlatNode,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    collapsed: Boolean,
    packageName: String?,
    onToggleSelect: () -> Unit,
    onBodyClick: () -> Unit,
    onToggleCollapse: () -> Unit,
    onEdit: () -> Unit,
    onUngroup: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    val step = flat.step
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = (8 + flat.depth * 18).dp,
                end = 8.dp,
                top = 3.dp,
                bottom = 3.dp,
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                selectionMode ->
                    Checkbox(checked = selected, onCheckedChange = { onToggleSelect() })

                step is ScriptStep.LoopGroup ->
                    IconButton(
                        onClick = onToggleCollapse,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = if (collapsed) Icons.Filled.ChevronRight else Icons.Filled.ExpandMore,
                            contentDescription = if (collapsed) "展开循环段" else "折叠循环段",
                            modifier = Modifier.size(20.dp),
                        )
                    }
            }

            // 主体内容
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onBodyClick)
                    .padding(vertical = 4.dp),
            ) {
                when (step) {
                    is ScriptStep.BasicStep ->
                        Text(
                            text = step.action.summaryZh(),
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                        )

                    is ScriptStep.LoopGroup -> {
                        Text(step.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "循环 ${step.count} 次 · ${countSteps(step.steps)} 个步骤",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    is ScriptStep.PackageCall -> {
                        Text(
                            text = "调用函数包：${packageName ?: "（函数包不存在）"}",
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (packageName == null) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                        )
                        if (packageName == null) {
                            Text(
                                "函数包 id：${step.packageId}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            // 行内操作（多选模式隐藏，避免误触）
            if (!selectionMode) {
                if (step is ScriptStep.BasicStep || step is ScriptStep.LoopGroup) {
                    IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Edit, contentDescription = "编辑", modifier = Modifier.size(18.dp))
                    }
                }
                // 解段：仅循环段显示，子节点原位提回父层级
                if (step is ScriptStep.LoopGroup) {
                    IconButton(onClick = onUngroup, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Filled.LinkOff,
                            contentDescription = "解段",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                IconButton(
                    onClick = onMoveUp,
                    enabled = canMoveUp,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "上移", modifier = Modifier.size(18.dp))
                }
                IconButton(
                    onClick = onMoveDown,
                    enabled = canMoveDown,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "下移", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

// =================================================================================
// 表单弹窗
// =================================================================================

/** 新建 WaitImage 的默认超时：5 秒 */
private const val DEFAULT_WAIT_TIMEOUT_MS: Int = 5000

/**
 * 动作参数表单弹窗：按 [initial] 的实际类型渲染对应数字字段。
 * - Tap：x、y
 * - LongPress：x、y、时长 ms
 * - Swipe：起点/终点坐标、时长 ms
 * - Delay：时长 ms
 * - GlobalHome/GlobalBack：无可编辑参数，确认原样返回
 * - WaitImage：模板下拉、超时秒数、相似度、可选区域、找到后是否点击
 *
 * @param templates 识图模板列表（WaitImage 表单下拉数据源，ViewModel 进页面时加载）
 */
@Composable
private fun ActionFormDialog(
    title: String,
    initial: Action,
    templates: List<ImageTemplateMetadata>,
    onConfirm: (Action) -> Unit,
    onDismiss: () -> Unit,
) {
    // 各字段种子值
    val seedX = when (val a = initial) {
        is Action.Tap -> a.x
        is Action.LongPress -> a.x
        is Action.Swipe -> a.x1
        else -> null
    }
    val seedY = when (val a = initial) {
        is Action.Tap -> a.y
        is Action.LongPress -> a.y
        is Action.Swipe -> a.y1
        else -> null
    }
    val seedX2 = (initial as? Action.Swipe)?.x2
    val seedY2 = (initial as? Action.Swipe)?.y2
    val seedDuration = when (val a = initial) {
        is Action.LongPress -> a.durationMs
        is Action.Swipe -> a.durationMs
        is Action.Delay -> a.durationMs
        else -> null
    }

    var x by remember(initial) { mutableStateOf(seedX?.toString() ?: "") }
    var y by remember(initial) { mutableStateOf(seedY?.toString() ?: "") }
    var x2 by remember(initial) { mutableStateOf(seedX2?.toString() ?: "") }
    var y2 by remember(initial) { mutableStateOf(seedY2?.toString() ?: "") }
    var duration by remember(initial) { mutableStateOf(seedDuration?.toString() ?: "") }

    // ---- WaitImage 专用字段种子（仅 WaitImage 时有意义）----
    val seedWaitImage = initial as? Action.WaitImage
    var wiTemplateId by remember(initial) {
        mutableStateOf(seedWaitImage?.templateId ?: "")
    }
    // 表单按「秒」编辑，存储时 ×1000
    var wiTimeoutSec by remember(initial) {
        mutableStateOf(
            seedWaitImage?.let { (it.timeoutMs / 1000).toString() } ?: "5"
        )
    }
    var wiSimilarity by remember(initial) {
        mutableStateOf(
            seedWaitImage?.similarity?.toString() ?: Action.DEFAULT_SIMILARITY.toString()
        )
    }
    // 全屏复选框：region == null 时勾选（新建默认勾选）
    var wiFullscreen by remember(initial) {
        mutableStateOf(seedWaitImage?.region == null)
    }
    var wiRegionX by remember(initial) {
        mutableStateOf(seedWaitImage?.region?.left?.toString() ?: "")
    }
    var wiRegionY by remember(initial) {
        mutableStateOf(seedWaitImage?.region?.top?.toString() ?: "")
    }
    var wiRegionW by remember(initial) {
        mutableStateOf(
            seedWaitImage?.region?.let { (it.right - it.left).toString() } ?: ""
        )
    }
    var wiRegionH by remember(initial) {
        mutableStateOf(
            seedWaitImage?.region?.let { (it.bottom - it.top).toString() } ?: ""
        )
    }
    var wiTapWhenFound by remember(initial) {
        mutableStateOf(seedWaitImage?.tapWhenFound ?: true)
    }

    // 实时解析
    val px = x.toIntOrNull()
    val py = y.toIntOrNull()
    val px2 = x2.toIntOrNull()
    val py2 = y2.toIntOrNull()
    val pDuration = duration.toLongOrNull()

    // WaitImage 实时解析
    val pWiTimeoutSec = wiTimeoutSec.toIntOrNull()
    val pWiSimilarity = wiSimilarity.toDoubleOrNull()
    val pWiX = wiRegionX.toIntOrNull()
    val pWiY = wiRegionY.toIntOrNull()
    val pWiW = wiRegionW.toIntOrNull()
    val pWiH = wiRegionH.toIntOrNull()

    /**
     * WaitImage 校验，返回第一条中文错误提示；null 表示全部通过。
     * 规则：必须选择已存在模板；超时秒数 >=1；相似度 0..1；
     * 非全屏时起始坐标非负、宽高 >0。
     */
    val wiError: String? = when {
        seedWaitImage == null -> null
        templates.isEmpty() -> "请先到识图模板页创建模板"
        wiTemplateId.isBlank() || templates.none { it.id == wiTemplateId } ->
            "请选择识图模板"
        pWiTimeoutSec == null || pWiTimeoutSec < 1 ->
            "超时秒数必须 ≥ 1"
        pWiSimilarity == null || pWiSimilarity < 0.0 || pWiSimilarity > 1.0 ->
            "相似度必须在 0~1 之间"
        !wiFullscreen && (pWiX == null || pWiY == null || pWiX < 0 || pWiY < 0) ->
            "区域起始坐标必须为非负整数"
        !wiFullscreen && (pWiW == null || pWiH == null || pWiW <= 0 || pWiH <= 0) ->
            "区域宽高必须为正整数（大于 0）"
        else -> null
    }

    val valid = when (initial) {
        is Action.Tap -> px != null && px >= 0 && py != null && py >= 0
        is Action.LongPress ->
            px != null && px >= 0 && py != null && py >= 0 &&
                pDuration != null && pDuration >= 0
        is Action.Swipe ->
            px != null && px >= 0 && py != null && py >= 0 &&
                px2 != null && px2 >= 0 && py2 != null && py2 >= 0 &&
                pDuration != null && pDuration >= 0
        is Action.Delay -> pDuration != null && pDuration >= 0
        Action.GlobalHome -> true
        Action.GlobalBack -> true
        is Action.WaitImage -> wiError == null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (initial) {
                    is Action.Tap -> {
                        NumberField("X 坐标", x) { x = it }
                        NumberField("Y 坐标", y) { y = it }
                    }

                    is Action.LongPress -> {
                        NumberField("X 坐标", x) { x = it }
                        NumberField("Y 坐标", y) { y = it }
                        NumberField("长按时长（毫秒）", duration) { duration = it }
                    }

                    is Action.Swipe -> {
                        NumberField("起点 X", x) { x = it }
                        NumberField("起点 Y", y) { y = it }
                        NumberField("终点 X", x2) { x2 = it }
                        NumberField("终点 Y", y2) { y2 = it }
                        NumberField("滑动时长（毫秒，建议 ≥300）", duration) { duration = it }
                    }

                    is Action.Delay ->
                        NumberField("延时时长（毫秒）", duration) { duration = it }

                    Action.GlobalHome -> Text("Home 键动作没有可编辑参数。")
                    Action.GlobalBack -> Text("返回键动作没有可编辑参数。")
                    is Action.WaitImage ->
                        WaitImageFormFields(
                            templates = templates,
                            selectedTemplateId = wiTemplateId,
                            onTemplateSelect = { wiTemplateId = it },
                            timeoutSec = wiTimeoutSec,
                            onTimeoutChange = { raw ->
                                wiTimeoutSec = raw.filter { it.isDigit() }
                            },
                            similarity = wiSimilarity,
                            onSimilarityChange = { raw ->
                                // 仅保留数字与小数点，且最多一个小数点
                                val filtered = raw.filter { it.isDigit() || it == '.' }
                                wiSimilarity = if (filtered.count { it == '.' } > 1) {
                                    filtered.substringBeforeLast('.')
                                } else {
                                    filtered
                                }
                            },
                            fullscreen = wiFullscreen,
                            onFullscreenChange = { wiFullscreen = it },
                            regionX = wiRegionX,
                            regionY = wiRegionY,
                            regionW = wiRegionW,
                            regionH = wiRegionH,
                            onRegionXChange = { wiRegionX = it.filter { c -> c.isDigit() } },
                            onRegionYChange = { wiRegionY = it.filter { c -> c.isDigit() } },
                            onRegionWChange = { wiRegionW = it.filter { c -> c.isDigit() } },
                            onRegionHChange = { wiRegionH = it.filter { c -> c.isDigit() } },
                            tapWhenFound = wiTapWhenFound,
                            onTapWhenFoundChange = { wiTapWhenFound = it },
                            errorText = wiError,
                        )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    val newAction = when (initial) {
                        is Action.Tap -> Action.Tap(px!!, py!!)
                        is Action.LongPress -> Action.LongPress(px!!, py!!, pDuration!!)
                        is Action.Swipe -> Action.Swipe(px!!, py!!, px2!!, py2!!, pDuration!!)
                        is Action.Delay -> Action.Delay(pDuration!!)
                        Action.GlobalHome -> initial
                        Action.GlobalBack -> initial
                        is Action.WaitImage ->
                            Action.WaitImage(
                                templateId = wiTemplateId,
                                // 全屏时 region=null；否则 x/y + 宽高换算为 left/top/right/bottom
                                region = if (wiFullscreen) {
                                    null
                                } else {
                                    Rect(
                                        left = pWiX!!,
                                        top = pWiY!!,
                                        right = pWiX + pWiW!!,
                                        bottom = pWiY + pWiH!!,
                                    )
                                },
                                similarity = pWiSimilarity!!,
                                timeoutMs = pWiTimeoutSec!! * 1000,
                                tapWhenFound = wiTapWhenFound,
                            )
                    }
                    onConfirm(newAction)
                },
            ) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 数字输入框：仅允许数字字符（即非负） */
@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { raw -> onChange(raw.filter { it.isDigit() }) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * WaitImage（等待识图）参数表单。
 *
 * 字段：模板下拉、超时秒数（≥1）、相似度（0~1）、全屏复选框、
 * 非全屏时的起始 x/y 与宽高、找到后是否点击开关；底部显示第一条校验错误。
 * 字段较多，外层用可滚动 Column 并限制最大高度，避免在小屏溢出。
 */
@Composable
private fun WaitImageFormFields(
    templates: List<ImageTemplateMetadata>,
    selectedTemplateId: String,
    onTemplateSelect: (String) -> Unit,
    timeoutSec: String,
    onTimeoutChange: (String) -> Unit,
    similarity: String,
    onSimilarityChange: (String) -> Unit,
    fullscreen: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
    regionX: String,
    regionY: String,
    regionW: String,
    regionH: String,
    onRegionXChange: (String) -> Unit,
    onRegionYChange: (String) -> Unit,
    onRegionWChange: (String) -> Unit,
    onRegionHChange: (String) -> Unit,
    tapWhenFound: Boolean,
    onTapWhenFoundChange: (Boolean) -> Unit,
    errorText: String?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 420.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 1) 模板下拉选择
        TemplateDropdown(
            templates = templates,
            selectedId = selectedTemplateId,
            onSelect = onTemplateSelect,
        )

        // 2) 超时秒数（内部 ×1000 存 timeoutMs）
        OutlinedTextField(
            value = timeoutSec,
            onValueChange = onTimeoutChange,
            label = { Text("超时秒数（≥1）") },
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = KeyboardType.Number
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        // 3) 相似度 0~1（允许小数）
        OutlinedTextField(
            value = similarity,
            onValueChange = onSimilarityChange,
            label = { Text("相似度（0~1）") },
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = KeyboardType.Decimal
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        // 4) 全屏复选框：勾选时 region=null
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = fullscreen, onCheckedChange = onFullscreenChange)
            Text("全屏（不限制找图区域）")
        }

        // 非全屏时显示区域四个输入：起始 x/y + 宽高
        if (!fullscreen) {
            NumberField("区域起始 X（非负）", regionX, onRegionXChange)
            NumberField("区域起始 Y（非负）", regionY, onRegionYChange)
            NumberField("区域宽度（>0）", regionW, onRegionWChange)
            NumberField("区域高度（>0）", regionH, onRegionHChange)
        }

        // 5) 找到后是否点击
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Switch(
                checked = tapWhenFound,
                onCheckedChange = onTapWhenFoundChange,
            )
            Spacer(Modifier.width(8.dp))
            Text("找到后点击匹配位置")
        }

        // 6) 校验错误中文提示
        if (errorText != null) {
            Text(
                text = errorText,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * 识图模板下拉选择（Material3 ExposedDropdownMenuBox）。
 * 模板列表为空时字段只读且不可展开，提示文案由外层校验区统一展示。
 */
@Composable
private fun TemplateDropdown(
    templates: List<ImageTemplateMetadata>,
    selectedId: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val menuExpanded = expanded && templates.isNotEmpty()
    val selectedName = templates.firstOrNull { it.id == selectedId }?.name ?: ""

    ExposedDropdownMenuBox(
        expanded = menuExpanded,
        onExpandedChange = { if (templates.isNotEmpty()) expanded = it },
    ) {
        OutlinedTextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            enabled = templates.isNotEmpty(),
            label = { Text("识图模板") },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuExpanded)
            },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { expanded = false },
        ) {
            templates.forEach { template ->
                DropdownMenuItem(
                    text = { Text(template.name) },
                    onClick = {
                        onSelect(template.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** 「设为循环段」确认弹窗：名称 + 次数 N（≥1） */
@Composable
private fun WrapFormDialog(
    onConfirm: (name: String, count: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(ScriptStep.LoopGroup.DEFAULT_NAME) }
    var countText by remember { mutableStateOf("1") }
    val count = countText.toIntOrNull()
    val valid = name.isNotBlank() && count != null && count >= 1

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设为循环段") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("循环段名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = countText,
                    onValueChange = { raw -> countText = raw.filter { it.isDigit() } },
                    label = { Text("循环次数（≥1）") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { onConfirm(name.trim(), count!!) },
            ) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** LoopGroup 改名 + 改次数弹窗 */
@Composable
private fun LoopFormDialog(
    initial: ScriptStep.LoopGroup,
    onConfirm: (name: String, count: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(initial.id) { mutableStateOf(initial.name) }
    var countText by remember(initial.id) { mutableStateOf(initial.count.toString()) }
    val count = countText.toIntOrNull()
    val valid = name.isNotBlank() && count != null && count >= 1

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("循环段设置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("循环段名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = countText,
                    onValueChange = { raw -> countText = raw.filter { it.isDigit() } },
                    label = { Text("循环次数（≥1）") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { onConfirm(name.trim(), count!!) },
            ) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 单行文本输入弹窗（另存为函数包用） */
@Composable
private fun TextInputDialog(
    title: String,
    initialText: String,
    label: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initialText) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = { onConfirm(text.trim()) },
            ) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 插入函数包调用选择弹窗：列出全部函数包，点击一行即选中 */
@Composable
private fun InsertPackageCallDialog(
    packages: List<FunctionPackage>,
    onConfirm: (packageId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("插入函数包调用") },
        text = {
            if (packages.isEmpty()) {
                Text("暂无可用函数包，请先在函数包页创建，或把步骤另存为函数包。")
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(packages, key = { it.id }) { pkg ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onConfirm(pkg.id) }
                                .padding(horizontal = 4.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(pkg.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${countSteps(pkg.steps)} 个步骤",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        // 仅提供「取消」，选择动作由列表行点击触发
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

// =================================================================================
// MVI：状态 / 意图 / 弹窗
// =================================================================================

/** 步骤编辑器 UI 状态（单一数据源） */
data class EditorUiState(
    val loading: Boolean = true,
    val notFound: Boolean = false,
    val scriptName: String = "",
    val steps: List<ScriptStep> = emptyList(),
    /** 全部函数包（供插入调用选择） */
    val packages: List<FunctionPackage> = emptyList(),
    /** packageId -> 函数包名称（PackageCall 渲染解析） */
    val packageNames: Map<String, String> = emptyMap(),
    /** 全部识图模板元数据（WaitImage 表单下拉数据源） */
    val templates: List<ImageTemplateMetadata> = emptyList(),
    /** 已折叠的 LoopGroup id 集合 */
    val collapsed: Set<String> = emptySet(),
    /** 是否处于多选模式 */
    val selectionMode: Boolean = false,
    /** 多选选中的步骤 id */
    val selectedIds: Set<String> = emptySet(),
    val dialog: EditorDialog? = null,
    val message: EditorMessage? = null,
)

/** 一次性提示消息 */
data class EditorMessage(
    val seq: Long,
    val text: String,
)

/** 弹窗类型 */
sealed interface EditorDialog {
    /** 新建动作（携带默认动作以决定表单类型） */
    data class CreateAction(val action: Action) : EditorDialog
    /** 编辑已有 BasicStep 的动作 */
    data class EditAction(val stepId: String, val action: Action) : EditorDialog
    /** 区间封装确认（起始/结束步骤 id） */
    data class WrapSettings(val startId: String, val endId: String) : EditorDialog
    /** LoopGroup 改名/改次数 */
    data class LoopSettings(val loop: ScriptStep.LoopGroup) : EditorDialog
    /** 另存为函数包名称输入 */
    data object SaveAsPackageName : EditorDialog
    /** 插入函数包调用选择 */
    data object InsertPackageCall : EditorDialog
    /** 保存失败（仓库校验中文原因） */
    data class SaveFailed(val reason: String) : EditorDialog
}

/** 用户意图 */
sealed interface EditorIntent {
    data class UpdateName(val name: String) : EditorIntent
    data object Save : EditorIntent

    data object ToggleSelectionMode : EditorIntent
    data class ToggleSelect(val id: String) : EditorIntent
    data object ClearSelection : EditorIntent

    data object RequestWrap : EditorIntent
    data class ConfirmWrap(
        val startId: String,
        val endId: String,
        val name: String,
        val count: Int,
    ) : EditorIntent

    data object RequestSaveAsPackage : EditorIntent
    data class ConfirmSaveAsPackage(val name: String) : EditorIntent

    data object ShowInsertCall : EditorIntent
    data class InsertPackageCall(val packageId: String) : EditorIntent

    data class OpenCreate(val action: Action) : EditorIntent
    data class AddStep(val action: Action) : EditorIntent
    data class EditStep(val stepId: String) : EditorIntent
    data class UpdateAction(val stepId: String, val action: Action) : EditorIntent

    data class Delete(val stepId: String) : EditorIntent
    data class Move(val stepId: String, val up: Boolean) : EditorIntent

    data class EditLoop(val loop: ScriptStep.LoopGroup) : EditorIntent
    data class UpdateLoop(val stepId: String, val name: String, val count: Int) : EditorIntent
    data class Ungroup(val stepId: String) : EditorIntent

    data class ToggleCollapse(val id: String) : EditorIntent
    data object DismissDialog : EditorIntent
    data object MessageShown : EditorIntent
}

// =================================================================================
// ViewModel
// =================================================================================

/**
 * 步骤编辑器 ViewModel：持有单一 [EditorUiState]，所有仓库 IO 经 viewModelScope 发起。
 *
 * @param app 应用实例
 * @param scriptId 当前脚本 id
 * @param scriptRepository 脚本仓库（加载/保存）
 * @param packageRepository 函数包仓库（包名解析、插入调用列表、另存为函数包）
 * @param templateRepository 识图模板仓库（WaitImage 表单的模板列表）
 */
class ScriptEditorViewModel(
    private val app: MyApplication,
    private val scriptId: String,
    private val scriptRepository: ScriptFileRepository,
    private val packageRepository: FunctionPackageRepository,
    private val templateRepository: ImageTemplateRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    /** 加载时记住脚本原有的重复策略，保存时原样带回（本页不编辑策略） */
    private var savedPolicy: RepeatPolicy = RepeatPolicy.UntilStopped

    private var messageSeq: Long = 0

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val script = scriptRepository.get(scriptId)
            val packages = runCatching { packageRepository.list() }.getOrDefault(emptyList())
            // 仿照 packages 模式，进页面一次性加载识图模板列表
            val templates = runCatching { templateRepository.list() }.getOrDefault(emptyList())
            if (script == null) {
                _state.update { it.copy(loading = false, notFound = true) }
                return@launch
            }
            savedPolicy = script.repeatPolicy
            _state.update {
                it.copy(
                    loading = false,
                    scriptName = script.name,
                    steps = script.steps,
                    packages = packages,
                    packageNames = packages.associate { pkg -> pkg.id to pkg.name },
                    templates = templates,
                )
            }
        }
    }

    fun onIntent(intent: EditorIntent) {
        when (intent) {
            is EditorIntent.UpdateName ->
                _state.update { it.copy(scriptName = intent.name) }

            EditorIntent.Save -> save()

            EditorIntent.ToggleSelectionMode ->
                _state.update { current ->
                    // 进入多选不清空已有选择，退出多选由 ClearSelection 负责
                    current.copy(selectionMode = !current.selectionMode)
                }

            is EditorIntent.ToggleSelect ->
                _state.update { current ->
                    current.copy(
                        selectedIds =
                            if (intent.id in current.selectedIds) current.selectedIds - intent.id
                            else current.selectedIds + intent.id
                    )
                }

            EditorIntent.ClearSelection ->
                _state.update { it.copy(selectionMode = false, selectedIds = emptySet()) }

            EditorIntent.RequestWrap -> requestWrap()
            is EditorIntent.ConfirmWrap -> confirmWrap(
                startId = intent.startId,
                endId = intent.endId,
                name = intent.name,
                count = intent.count,
            )

            EditorIntent.RequestSaveAsPackage -> requestSaveAsPackage()
            is EditorIntent.ConfirmSaveAsPackage -> confirmSaveAsPackage(intent.name)

            EditorIntent.ShowInsertCall -> showInsertCall()
            is EditorIntent.InsertPackageCall -> insertPackageCall(intent.packageId)

            is EditorIntent.OpenCreate ->
                _state.update { it.copy(dialog = EditorDialog.CreateAction(intent.action)) }

            is EditorIntent.AddStep -> addStep(intent.action)
            is EditorIntent.EditStep -> editStep(intent.stepId)
            is EditorIntent.UpdateAction ->
                _state.update { current ->
                    current.copy(
                        steps = current.steps.withAction(intent.stepId, intent.action),
                        dialog = null,
                    )
                }

            is EditorIntent.Delete ->
                _state.update { current ->
                    current.copy(
                        steps = current.steps.withoutId(intent.stepId),
                        selectedIds = current.selectedIds - intent.stepId,
                        collapsed = current.collapsed - intent.stepId,
                    )
                }

            is EditorIntent.Move ->
                _state.update { it.copy(steps = it.steps.moveNode(intent.stepId, intent.up)) }

            is EditorIntent.EditLoop ->
                _state.update { it.copy(dialog = EditorDialog.LoopSettings(intent.loop)) }

            is EditorIntent.UpdateLoop ->
                _state.update { current ->
                    current.copy(
                        steps = current.steps.withLoop(intent.stepId, intent.name, intent.count),
                        dialog = null,
                    )
                }

            // 解段：点击循环段行内的「解段」按钮（LinkOff 图标）触发
            is EditorIntent.Ungroup ->
                _state.update { current ->
                    current.copy(
                        steps = current.steps.ungroupNode(intent.stepId),
                        collapsed = current.collapsed - intent.stepId,
                    )
                }

            is EditorIntent.ToggleCollapse ->
                _state.update { current ->
                    current.copy(
                        collapsed =
                            if (intent.id in current.collapsed) current.collapsed - intent.id
                            else current.collapsed + intent.id
                    )
                }

            EditorIntent.DismissDialog ->
                _state.update { it.copy(dialog = null) }

            EditorIntent.MessageShown ->
                _state.update { it.copy(message = null) }
        }
    }

    /** 保存：经仓库 StructureValidator 校验；失败弹中文原因 */
    private fun save() {
        val current = _state.value
        val name = current.scriptName.trim()
        if (name.isEmpty()) {
            postMessage("脚本名称不能为空")
            return
        }
        viewModelScope.launch {
            val script = Script(
                id = scriptId,
                name = name,
                steps = current.steps,
                repeatPolicy = savedPolicy,
            )
            val result = runCatching { scriptRepository.upsert(script) }
            result.fold(
                onSuccess = { postMessage("已保存") },
                onFailure = { e ->
                    _state.update {
                        it.copy(dialog = EditorDialog.SaveFailed(e.message ?: "保存失败"))
                    }
                },
            )
        }
    }

    /** 请求区间封装：校验选中节点至少 2 个且同属一个父层级 */
    private fun requestWrap() {
        val current = _state.value
        val ordered = orderedSelection(current)
        if (ordered.size < 2) {
            postMessage("请在同一层级勾选起始步骤与结束步骤（至少 2 步）")
            return
        }
        val parents = ordered.map { parentListOf(current.steps, it.id) }
        val sameParent = parents.none { it == null } &&
            parents.zipWithNext().all { (a, b) -> a === b }
        if (!sameParent) {
            postMessage("起始步骤与结束步骤必须属于同一个层级")
            return
        }
        _state.update {
            it.copy(dialog = EditorDialog.WrapSettings(ordered.first().id, ordered.last().id))
        }
    }

    /** 确认区间封装：区间节点移入新 LoopGroup，退出多选 */
    private fun confirmWrap(startId: String, endId: String, name: String, count: Int) {
        if (count < 1) {
            postMessage("循环次数必须 >= 1")
            return
        }
        _state.update { current ->
            current.copy(
                steps = current.steps.wrapRange(
                    startId = startId,
                    endId = endId,
                    name = name.ifBlank { ScriptStep.LoopGroup.DEFAULT_NAME },
                    count = count,
                    newGroupId = newStepId(),
                ),
                dialog = null,
                selectionMode = false,
                selectedIds = emptySet(),
            )
        }
        postMessage("已设为循环段")
    }

    /** 请求另存为函数包：校验选中非空且同层级 */
    private fun requestSaveAsPackage() {
        val current = _state.value
        val ordered = orderedSelection(current)
        if (ordered.isEmpty()) {
            postMessage("请先勾选要保存的步骤")
            return
        }
        val parents = ordered.map { parentListOf(current.steps, it.id) }
        val sameParent = parents.none { it == null } &&
            parents.zipWithNext().all { (a, b) -> a === b }
        if (!sameParent) {
            postMessage("请选择同一层级的步骤")
            return
        }
        _state.update { it.copy(dialog = EditorDialog.SaveAsPackageName) }
    }

    /** 确认另存为函数包：步骤副本（新 id）写入函数包仓库 */
    private fun confirmSaveAsPackage(nameRaw: String) {
        val name = nameRaw.trim()
        if (name.isEmpty()) {
            postMessage("函数包名称不能为空")
            return
        }
        val copies = orderedSelection(_state.value).map { it.regenIds() }
        viewModelScope.launch {
            val result = runCatching {
                packageRepository.upsert(
                    FunctionPackage(
                        id = packageRepository.newId(),
                        name = name,
                        steps = copies,
                    )
                )
            }
            result.fold(
                onSuccess = {
                    postMessage("已另存为函数包：$name")
                    _state.update {
                        it.copy(dialog = null, selectionMode = false, selectedIds = emptySet())
                    }
                    val packages = runCatching { packageRepository.list() }
                        .getOrDefault(emptyList())
                    _state.update {
                        it.copy(
                            packages = packages,
                            packageNames = packages.associate { pkg -> pkg.id to pkg.name },
                        )
                    }
                },
                onFailure = { postMessage(it.message ?: "函数包保存失败") },
            )
        }
    }

    /** 打开展示函数包调用选择：无函数包时给中文提示 */
    private fun showInsertCall() {
        if (_state.value.packages.isEmpty()) {
            postMessage("暂无函数包，请先在「函数包」页创建，或把步骤另存为函数包")
            return
        }
        _state.update { it.copy(dialog = EditorDialog.InsertPackageCall) }
    }

    /** 插入函数包调用：在根层级末尾追加 PackageCall */
    private fun insertPackageCall(packageId: String) {
        _state.update {
            it.copy(
                steps = it.steps + ScriptStep.PackageCall(newStepId(), packageId),
                dialog = null,
            )
        }
        postMessage("已插入函数包调用")
    }

    /** 添加基础步骤：在根层级末尾追加 BasicStep */
    private fun addStep(action: Action) {
        _state.update {
            it.copy(
                steps = it.steps + ScriptStep.BasicStep(newStepId(), action),
                dialog = null,
            )
        }
    }

    /** 打开 BasicStep 编辑表单 */
    private fun editStep(stepId: String) {
        val step = findStep(_state.value.steps, stepId)
        val action = (step as? ScriptStep.BasicStep)?.action ?: return
        _state.update { it.copy(dialog = EditorDialog.EditAction(stepId, action)) }
    }

    private fun postMessage(text: String) {
        messageSeq += 1
        _state.update { it.copy(message = EditorMessage(messageSeq, text)) }
    }
}

// =================================================================================
// 步骤树纯函数操作（不可变更新）
// =================================================================================

/** 扁平化后的可见节点 */
private data class FlatNode(
    val step: ScriptStep,
    val depth: Int,
)

/**
 * 把步骤树扁平化为可见节点序列：
 * LoopGroup 自身占一行，仅当其 id 不在 [collapsed] 时递归展开子节点（层级 +1）。
 */
private fun flattenVisible(
    steps: List<ScriptStep>,
    depth: Int,
    collapsed: Set<String>,
    out: MutableList<FlatNode>,
) {
    steps.forEach { node ->
        out += FlatNode(node, depth)
        if (node is ScriptStep.LoopGroup && node.id !in collapsed) {
            flattenVisible(node.steps, depth + 1, collapsed, out)
        }
    }
}

/** 节点在所属层级中的位置（用于上移/下移按钮可用性） */
private data class StepPos(
    val index: Int,
    val size: Int,
)

private fun positionOf(steps: List<ScriptStep>, id: String): StepPos? {
    steps.forEachIndexed { index, node ->
        if (node.id == id) return StepPos(index, steps.size)
    }
    steps.forEach { node ->
        if (node is ScriptStep.LoopGroup) {
            positionOf(node.steps, id)?.let { return it }
        }
    }
    return null
}

/** 按 id 查找节点 */
private fun findStep(steps: List<ScriptStep>, id: String): ScriptStep? {
    steps.forEach { node ->
        if (node.id == id) return node
        if (node is ScriptStep.LoopGroup) {
            findStep(node.steps, id)?.let { return it }
        }
    }
    return null
}

/**
 * 按 DFS 顺序返回当前选中的节点（顺序与界面自上而下一致）。
 */
private fun orderedSelection(state: EditorUiState): List<ScriptStep> {
    val ids = state.selectedIds
    val out = mutableListOf<ScriptStep>()
    fun walk(list: List<ScriptStep>) {
        list.forEach { node ->
            if (node.id in ids) out += node
            if (node is ScriptStep.LoopGroup) walk(node.steps)
        }
    }
    walk(state.steps)
    return out
}

/** 删除指定 id 节点（递归各层级）；LoopGroup 命中则整段删除 */
private fun List<ScriptStep>.withoutId(id: String): List<ScriptStep> = mapNotNull { node ->
    when (node) {
        is ScriptStep.BasicStep, is ScriptStep.PackageCall ->
            if (node.id == id) null else node

        is ScriptStep.LoopGroup ->
            if (node.id == id) null else node.copy(steps = node.steps.withoutId(id))
    }
}

/** 同层级上移/下移：越界或本层未命中（继续递归）时结构保持不变 */
private fun List<ScriptStep>.moveNode(id: String, up: Boolean): List<ScriptStep> {
    val index = indexOfFirst { it.id == id }
    if (index < 0) {
        return map { node ->
            if (node is ScriptStep.LoopGroup) node.copy(steps = node.steps.moveNode(id, up))
            else node
        }
    }
    val target = index + if (up) -1 else 1
    if (target !in indices) return this
    return toMutableList().apply {
        val moving = this[index]
        this[index] = this[target]
        this[target] = moving
    }
}

/** 更新 BasicStep 的动作（递归查找） */
private fun List<ScriptStep>.withAction(id: String, action: Action): List<ScriptStep> = map { node ->
    when (node) {
        is ScriptStep.BasicStep ->
            if (node.id == id) node.copy(action = action) else node

        is ScriptStep.LoopGroup -> node.copy(steps = node.steps.withAction(id, action))
        is ScriptStep.PackageCall -> node
    }
}

/** 更新 LoopGroup 的名称与次数（递归查找） */
private fun List<ScriptStep>.withLoop(id: String, name: String, count: Int): List<ScriptStep> =
    map { node ->
        when (node) {
            is ScriptStep.LoopGroup ->
                if (node.id == id) node.copy(name = name, count = count)
                else node.copy(steps = node.steps.withLoop(id, name, count))

            else -> node
        }
    }

/**
 * 解段：命中的 LoopGroup 在父层级原位被其 steps 子节点替换（保持原有先后顺序）；
 * 未命中则递归处理嵌套 LoopGroup。
 */
private fun List<ScriptStep>.ungroupNode(id: String): List<ScriptStep> = flatMap { node ->
    when {
        node is ScriptStep.LoopGroup && node.id == id -> node.steps
        node is ScriptStep.LoopGroup -> listOf(node.copy(steps = node.steps.ungroupNode(id)))
        else -> listOf(node)
    }
}

/**
 * 区间封装：[startId] 与 [endId] 必须同属一个父层级（调用方已校验）。
 * 取二者在父层级中的下标区间（含两端，区间内全部节点，保持连续），
 * 整体移入新建的 LoopGroup.steps，新 LoopGroup 占据区间起点位置。
 * 本层未命中两个端点时递归进入嵌套 LoopGroup。
 */
private fun List<ScriptStep>.wrapRange(
    startId: String,
    endId: String,
    name: String,
    count: Int,
    newGroupId: String,
): List<ScriptStep> {
    val startIndex = indexOfFirst { it.id == startId }
    val endIndex = indexOfFirst { it.id == endId }
    if (startIndex >= 0 && endIndex >= 0) {
        val from = minOf(startIndex, endIndex)
        val to = maxOf(startIndex, endIndex)
        val children = subList(from, to + 1).toList()
        return take(from) +
            ScriptStep.LoopGroup(
                id = newGroupId,
                name = name,
                steps = children,
                count = count,
            ) +
            drop(to + 1)
    }
    return map { node ->
        if (node is ScriptStep.LoopGroup) {
            node.copy(
                steps = node.steps.wrapRange(startId, endId, name, count, newGroupId)
            )
        } else {
            node
        }
    }
}
