package com.autoclicker.ui.editor

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.autoclicker.core.bus.RecordingSession
import com.autoclicker.core.flow.RecordFlow
import com.autoclicker.core.permission.PermissionChecker
import com.autoclicker.di.ServiceLocator
import com.autoclicker.domain.model.Action
import com.autoclicker.domain.model.FunctionPackage
import com.autoclicker.domain.model.GroupNode
import com.autoclicker.domain.model.Ids
import com.autoclicker.domain.model.ImageTemplate
import com.autoclicker.domain.model.Script
import com.autoclicker.domain.model.ScriptNode
import com.autoclicker.domain.model.StepNode
import com.autoclicker.domain.model.TextGroup
import com.autoclicker.domain.model.flattenSteps
import com.autoclicker.domain.rule.StructureValidator
import com.autoclicker.domain.rule.ValidationIssue
import com.autoclicker.ui.EditorAction
import kotlinx.coroutines.launch

/**
 * 脚本编辑器：步骤列表、分组、参数配置、动作添加与校验保存。
 *
 * [initialAction] 支持外部入口（悬浮球控制台的「动作编辑 / 新建步骤组 / 重命名」）直达对应操作。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptEditorScreen(
    scriptId: String,
    initialAction: String = EditorAction.NONE,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recordingScriptId by RecordingSession.scriptId.collectAsState()
    val recordingScriptName by RecordingSession.scriptName.collectAsState()

    var script by remember { mutableStateOf<Script?>(null) }
    var loading by remember { mutableStateOf(true) }
    var templates by remember { mutableStateOf<List<ImageTemplate>>(emptyList()) }
    var packages by remember { mutableStateOf<List<FunctionPackage>>(emptyList()) }
    var textGroups by remember { mutableStateOf<List<TextGroup>>(emptyList()) }
    var expandedGroups by remember { mutableStateOf<Set<String>>(emptySet()) }

    var pickerOpen by remember { mutableStateOf(false) }
    var pickerCategory by remember { mutableStateOf<ActionCategory?>(null) }
    var formRequest by remember { mutableStateOf<FormRequest?>(null) }
    var configStepId by remember { mutableStateOf<String?>(null) }
    var groupDialogOpen by remember { mutableStateOf(false) }
    var groupEditId by remember { mutableStateOf<String?>(null) }
    var groupName by remember { mutableStateOf("") }
    var groupLoop by remember { mutableStateOf("1") }
    // 新建步骤组时勾选要并入该组的顶层步骤 id
    var groupSelection by remember { mutableStateOf<Set<String>>(emptySet()) }
    var renameDialogOpen by remember { mutableStateOf(false) }
    var renameName by remember { mutableStateOf("") }
    var addParentId by remember { mutableStateOf<String?>(null) }
    var issues by remember { mutableStateOf<List<ValidationIssue>?>(null) }
    // 权限跳转后待重试开始录制的脚本
    var pendingRetry by remember { mutableStateOf<Script?>(null) }
    // 已连续跳转权限设置页的次数（上限 2，避免死循环）
    var permissionRedirects by remember { mutableStateOf(0) }

    LaunchedEffect(scriptId) {
        loading = true
        script = runCatching { ServiceLocator.scripts.get(scriptId) }.getOrNull()
        templates = runCatching { ServiceLocator.templates.list() }.getOrDefault(emptyList())
        packages = runCatching { ServiceLocator.packages.list() }.getOrDefault(emptyList())
        textGroups = runCatching { ServiceLocator.textGroups.list() }.getOrDefault(emptyList())
        loading = false
    }

    // 外部入口直达：载入完成后再弹对应对话框，避免列表还没数据就打开。
    LaunchedEffect(initialAction, loading) {
        if (loading) return@LaunchedEffect
        when (initialAction) {
            EditorAction.GROUP -> {
                groupEditId = null
                groupName = ""
                groupLoop = "1"
                groupSelection = emptySet()
                groupDialogOpen = true
            }

            EditorAction.RENAME -> {
                renameName = script?.name.orEmpty()
                renameDialogOpen = true
            }
        }
    }

    val toast: (String) -> Unit = { msg ->
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    // 权限设置页返回后：若存在待重试脚本则再次尝试开始录制；仍缺权限时最多再跳转一次
    var permissionLauncherHolder: ActivityResultLauncher<Intent>? = null
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val retry = pendingRetry
        if (retry != null) {
            scope.launch {
                when (val result = RecordFlow.bindAndRecord(context, retry)) {
                    is RecordFlow.StartResult.Started -> {
                        pendingRetry = null
                        permissionRedirects = 0
                        script = runCatching { ServiceLocator.scripts.get(scriptId) }.getOrNull() ?: script
                        toast("已开始录制到本任务，悬浮球已弹出")
                    }
                    is RecordFlow.StartResult.NeedPermission -> {
                        if (permissionRedirects >= 2) {
                            pendingRetry = null
                            toast("仍缺少${PermissionChecker.kindLabel(result.kind)}权限，请手动开启后再试")
                        } else {
                            permissionRedirects++
                            toast("请开启${PermissionChecker.kindLabel(result.kind)}权限")
                            permissionLauncherHolder?.launch(
                                PermissionChecker.settingsIntent(context, result.kind),
                            )
                        }
                    }
                    is RecordFlow.StartResult.Failed -> {
                        pendingRetry = null
                        toast(result.message)
                    }
                }
            }
        }
    }
    permissionLauncherHolder = permissionLauncher

    val updateNodes: (List<ScriptNode>) -> Unit = { newNodes ->
        script = script?.copy(nodes = newNodes)
    }

    val save: () -> Unit = {
        val s = script
        if (s == null) {
            toast("脚本未加载")
        } else {
            scope.launch {
                val saved = s.copy(nodes = s.nodes, updatedAt = System.currentTimeMillis())
                runCatching { ServiceLocator.scripts.save(saved) }
                    .onFailure { toast("保存失败：${it.message}") }
                script = saved
                // 悬浮窗控制台与编辑器共用录制会话，保存后同步，避免两处步骤不一致。
                RecordingSession.syncFrom(saved)
                val known = packages.map { it.id }.toSet()
                issues = StructureValidator.validateScript(saved, known)
            }
        }
    }

    /** 立即保存并同步会话（步骤组 / 重命名等即时操作使用，不弹校验结果）。 */
    val persist: (Script) -> Unit = { target ->
        script = target
        scope.launch {
            runCatching { ServiceLocator.scripts.save(target) }
                .onFailure { toast("保存失败：${it.message}") }
            RecordingSession.syncFrom(target)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = script?.name ?: "脚本编辑",
                        modifier = Modifier.clickable {
                            renameName = script?.name.orEmpty()
                            renameDialogOpen = true
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    val recordingThis = recordingScriptId == scriptId
                    TextButton(
                        onClick = {
                            if (recordingThis) {
                                scope.launch {
                                    RecordFlow.finishAndClose(context)
                                    script = runCatching { ServiceLocator.scripts.get(scriptId) }
                                        .getOrNull() ?: script
                                    val suffix = recordingScriptName?.let { "：$it" } ?: ""
                                    toast("已停止录制并保存$suffix")
                                }
                            } else {
                                val s = script
                                if (s == null) {
                                    toast("脚本未加载")
                                } else {
                                    scope.launch {
                                        val saved = s.copy(updatedAt = System.currentTimeMillis())
                                        runCatching { ServiceLocator.scripts.save(saved) }
                                            .onFailure { toast("保存失败：${it.message}") }
                                        script = saved
                                        when (val result = RecordFlow.bindAndRecord(context, saved)) {
                                            is RecordFlow.StartResult.Started -> {
                                                pendingRetry = null
                                                permissionRedirects = 0
                                                toast("已开始录制到本任务，悬浮球已弹出")
                                            }
                                            is RecordFlow.StartResult.NeedPermission -> {
                                                pendingRetry = saved
                                                permissionRedirects = 1
                                                toast("请先开启${PermissionChecker.kindLabel(result.kind)}权限")
                                                permissionLauncher.launch(
                                                    PermissionChecker.settingsIntent(context, result.kind),
                                                )
                                            }
                                            is RecordFlow.StartResult.Failed -> toast(result.message)
                                        }
                                    }
                                }
                            }
                        },
                    ) { Text(if (recordingThis) "停止录制并保存" else "录制到本任务") }
                    TextButton(onClick = save) { Text("保存") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            val s = script
            if (loading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("加载中…")
                }
            } else if (s == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("脚本不存在")
                }
            } else {
                val nodes = s.nodes
                val renderItems = remember(nodes, expandedGroups) { buildRenderList(nodes, expandedGroups) }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            addParentId = null
                            pickerCategory = null
                            pickerOpen = true
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("添加动作") }
                    OutlinedButton(
                        onClick = {
                            groupEditId = null
                            groupName = ""
                            groupLoop = "1"
                            groupSelection = emptySet()
                            groupDialogOpen = true
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("新建步骤组") }
                }

                if (renderItems.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("暂无步骤，点击「添加动作」", style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(renderItems, key = { it.node.id }) { item ->
                            when (val node = item.node) {
                                is StepNode -> StepCard(
                                    step = node,
                                    depth = item.depth,
                                    onMoveUp = { updateNodes(nodes.moveNode(node.id, -1)) },
                                    onMoveDown = { updateNodes(nodes.moveNode(node.id, 1)) },
                                    onMoveOut = if (item.parentId != null) {
                                        { updateNodes(nodes.moveOutOfGroup(node.id)) }
                                    } else {
                                        null
                                    },
                                    onEditAction = {
                                        formRequest = FormRequest(node.action, node.id, null)
                                    },
                                    onConfig = { configStepId = node.id },
                                    onDelete = { updateNodes(nodes.removeNode(node.id)) },
                                )
                                is GroupNode -> GroupCard(
                                    group = node,
                                    depth = item.depth,
                                    expanded = node.id in expandedGroups,
                                    onToggle = {
                                        expandedGroups = if (node.id in expandedGroups) {
                                            expandedGroups - node.id
                                        } else {
                                            expandedGroups + node.id
                                        }
                                    },
                                    onAddChild = {
                                        addParentId = node.id
                                        pickerCategory = null
                                        pickerOpen = true
                                    },
                                    onRename = {
                                        groupEditId = node.id
                                        groupName = node.name
                                        groupLoop = node.loopCount.toString()
                                        groupSelection = emptySet()
                                        groupDialogOpen = true
                                    },
                                    onMoveUp = { updateNodes(nodes.moveNode(node.id, -1)) },
                                    onMoveDown = { updateNodes(nodes.moveNode(node.id, 1)) },
                                    onDelete = {
                                        expandedGroups = expandedGroups - node.id
                                        updateNodes(nodes.removeNode(node.id))
                                    },
                                )
                            }
                        }
                        item { Spacer(Modifier.height(24.dp)) }
                    }
                }
            }
        }
    }

    // 动作选择底部弹层
    if (pickerOpen) {
        ModalBottomSheet(onDismissRequest = { pickerOpen = false; pickerCategory = null }) {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                val category = pickerCategory
                if (category == null) {
                    Text("选择动作分类", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(modifier = Modifier.height(360.dp)) {
                        items(ACTION_CATEGORIES, key = { it.title }) { cat ->
                            Column {
                                TextButton(
                                    onClick = { pickerCategory = cat },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(cat.title, modifier = Modifier.weight(1f))
                                    Text("${cat.entries.size} 项")
                                }
                            }
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { pickerCategory = null }) { Text("← 分类") }
                        Text(category.title, style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(modifier = Modifier.height(360.dp)) {
                        items(category.entries, key = { it.label }) { entry ->
                            TextButton(
                                onClick = {
                                    pickerOpen = false
                                    pickerCategory = null
                                    formRequest = FormRequest(entry.create(), null, addParentId)
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(entry.label, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    // 动作参数表单
    val req = formRequest
    if (req != null) {
        ActionFormDialog(
            initial = req.action,
            templates = templates,
            packages = packages,
            textGroups = textGroups,
            steps = script?.nodes?.flattenSteps().orEmpty(),
            onConfirm = { newAction ->
                val current = script?.nodes.orEmpty()
                val newNodes = if (req.stepId != null) {
                    current.updateNode(req.stepId) { n ->
                        if (n is StepNode) n.copy(action = newAction) else n
                    }
                } else {
                    current.appendTo(req.parentId, StepNode(id = Ids.newId(), action = newAction))
                }
                updateNodes(newNodes)
                formRequest = null
            },
            onDismiss = { formRequest = null },
        )
    }

    // 步骤设置
    val currentScript = script
    val configStep = configStepId?.let { id -> currentScript?.nodes?.flattenSteps()?.firstOrNull { it.id == id } }
    if (configStep != null) {
        StepConfigDialog(
            step = configStep,
            allSteps = currentScript?.nodes?.flattenSteps().orEmpty(),
            onConfirm = { updated ->
                updateNodes(currentScript?.nodes.orEmpty().updateNode(updated.id) { updated })
                configStepId = null
            },
            onDismiss = { configStepId = null },
        )
    }

    // 步骤组设置
    if (groupDialogOpen) {
        val topNodes = script?.nodes.orEmpty()
        AlertDialog(
            onDismissRequest = { groupDialogOpen = false },
            title = { Text(if (groupEditId == null) "新建步骤组" else "编辑步骤组") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 440.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    OutlinedTextField(
                        value = groupName,
                        onValueChange = { groupName = it },
                        label = { Text("组名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    NumberFieldRow("循环次数", groupLoop) { groupLoop = it }

                    // 新建时可勾选当前任务里已有的步骤，确定后这些步骤会被移入该步骤组。
                    if (groupEditId == null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "选择要并入该步骤组的步骤（不选则创建空组）",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (topNodes.isEmpty()) {
                            Text(
                                "当前任务还没有步骤",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            topNodes.forEachIndexed { index, node ->
                                when (node) {
                                    is StepNode -> Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Checkbox(
                                            checked = node.id in groupSelection,
                                            onCheckedChange = { checked ->
                                                groupSelection = if (checked) {
                                                    groupSelection + node.id
                                                } else {
                                                    groupSelection - node.id
                                                }
                                            },
                                        )
                                        Text(
                                            text = "#${index + 1} ${actionSummary(node.action)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }

                                    is GroupNode -> Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Checkbox(checked = false, onCheckedChange = null, enabled = false)
                                        Text(
                                            text = "#${index + 1} [组] ${node.name.ifBlank { "步骤组" }}（步骤组暂不支持并入）",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val s = script
                        if (s == null) {
                            groupDialogOpen = false
                        } else {
                            val loop = parseInt(groupLoop, 1).coerceAtLeast(1)
                            val name = groupName.ifBlank { "步骤组" }
                            val editingId = groupEditId
                            val newNodes = if (editingId == null) {
                                // 选中的顶层步骤（保持原顺序）移入新组，组插在第一个选中步骤的位置。
                                val picked = s.nodes.filterIsInstance<StepNode>()
                                    .filter { it.id in groupSelection }
                                val rest = s.nodes.filterNot { it is StepNode && it.id in groupSelection }
                                val firstPickedId = picked.firstOrNull()?.id
                                val insertAt = if (firstPickedId == null) {
                                    rest.size
                                } else {
                                    val firstIndex = s.nodes.indexOfFirst { it.id == firstPickedId }
                                    s.nodes.take(firstIndex.coerceAtLeast(0))
                                        .count { it !is StepNode || it.id !in groupSelection }
                                }
                                val group = GroupNode(
                                    id = Ids.newId(),
                                    name = name,
                                    loopCount = loop,
                                    children = picked,
                                )
                                rest.toMutableList().apply { add(insertAt.coerceIn(0, size), group) }
                            } else {
                                s.nodes.updateNode(editingId) { n ->
                                    if (n is GroupNode) n.copy(name = name, loopCount = loop) else n
                                }
                            }
                            persist(s.copy(nodes = newNodes, updatedAt = System.currentTimeMillis()))
                            groupDialogOpen = false
                            groupSelection = emptySet()
                        }
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { groupDialogOpen = false; groupSelection = emptySet() }) {
                    Text("取消")
                }
            },
        )
    }

    // 重命名任务（悬浮球控制台的「重命名」入口也会直达这里）
    if (renameDialogOpen) {
        AlertDialog(
            onDismissRequest = { renameDialogOpen = false },
            title = { Text("重命名任务") },
            text = {
                OutlinedTextField(
                    value = renameName,
                    onValueChange = { renameName = it },
                    label = { Text("任务名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val s = script
                        val newName = renameName.trim()
                        if (s != null && newName.isNotBlank() && newName != s.name) {
                            persist(s.copy(name = newName, updatedAt = System.currentTimeMillis()))
                        }
                        renameDialogOpen = false
                    },
                ) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { renameDialogOpen = false }) { Text("取消") } },
        )
    }

    // 校验结果
    val issueList = issues
    if (issueList != null) {
        AlertDialog(
            onDismissRequest = { issues = null },
            title = { Text("校验结果") },
            text = {
                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    if (issueList.isEmpty()) {
                        Text("未发现问题")
                    } else {
                        issueList.forEach { issue ->
                            val prefix = if (issue.level == ValidationIssue.Level.ERROR) "[错误]" else "[警告]"
                            Text("$prefix ${issue.message}")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { issues = null }) { Text("关闭") } },
        )
    }
}

@Composable
private fun StepCard(
    step: StepNode,
    depth: Int,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onMoveOut: (() -> Unit)?,
    onEditAction: () -> Unit,
    onConfig: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (depth * 16).dp, end = 8.dp),
        colors = CardDefaults.cardColors(),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    actionSummary(step.action),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                if (!step.enabled) {
                    Text("已禁用", style = MaterialTheme.typography.labelSmall)
                }
            }
            if (step.note.isNotBlank()) {
                Text(step.note, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "延时 ${step.delayAfterMs}ms · 次数 ${step.repeatCount}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onMoveUp, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "上移")
                }
                IconButton(onClick = onMoveDown, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "下移")
                }
                IconButton(onClick = onEditAction, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Edit, contentDescription = "编辑动作")
                }
                IconButton(onClick = onConfig, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Settings, contentDescription = "步骤设置")
                }
                if (onMoveOut != null) {
                    TextButton(onClick = onMoveOut) { Text("移出组") }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除")
                }
            }
        }
    }
}

@Composable
private fun GroupCard(
    group: GroupNode,
    depth: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    onAddChild: () -> Unit,
    onRename: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (depth * 16).dp, end = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onToggle) { Text(if (expanded) "收起" else "展开") }
                Text(
                    "组：${group.name.ifBlank { group.id.take(6) }} · 循环 ${group.loopCount} 次 · ${group.children.size} 步",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onAddChild, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Add, contentDescription = "组内添加")
                }
                IconButton(onClick = onRename, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Edit, contentDescription = "编辑组")
                }
                IconButton(onClick = onMoveUp, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "上移")
                }
                IconButton(onClick = onMoveDown, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "下移")
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除")
                }
            }
        }
    }
}

@Composable
private fun StepConfigDialog(
    step: StepNode,
    allSteps: List<StepNode>,
    onConfirm: (StepNode) -> Unit,
    onDismiss: () -> Unit,
) {
    var delay by remember { mutableStateOf(step.delayAfterMs.toString()) }
    var repeat by remember { mutableStateOf(step.repeatCount.toString()) }
    var note by remember { mutableStateOf(step.note) }
    var enabled by remember { mutableStateOf(step.enabled) }
    var success by remember { mutableStateOf(step.onSuccessStepId) }
    var failure by remember { mutableStateOf(step.onFailureStepId) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("步骤设置") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                NumberFieldRow("动作后延时（ms）", delay) { delay = it }
                NumberFieldRow("执行次数", repeat) { repeat = it }
                TextFieldRow("备注", note) { note = it }
                BoolFieldRow("启用", enabled) { enabled = it }
                // 跳转目标包含本步骤自身（选自己 = 成功/失败后重新执行本步骤，可做识别重试循环）。
                val options = listOf<Pair<String?, String>>(null to "（继续下一步）") +
                    allSteps.map {
                        it.id to (
                            (if (it.id == step.id) "本步骤自身 · " else "") +
                                "${it.id.take(6)} · ${actionSummary(it.action)}"
                            )
                    }
                ChoiceField("成功跳转", success, options) { success = it }
                ChoiceField("失败跳转", failure, options) { failure = it }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        step.copy(
                            delayAfterMs = parseLong(delay, step.delayAfterMs),
                            repeatCount = parseInt(repeat, step.repeatCount).coerceAtLeast(1),
                            note = note,
                            enabled = enabled,
                            onSuccessStepId = success,
                            onFailureStepId = failure,
                        ),
                    )
                },
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private data class FormRequest(
    val action: Action,
    val stepId: String?,
    val parentId: String?,
)

private data class RenderItem(
    val node: ScriptNode,
    val depth: Int,
    val parentId: String?,
    val indexInParent: Int,
    val siblingCount: Int,
)

private fun buildRenderList(nodes: List<ScriptNode>, expanded: Set<String>): List<RenderItem> {
    val out = ArrayList<RenderItem>()
    fun walk(list: List<ScriptNode>, depth: Int, parentId: String?) {
        list.forEachIndexed { index, node ->
            out.add(RenderItem(node, depth, parentId, index, list.size))
            if (node is GroupNode && node.id in expanded) {
                walk(node.children, depth + 1, node.id)
            }
        }
    }
    walk(nodes, 0, null)
    return out
}

private fun List<ScriptNode>.updateNode(id: String, transform: (ScriptNode) -> ScriptNode): List<ScriptNode> =
    map { node ->
        when {
            node.id == id -> transform(node)
            node is GroupNode -> node.copy(children = node.children.updateNode(id, transform))
            else -> node
        }
    }

private fun List<ScriptNode>.removeNode(id: String): List<ScriptNode> =
    filterNot { it.id == id }.map { if (it is GroupNode) it.copy(children = it.children.removeNode(id)) else it }

private fun List<ScriptNode>.moveNode(id: String, delta: Int): List<ScriptNode> {
    val idx = indexOfFirst { it.id == id }
    if (idx >= 0) {
        val j = idx + delta
        if (j !in indices) return this
        val m = toMutableList()
        val tmp = m[idx]
        m[idx] = m[j]
        m[j] = tmp
        return m
    }
    return map { if (it is GroupNode) it.copy(children = it.children.moveNode(id, delta)) else it }
}

private fun List<ScriptNode>.appendTo(parentId: String?, node: ScriptNode): List<ScriptNode> =
    if (parentId == null) {
        this + node
    } else {
        map { if (it is GroupNode && it.id == parentId) it.copy(children = it.children + node) else it }
    }

/** 把步骤从所属步骤组中移出，放到该组之后的顶层位置。 */
private fun List<ScriptNode>.moveOutOfGroup(id: String): List<ScriptNode> {
    val groupIndex = indexOfFirst { it is GroupNode && it.children.any { c -> c.id == id } }
    if (groupIndex < 0) return this
    val group = this[groupIndex] as GroupNode
    val child = group.children.firstOrNull { it.id == id } ?: return this
    val list = toMutableList()
    list[groupIndex] = group.copy(children = group.children.filterNot { it.id == id })
    list.add(groupIndex + 1, child)
    return list
}