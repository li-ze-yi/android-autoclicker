@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.autoclicker.ui.packages

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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.autoclicker.core.data.packages.FunctionPackageRepository
import com.autoclicker.domain.model.Action
import com.autoclicker.domain.model.FunctionPackage
import com.autoclicker.domain.model.ScriptStep
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

// 注意：本文件为函数包内容编辑器（评审问题 I3 / FR-6B），完全自包含；
// 步骤树纯函数与 UI 组件均在本文件内以 private 重新声明，
// 不 import ui.scripts 包下 ScriptEditorScreen / ScriptsScreen 的任何符号。

// =================================================================================
// 页面入口
// =================================================================================

/**
 * 函数包内容编辑器（FR-6B：函数包是独立实体，可编辑内容）。
 *
 * 能力对标脚本编辑器 [com.autoclicker.ui.scripts.ScriptEditorScreen]，作用对象为
 * [FunctionPackage]：
 * - 加载：[FunctionPackageRepository.get] 取出函数包，步骤树只在内存中编辑；
 * - 顶部：名称可编辑，「保存」走 [FunctionPackageRepository.upsert]
 *   （仓库内含 StructureValidator 校验，失败弹中文原因）；
 * - 步骤树缩进渲染：BasicStep 动作摘要、LoopGroup 名称 + 次数 + 折叠、
 *   PackageCall 经全局函数包列表解析包名；
 * - 单步：动作参数表单（点击含 holdMs / 长按 / 滑动 / 延时，Home / 返回无参数）、
 *   删除、同层级上移 / 下移；
 * - 添加：点击 / 长按 / 滑动 / 延时 / Home / 返回，以及插入函数包调用；
 * - 循环段：多选同层级区间 → 封装（名称 + 次数），可改名 / 改次数 / 解段 / 整段删除；
 * - WaitImage 在本编辑器中不新建，已存在的只读展示。
 *
 * @param packageId 要编辑的函数包 id
 * @param onBack 返回回调（由导航层接入）
 */
@Composable
fun PackageEditorScreen(
    packageId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val appContext = LocalContext.current.applicationContext
    val viewModel: PackageEditorViewModel = viewModel(
        factory = remember(appContext, packageId) {
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    @Suppress("UNCHECKED_CAST")
                    return PackageEditorViewModel(
                        repository = FunctionPackageRepository(appContext),
                        packageId = packageId,
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
        viewModel.onIntent(PackageEditorIntent.MessageShown)
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
                        title = { Text("编辑函数包") },
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
                    Text("函数包不存在或已被删除", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onBack) { Text("返回") }
                }
            }

        else ->
            PackageEditorScaffold(
                state = state,
                selfId = packageId,
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

/** 正常编辑态的 Scaffold（加载 / 不存在态在外层处理） */
@Composable
private fun PackageEditorScaffold(
    state: PackageEditorUiState,
    selfId: String,
    onBack: () -> Unit,
    onIntent: (PackageEditorIntent) -> Unit,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("编辑函数包") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = { onIntent(PackageEditorIntent.Save) }) { Text("保存") }
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
            // 函数包名称
            OutlinedTextField(
                value = state.packageName,
                onValueChange = { onIntent(PackageEditorIntent.UpdateName(it)) },
                label = { Text("函数包名称") },
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
                    OutlinedButton(onClick = { onIntent(PackageEditorIntent.ClearSelection) }) {
                        Text("退出多选")
                    }
                } else {
                    OutlinedButton(onClick = { onIntent(PackageEditorIntent.ToggleSelectionMode) }) {
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
                        onToggleSelect = { onIntent(PackageEditorIntent.ToggleSelect(flat.step.id)) },
                        onBodyClick = {
                            when (flat.step) {
                                is ScriptStep.BasicStep ->
                                    onIntent(PackageEditorIntent.EditStep(flat.step.id))

                                is ScriptStep.LoopGroup ->
                                    onIntent(PackageEditorIntent.ToggleCollapse(flat.step.id))

                                is ScriptStep.PackageCall -> Unit
                            }
                        },
                        onToggleCollapse = {
                            onIntent(PackageEditorIntent.ToggleCollapse(flat.step.id))
                        },
                        onEdit = {
                            when (val step = flat.step) {
                                is ScriptStep.BasicStep ->
                                    onIntent(PackageEditorIntent.EditStep(step.id))

                                is ScriptStep.LoopGroup ->
                                    onIntent(PackageEditorIntent.EditLoop(step))

                                is ScriptStep.PackageCall -> Unit
                            }
                        },
                        onUngroup = { onIntent(PackageEditorIntent.Ungroup(flat.step.id)) },
                        onMoveUp = { onIntent(PackageEditorIntent.Move(flat.step.id, up = true)) },
                        onMoveDown = { onIntent(PackageEditorIntent.Move(flat.step.id, up = false)) },
                        onDelete = { onIntent(PackageEditorIntent.Delete(flat.step.id)) },
                    )
                }
            }

            // 底部操作条：多选模式显示封装操作；否则显示添加动作菜单
            if (state.selectionMode) {
                SelectionActionBar(
                    selectedCount = state.selectedIds.size,
                    onWrap = { onIntent(PackageEditorIntent.RequestWrap) },
                    onCancel = { onIntent(PackageEditorIntent.ClearSelection) },
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

        is PackageEditorDialog.CreateAction ->
            ActionFormDialog(
                title = "添加动作",
                initial = dialog.action,
                onConfirm = { onIntent(PackageEditorIntent.AddStep(it)) },
                onDismiss = { onIntent(PackageEditorIntent.DismissDialog) },
            )

        is PackageEditorDialog.EditAction ->
            ActionFormDialog(
                title = "编辑动作",
                initial = dialog.action,
                onConfirm = { onIntent(PackageEditorIntent.UpdateAction(dialog.stepId, it)) },
                onDismiss = { onIntent(PackageEditorIntent.DismissDialog) },
            )

        is PackageEditorDialog.WaitImageReadOnly ->
            WaitImageReadOnlyDialog(
                action = dialog.action,
                onDismiss = { onIntent(PackageEditorIntent.DismissDialog) },
            )

        is PackageEditorDialog.WrapSettings ->
            WrapFormDialog(
                onConfirm = { name, count ->
                    onIntent(
                        PackageEditorIntent.ConfirmWrap(
                            startId = dialog.startId,
                            endId = dialog.endId,
                            name = name,
                            count = count,
                        )
                    )
                },
                onDismiss = { onIntent(PackageEditorIntent.DismissDialog) },
            )

        is PackageEditorDialog.LoopSettings ->
            LoopFormDialog(
                initial = dialog.loop,
                onConfirm = { name, count ->
                    onIntent(PackageEditorIntent.UpdateLoop(dialog.loop.id, name, count))
                },
                onDismiss = { onIntent(PackageEditorIntent.DismissDialog) },
            )

        PackageEditorDialog.InsertPackageCall ->
            InsertPackageCallDialog(
                // 排除自身：自调用在保存校验时也会被拦截，这里先从入口规避
                packages = state.packages.filter { it.id != selfId },
                onConfirm = { onIntent(PackageEditorIntent.InsertPackageCall(it)) },
                onDismiss = { onIntent(PackageEditorIntent.DismissDialog) },
            )

        is PackageEditorDialog.SaveFailed ->
            AlertDialog(
                onDismissRequest = { onIntent(PackageEditorIntent.DismissDialog) },
                title = { Text("保存失败") },
                text = { Text(dialog.reason) },
                confirmButton = {
                    TextButton(onClick = { onIntent(PackageEditorIntent.DismissDialog) }) {
                        Text("知道了")
                    }
                },
            )
    }
}

/** 多选模式底部操作条：函数包编辑器内多选仅用于区间封装 */
@Composable
private fun SelectionActionBar(
    selectedCount: Int,
    onWrap: () -> Unit,
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
            TextButton(onClick = onCancel) { Text("取消") }
        }
    }
}

/** 普通模式底部「添加动作」菜单（不提供等待识图入口） */
@Composable
private fun AddActionBar(onIntent: (PackageEditorIntent) -> Unit) {
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
                        // holdMs 默认 1ms（快速点击），表单中可改
                        onIntent(PackageEditorIntent.OpenCreate(Action.Tap(x = 0, y = 0)))
                    },
                )
                DropdownMenuItem(
                    text = { Text("长按") },
                    onClick = {
                        expanded = false
                        onIntent(
                            PackageEditorIntent.OpenCreate(
                                Action.LongPress(x = 0, y = 0, durationMs = 1000)
                            )
                        )
                    },
                )
                DropdownMenuItem(
                    text = { Text("滑动") },
                    onClick = {
                        expanded = false
                        onIntent(
                            PackageEditorIntent.OpenCreate(
                                Action.Swipe(x1 = 0, y1 = 0, x2 = 0, y2 = 0, durationMs = 300)
                            )
                        )
                    },
                )
                DropdownMenuItem(
                    text = { Text("延时") },
                    onClick = {
                        expanded = false
                        onIntent(PackageEditorIntent.OpenCreate(Action.Delay(durationMs = 500)))
                    },
                )
                DropdownMenuItem(
                    text = { Text("Home 键") },
                    onClick = {
                        expanded = false
                        onIntent(PackageEditorIntent.AddStep(Action.GlobalHome))
                    },
                )
                DropdownMenuItem(
                    text = { Text("返回键") },
                    onClick = {
                        expanded = false
                        onIntent(PackageEditorIntent.AddStep(Action.GlobalBack))
                    },
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("插入函数包调用") },
                    onClick = {
                        expanded = false
                        onIntent(PackageEditorIntent.ShowInsertCall)
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

/**
 * 动作参数表单弹窗：按 [initial] 的实际类型渲染对应字段。
 * - Tap：x、y、按住时长 holdMs
 * - LongPress：x、y、时长 ms
 * - Swipe：起点 / 终点坐标、时长 ms
 * - Delay：时长 ms
 * - GlobalHome / GlobalBack：无可编辑参数，确认原样返回
 * - WaitImage：不会进入本弹窗（走只读弹窗），分支仅为穷尽性保留
 */
@Composable
private fun ActionFormDialog(
    title: String,
    initial: Action,
    onConfirm: (Action) -> Unit,
    onDismiss: () -> Unit,
) {
    // 坐标 / 时长种子值
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
    val seedHold = (initial as? Action.Tap)?.holdMs

    var x by remember(initial) { mutableStateOf(seedX?.toString() ?: "") }
    var y by remember(initial) { mutableStateOf(seedY?.toString() ?: "") }
    var x2 by remember(initial) { mutableStateOf(seedX2?.toString() ?: "") }
    var y2 by remember(initial) { mutableStateOf(seedY2?.toString() ?: "") }
    var duration by remember(initial) { mutableStateOf(seedDuration?.toString() ?: "") }
    var hold by remember(initial) { mutableStateOf(seedHold?.toString() ?: "") }

    // 实时解析
    val px = x.toIntOrNull()
    val py = y.toIntOrNull()
    val px2 = x2.toIntOrNull()
    val py2 = y2.toIntOrNull()
    val pDuration = duration.toLongOrNull()
    val pHold = hold.toLongOrNull()

    val valid = when (initial) {
        is Action.Tap ->
            px != null && px >= 0 && py != null && py >= 0 &&
                pHold != null && pHold >= 0

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
        is Action.WaitImage -> true
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
                        NumberField("按住时长（毫秒，快速点击填 1）", hold) { hold = it }
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
                    is Action.WaitImage -> Text("等待识图为只读动作，不在本弹窗编辑。")
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    val newAction = when (initial) {
                        is Action.Tap -> Action.Tap(px!!, py!!, pHold!!)
                        is Action.LongPress -> Action.LongPress(px!!, py!!, pDuration!!)
                        is Action.Swipe -> Action.Swipe(px!!, py!!, px2!!, py2!!, pDuration!!)
                        is Action.Delay -> Action.Delay(pDuration!!)
                        Action.GlobalHome -> initial
                        Action.GlobalBack -> initial
                        is Action.WaitImage -> initial
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
 * WaitImage（等待识图）只读展示弹窗。
 *
 * 函数包编辑器不新建、不修改 WaitImage（与脚本编辑器口径一致），
 * 步骤树中已存在的 WaitImage 以本弹窗展示其参数。
 */
@Composable
private fun WaitImageReadOnlyDialog(
    action: Action.WaitImage,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("等待识图（只读）") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                InfoLine("识图模板 ID", action.templateId.ifBlank { "（未填写）" })
                InfoLine("超时时间", "${action.timeoutMs} 毫秒")
                InfoLine("相似度", action.similarity.toString())
                InfoLine(
                    "限定区域",
                    action.region?.let {
                        "(${it.left}, ${it.top}) - (${it.right}, ${it.bottom})"
                    } ?: "全屏",
                )
                InfoLine(
                    "找到后",
                    if (action.tapWhenFound) "点击匹配位置" else "不点击，继续下一步",
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "等待识图动作在函数包编辑器中仅作只读展示，不能新建或修改。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

/** 只读信息行：固定宽度标签 + 值 */
@Composable
private fun InfoLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            "$label：",
            modifier = Modifier.width(104.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
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

/** 插入函数包调用选择弹窗：列出可调用函数包，点击一行即选中 */
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
                Text("暂无可调用的其他函数包。")
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

/** 函数包编辑器 UI 状态（单一数据源） */
data class PackageEditorUiState(
    val loading: Boolean = true,
    val notFound: Boolean = false,
    val packageName: String = "",
    val steps: List<ScriptStep> = emptyList(),
    /** 全部全局函数包（供插入调用选择） */
    val packages: List<FunctionPackage> = emptyList(),
    /** packageId -> 函数包名称（PackageCall 渲染解析） */
    val packageNames: Map<String, String> = emptyMap(),
    /** 已折叠的 LoopGroup id 集合 */
    val collapsed: Set<String> = emptySet(),
    /** 是否处于多选模式 */
    val selectionMode: Boolean = false,
    /** 多选选中的步骤 id */
    val selectedIds: Set<String> = emptySet(),
    val dialog: PackageEditorDialog? = null,
    val message: PackageEditorMessage? = null,
)

/** 一次性提示消息（带序号，确保相同文本也能重新触发 Snackbar） */
data class PackageEditorMessage(
    val seq: Long,
    val text: String,
)

/** 弹窗类型 */
sealed interface PackageEditorDialog {
    /** 新建动作（携带默认动作以决定表单类型） */
    data class CreateAction(val action: Action) : PackageEditorDialog
    /** 编辑已有 BasicStep 的动作 */
    data class EditAction(val stepId: String, val action: Action) : PackageEditorDialog
    /** 已存在的 WaitImage 只读展示 */
    data class WaitImageReadOnly(val action: Action.WaitImage) : PackageEditorDialog
    /** 区间封装确认（起始/结束步骤 id） */
    data class WrapSettings(val startId: String, val endId: String) : PackageEditorDialog
    /** LoopGroup 改名 / 改次数 */
    data class LoopSettings(val loop: ScriptStep.LoopGroup) : PackageEditorDialog
    /** 插入函数包调用选择 */
    data object InsertPackageCall : PackageEditorDialog
    /** 保存失败（仓库校验中文原因） */
    data class SaveFailed(val reason: String) : PackageEditorDialog
}

/** 用户意图 */
sealed interface PackageEditorIntent {
    data class UpdateName(val name: String) : PackageEditorIntent
    data object Save : PackageEditorIntent

    data object ToggleSelectionMode : PackageEditorIntent
    data class ToggleSelect(val id: String) : PackageEditorIntent
    data object ClearSelection : PackageEditorIntent

    data object RequestWrap : PackageEditorIntent
    data class ConfirmWrap(
        val startId: String,
        val endId: String,
        val name: String,
        val count: Int,
    ) : PackageEditorIntent

    data object ShowInsertCall : PackageEditorIntent
    data class InsertPackageCall(val packageId: String) : PackageEditorIntent

    data class OpenCreate(val action: Action) : PackageEditorIntent
    data class AddStep(val action: Action) : PackageEditorIntent
    data class EditStep(val stepId: String) : PackageEditorIntent
    data class UpdateAction(val stepId: String, val action: Action) : PackageEditorIntent

    data class Delete(val stepId: String) : PackageEditorIntent
    data class Move(val stepId: String, val up: Boolean) : PackageEditorIntent

    data class EditLoop(val loop: ScriptStep.LoopGroup) : PackageEditorIntent
    data class UpdateLoop(val stepId: String, val name: String, val count: Int) : PackageEditorIntent
    data class Ungroup(val stepId: String) : PackageEditorIntent

    data class ToggleCollapse(val id: String) : PackageEditorIntent
    data object DismissDialog : PackageEditorIntent
    data object MessageShown : PackageEditorIntent
}

// =================================================================================
// ViewModel
// =================================================================================

/**
 * 函数包编辑器 ViewModel：持有单一 [PackageEditorUiState]，所有仓库 IO 经 viewModelScope 发起。
 *
 * @param repository 全局函数包仓库（加载 / 保存 / 全局包列表）
 * @param packageId 当前编辑的函数包 id
 */
class PackageEditorViewModel(
    private val repository: FunctionPackageRepository,
    private val packageId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(PackageEditorUiState())
    val state: StateFlow<PackageEditorUiState> = _state.asStateFlow()

    /** 消息自增序号 */
    private var messageSeq: Long = 0

    init {
        load()
    }

    /** 加载函数包与全局函数包列表；函数包不存在时进入 notFound 态 */
    private fun load() {
        viewModelScope.launch {
            val pkg = repository.get(packageId)
            val packages = runCatching { repository.list() }.getOrDefault(emptyList())
            if (pkg == null) {
                _state.update { it.copy(loading = false, notFound = true) }
                return@launch
            }
            _state.update {
                it.copy(
                    loading = false,
                    packageName = pkg.name,
                    steps = pkg.steps,
                    packages = packages,
                    packageNames = packages.associate { p -> p.id to p.name },
                )
            }
        }
    }

    fun onIntent(intent: PackageEditorIntent) {
        when (intent) {
            is PackageEditorIntent.UpdateName ->
                _state.update { it.copy(packageName = intent.name) }

            PackageEditorIntent.Save -> save()

            PackageEditorIntent.ToggleSelectionMode ->
                _state.update { current ->
                    // 进入多选不清空已有选择，退出多选由 ClearSelection 负责
                    current.copy(selectionMode = !current.selectionMode)
                }

            is PackageEditorIntent.ToggleSelect ->
                _state.update { current ->
                    current.copy(
                        selectedIds =
                            if (intent.id in current.selectedIds) current.selectedIds - intent.id
                            else current.selectedIds + intent.id
                    )
                }

            PackageEditorIntent.ClearSelection ->
                _state.update { it.copy(selectionMode = false, selectedIds = emptySet()) }

            PackageEditorIntent.RequestWrap -> requestWrap()
            is PackageEditorIntent.ConfirmWrap -> confirmWrap(
                startId = intent.startId,
                endId = intent.endId,
                name = intent.name,
                count = intent.count,
            )

            PackageEditorIntent.ShowInsertCall -> showInsertCall()
            is PackageEditorIntent.InsertPackageCall -> insertPackageCall(intent.packageId)

            is PackageEditorIntent.OpenCreate ->
                _state.update { it.copy(dialog = PackageEditorDialog.CreateAction(intent.action)) }

            is PackageEditorIntent.AddStep -> addStep(intent.action)
            is PackageEditorIntent.EditStep -> editStep(intent.stepId)
            is PackageEditorIntent.UpdateAction ->
                _state.update { current ->
                    current.copy(
                        steps = current.steps.withAction(intent.stepId, intent.action),
                        dialog = null,
                    )
                }

            is PackageEditorIntent.Delete ->
                _state.update { current ->
                    current.copy(
                        steps = current.steps.withoutId(intent.stepId),
                        selectedIds = current.selectedIds - intent.stepId,
                        collapsed = current.collapsed - intent.stepId,
                    )
                }

            is PackageEditorIntent.Move ->
                _state.update { it.copy(steps = it.steps.moveNode(intent.stepId, intent.up)) }

            is PackageEditorIntent.EditLoop ->
                _state.update { it.copy(dialog = PackageEditorDialog.LoopSettings(intent.loop)) }

            is PackageEditorIntent.UpdateLoop ->
                _state.update { current ->
                    current.copy(
                        steps = current.steps.withLoop(intent.stepId, intent.name, intent.count),
                        dialog = null,
                    )
                }

            // 解段：点击循环段行内的「解段」按钮（LinkOff 图标）触发
            is PackageEditorIntent.Ungroup ->
                _state.update { current ->
                    current.copy(
                        steps = current.steps.ungroupNode(intent.stepId),
                        collapsed = current.collapsed - intent.stepId,
                    )
                }

            is PackageEditorIntent.ToggleCollapse ->
                _state.update { current ->
                    current.copy(
                        collapsed =
                            if (intent.id in current.collapsed) current.collapsed - intent.id
                            else current.collapsed + intent.id
                    )
                }

            PackageEditorIntent.DismissDialog ->
                _state.update { it.copy(dialog = null) }

            PackageEditorIntent.MessageShown ->
                _state.update { it.copy(message = null) }
        }
    }

    /** 保存：名称非空后经仓库 upsert（内含 StructureValidator 校验）；失败弹中文原因 */
    private fun save() {
        val current = _state.value
        val name = current.packageName.trim()
        if (name.isEmpty()) {
            postMessage("函数包名称不能为空")
            return
        }
        viewModelScope.launch {
            val result = runCatching {
                repository.upsert(
                    FunctionPackage(
                        id = packageId,
                        name = name,
                        steps = current.steps,
                    )
                )
            }
            result.fold(
                onSuccess = { postMessage("已保存") },
                onFailure = { e ->
                    _state.update {
                        it.copy(
                            dialog = PackageEditorDialog.SaveFailed(e.message ?: "保存失败")
                        )
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
            it.copy(dialog = PackageEditorDialog.WrapSettings(ordered.first().id, ordered.last().id))
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

    /** 打开展示函数包调用选择：无其他函数包时给中文提示 */
    private fun showInsertCall() {
        val others = _state.value.packages.filter { it.id != packageId }
        if (others.isEmpty()) {
            postMessage("暂无可调用的其他函数包，请先在「函数包」页创建")
            return
        }
        _state.update { it.copy(dialog = PackageEditorDialog.InsertPackageCall) }
    }

    /** 插入函数包调用：在根层级末尾追加 PackageCall */
    private fun insertPackageCall(targetPackageId: String) {
        _state.update {
            it.copy(
                steps = it.steps + ScriptStep.PackageCall(newStepId(), targetPackageId),
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

    /**
     * 打开 BasicStep 编辑：
     * WaitImage 走只读弹窗，其余动作类型走参数表单弹窗。
     */
    private fun editStep(stepId: String) {
        val step = findStep(_state.value.steps, stepId)
        val action = (step as? ScriptStep.BasicStep)?.action ?: return
        _state.update {
            it.copy(
                dialog = if (action is Action.WaitImage) {
                    PackageEditorDialog.WaitImageReadOnly(action)
                } else {
                    PackageEditorDialog.EditAction(stepId, action)
                }
            )
        }
    }

    private fun postMessage(text: String) {
        messageSeq += 1
        _state.update { it.copy(message = PackageEditorMessage(messageSeq, text)) }
    }
}

// =================================================================================
// 步骤树纯函数操作（不可变更新，private，仅本文件使用）
// =================================================================================

/** 生成步骤节点新 id */
private fun newStepId(): String = "step-${UUID.randomUUID()}"

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

/** 节点在所属层级中的位置（用于上移 / 下移按钮可用性） */
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
 * 返回包含 [targetId] 节点的父层级列表（按引用相等判断）；
 * 节点位于根层级时返回根列表，找不到返回 null。
 */
private fun parentListOf(
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

/**
 * 按 DFS 顺序返回当前选中的节点（顺序与界面自上而下一致）。
 */
private fun orderedSelection(state: PackageEditorUiState): List<ScriptStep> {
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

/**
 * 统计步骤总数：顶层节点各计 1，循环段内子节点递归计入；
 * PackageCall 计 1（不展开包内步骤）。
 */
private fun countSteps(steps: List<ScriptStep>): Int =
    steps.sumOf { step ->
        1 + if (step is ScriptStep.LoopGroup) countSteps(step.steps) else 0
    }

/** 动作中文摘要（Tap 在按住时长 > 1ms 时追加展示） */
private fun Action.summaryZh(): String = when (this) {
    is Action.Tap ->
        if (holdMs > 1) "点击 ($x, $y) 按住 ${holdMs}ms" else "点击 ($x, $y)"
    is Action.LongPress -> "长按 ($x, $y) ${durationMs}ms"
    is Action.Swipe -> "滑动 ($x1,$y1)→($x2,$y2) ${durationMs}ms"
    is Action.Delay -> "等待 ${durationMs}ms"
    is Action.WaitImage -> "等待识图：$templateId"
    Action.GlobalHome -> "Home 键"
    Action.GlobalBack -> "返回键"
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

/** 同层级上移 / 下移：越界或本层未命中（继续递归）时结构保持不变 */
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
