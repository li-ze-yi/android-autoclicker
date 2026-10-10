package com.autoclicker.ui.record

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.autoclicker.core.bus.RecordingSession
import com.autoclicker.core.bus.RecordingState
import com.autoclicker.core.bus.RuntimeBus
import com.autoclicker.core.flow.RecordFlow
import com.autoclicker.core.permission.PermissionChecker
import com.autoclicker.di.ServiceLocator
import com.autoclicker.domain.model.Action
import com.autoclicker.domain.model.ConditionAction
import com.autoclicker.domain.model.DelayAction
import com.autoclicker.domain.model.EmptyAction
import com.autoclicker.domain.model.GestureAction
import com.autoclicker.domain.model.GlobalKeyAction
import com.autoclicker.domain.model.GroupNode
import com.autoclicker.domain.model.InputTextAction
import com.autoclicker.domain.model.JumpAction
import com.autoclicker.domain.model.LongPressAction
import com.autoclicker.domain.model.Script
import com.autoclicker.domain.model.StepNode
import com.autoclicker.domain.model.SwipeAction
import com.autoclicker.domain.model.ToastAction
import com.autoclicker.domain.model.VariableOpAction
import com.autoclicker.service.record.Recorder
import kotlinx.coroutines.launch

/**
 * 录制页：一条龙流程的入口。
 *
 * 「建任务 → 检查/跳转权限 → 弹悬浮球 → 录制入库」由 [RecordFlow] 编排，
 * 本页负责触发、展示实时步骤（订阅 [RecordingSession.nodes]）与编辑步参数；
 * 点击「停止」即调用 [RecordFlow.finishAndClose] 停止录制并关闭悬浮球。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingScreen(onBack: () -> Unit) {
    val nodes by RecordingSession.nodes.collectAsState()
    val recording by RuntimeBus.recording.collectAsState()
    val boundScriptName by RecordingSession.scriptName.collectAsState()
    val scope = rememberCoroutineScope()
    val context = ServiceLocator.context

    var showNewDialog by remember { mutableStateOf(false) }
    var newTaskName by remember { mutableStateOf("") }
    var showPickDialog by remember { mutableStateOf(false) }
    var scriptList by remember { mutableStateOf<List<Script>>(emptyList()) }
    var listLoading by remember { mutableStateOf(false) }

    // 待重试的入口参数：权限设置页返回后据此重试；最多连续跳转 2 次。
    var retryName by remember { mutableStateOf<String?>(null) }
    var retryScript by remember { mutableStateOf<Script?>(null) }
    var redirectCount by remember { mutableStateOf(0) }
    var retryTick by remember { mutableStateOf(0) }

    val toast: (String) -> Unit = { msg ->
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    // 权限设置页返回：只自增计数，真正的重试放在后面的 LaunchedEffect 中（避免前向引用局部函数）。
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        retryTick++
    }

    val promptPermission: (PermissionChecker.Kind, String?, Script?) -> Unit = { kind, name, script ->
        if (redirectCount >= 2) {
            toast("仍缺少「${PermissionChecker.kindLabel(kind)}」，请手动开启后再试")
        } else {
            redirectCount++
            retryName = name
            retryScript = script
            toast("请先开启${PermissionChecker.kindLabel(kind)}，已为你打开设置页")
            permissionLauncher.launch(PermissionChecker.settingsIntent(context, kind))
        }
    }

    val handleResult: (RecordFlow.StartResult, String?, Script?) -> Unit = { result, name, script ->
        when (result) {
            is RecordFlow.StartResult.NeedPermission -> promptPermission(result.kind, name, script)
            is RecordFlow.StartResult.Started -> {
                redirectCount = 0
                toast(
                    if (name != null) "已创建任务并开始录制，悬浮球已弹出"
                    else "已绑定任务并开始录制，悬浮球已弹出"
                )
            }
            is RecordFlow.StartResult.Failed -> toast("操作失败：${result.message}")
        }
    }

    LaunchedEffect(retryTick) {
        if (retryTick == 0) return@LaunchedEffect
        val name = retryName
        val script = retryScript
        retryName = null
        retryScript = null
        when {
            name != null -> handleResult(RecordFlow.createTaskAndRecord(context, name), name, null)
            script != null -> handleResult(RecordFlow.bindAndRecord(context, script), null, script)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("录制") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (nodes.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                RecordingSession.nodes.value.forEach { RecordingSession.removeNode(it.id) }
                            },
                        ) { Text("清空") }
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            Text(
                text = "录制任务：${boundScriptName ?: "未绑定任务"}",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp),
            )
            if (boundScriptName == null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            newTaskName = ""
                            redirectCount = 0
                            showNewDialog = true
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("新建任务并录制") }
                    OutlinedButton(
                        onClick = {
                            showPickDialog = true
                            listLoading = true
                            scope.launch {
                                scriptList = runCatching { ServiceLocator.scripts.list() }
                                    .getOrDefault(emptyList())
                                listLoading = false
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("选择已有任务") }
                }
            }
            RecordingControls(
                recording = recording,
                onPause = { Recorder.pause(context) },
                onStop = {
                    scope.launch {
                        RecordFlow.finishAndClose(context)
                        toast("已保存到任务并关闭悬浮球")
                    }
                },
                onPick = pick@{
                    if (!PermissionChecker.requireOverlay(context)) return@pick
                    Recorder.pickPoint(context)
                },
            )
            Text(
                text = "任务共 ${nodes.size} 步 · 录制期间可正常操作目标 App，步骤会自动保存到当前任务",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(nodes, key = { _, node -> node.id }) { index, node ->
                    when (node) {
                        is StepNode -> StepEditorCard(index = index, step = node)
                        is GroupNode -> GroupEditorCard(index = index, group = node)
                    }
                }
            }
        }
    }

    if (showNewDialog) {
        AlertDialog(
            onDismissRequest = { showNewDialog = false },
            title = { Text("新建任务并录制") },
            text = {
                OutlinedTextField(
                    value = newTaskName,
                    onValueChange = { newTaskName = it },
                    label = { Text("任务名称") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = newTaskName.trim().ifBlank { "录制任务" }
                    showNewDialog = false
                    redirectCount = 0
                    scope.launch {
                        handleResult(RecordFlow.createTaskAndRecord(context, name), name, null)
                    }
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showNewDialog = false }) { Text("取消") }
            },
        )
    }

    if (showPickDialog) {
        AlertDialog(
            onDismissRequest = { showPickDialog = false },
            title = { Text("选择已有任务") },
            text = {
                if (listLoading) {
                    Text("加载中…")
                } else if (scriptList.isEmpty()) {
                    Text("暂无任务，请先新建任务")
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                        items(scriptList, key = { it.id }) { script ->
                            TextButton(
                                onClick = {
                                    showPickDialog = false
                                    redirectCount = 0
                                    scope.launch {
                                        handleResult(
                                            RecordFlow.bindAndRecord(context, script),
                                            null,
                                            script,
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(script.name, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPickDialog = false }) { Text("关闭") }
            },
        )
    }
}

@Composable
private fun RecordingControls(
    recording: RecordingState,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onPick: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "状态：${recordingLabel(recording)}",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = onPause,
                enabled = recording != RecordingState.IDLE,
            ) { Text(if (recording == RecordingState.PAUSED) "继续" else "暂停") }
            OutlinedButton(
                onClick = onStop,
                enabled = recording != RecordingState.IDLE,
            ) { Text("停止") }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onPick,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("手动取点（单击屏幕位置补一个点击步骤）") }
    }
}

@Composable
private fun StepEditorCard(index: Int, step: StepNode) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("#${index + 1}", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = actionSummary(step.action),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { RecordingSession.removeNode(step.id) }) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除步骤")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = step.delayAfterMs.toString(),
                    onValueChange = { text ->
                        val value = text.filter { it.isDigit() }.toLongOrNull() ?: 0L
                        RecordingSession.updateNode(step.copy(delayAfterMs = value))
                    },
                    label = { Text("延时(ms)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = step.repeatCount.toString(),
                    onValueChange = { text ->
                        val value = (text.filter { it.isDigit() }.toIntOrNull() ?: 1).coerceAtLeast(1)
                        RecordingSession.updateNode(step.copy(repeatCount = value))
                    },
                    label = { Text("重复") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
            OutlinedTextField(
                value = step.note,
                onValueChange = { RecordingSession.updateNode(step.copy(note = it)) },
                label = { Text("备注") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun GroupEditorCard(index: Int, group: GroupNode) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("#${index + 1}", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(8.dp))
            Text(
                text = "[组] ${group.name} ×${group.loopCount}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { RecordingSession.moveNode(group.id, -1) }) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "上移")
            }
            IconButton(onClick = { RecordingSession.moveNode(group.id, 1) }) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "下移")
            }
            IconButton(onClick = { RecordingSession.removeNode(group.id) }) {
                Icon(Icons.Filled.Delete, contentDescription = "删除步骤")
            }
        }
    }
}

private fun recordingLabel(state: RecordingState): String = when (state) {
    RecordingState.IDLE -> "空闲"
    RecordingState.RECORDING -> "录制中"
    RecordingState.PAUSED -> "已暂停"
}

private fun actionSummary(action: Action): String = when (action) {
    is com.autoclicker.domain.model.ClickAction -> "点击 (${formatPercent(action.point.x)}, ${formatPercent(action.point.y)})"
    is LongPressAction -> "长按 (${formatPercent(action.point.x)}, ${formatPercent(action.point.y)})"
    is SwipeAction -> "滑动"
    is GestureAction -> "手势"
    is GlobalKeyAction -> "全局键 ${action.key}"
    is DelayAction -> "延时 ${action.ms}ms"
    is InputTextAction -> "输入文字"
    is VariableOpAction -> "变量操作 ${action.varName}"
    is ConditionAction -> "条件判断"
    is JumpAction -> "跳转"
    is ToastAction -> "提示：${action.message}"
    is EmptyAction -> "空步骤"
    else -> action::class.simpleName ?: "动作"
}

private fun formatPercent(value: Float): String =
    (Math.round(value * 1000f) / 10f).toString() + "%"