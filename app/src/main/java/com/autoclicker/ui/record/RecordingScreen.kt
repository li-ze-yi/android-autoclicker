package com.autoclicker.ui.record

import android.widget.Toast
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
import com.autoclicker.core.bus.RecorderBus
import com.autoclicker.core.bus.RecordingSession
import com.autoclicker.core.bus.RecordingState
import com.autoclicker.core.bus.RuntimeBus
import com.autoclicker.core.permission.PermissionChecker
import com.autoclicker.di.ServiceLocator
import com.autoclicker.domain.model.Action
import com.autoclicker.domain.model.ConditionAction
import com.autoclicker.domain.model.DelayAction
import com.autoclicker.domain.model.EmptyAction
import com.autoclicker.domain.model.GestureAction
import com.autoclicker.domain.model.GlobalKeyAction
import com.autoclicker.domain.model.Ids
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
 * 录制页：订阅 [RecorderBus.steps] 展示实时采集到的步骤，可编辑步参数并保存为任务。
 *
 * 录制开关委托给 [Recorder] 的静态方法（start/pause/stop），由录制服务实现。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingScreen(onBack: () -> Unit) {
    val steps by RecorderBus.steps.collectAsState()
    val recording by RuntimeBus.recording.collectAsState()
    val boundScriptName by RecordingSession.scriptName.collectAsState()
    val scope = rememberCoroutineScope()
    val context = ServiceLocator.context

    var showNewDialog by remember { mutableStateOf(false) }
    var newTaskName by remember { mutableStateOf("") }
    var showPickDialog by remember { mutableStateOf(false) }
    var scriptList by remember { mutableStateOf<List<Script>>(emptyList()) }
    var listLoading by remember { mutableStateOf(false) }

    val toast: (String) -> Unit = { msg ->
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
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
                    if (steps.isNotEmpty()) {
                        TextButton(onClick = { RecorderBus.clear() }) { Text("清空") }
                    }
                },
            )
        },
        bottomBar = {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Button(
                    onClick = {
                        scope.launch {
                            val ok = RecordingSession.saveNow()
                            toast(if (ok) "已保存到任务" else "未绑定任务或保存失败")
                        }
                    },
                    enabled = steps.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("保存到任务") }
            }
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
                onStart = start@{
                    if (!PermissionChecker.requireAccessibility(context)) return@start
                    if (!PermissionChecker.requireOverlay(context)) return@start
                    if (!RecordingSession.isActive) {
                        toast("请先创建或选择任务")
                        return@start
                    }
                    Recorder.start(context)
                },
                onPause = { Recorder.pause(context) },
                onStop = { Recorder.stop(context) },
                onPick = pick@{
                    if (!PermissionChecker.requireOverlay(context)) return@pick
                    Recorder.pickPoint(context)
                },
            )
            Text(
                text = "已录制 ${steps.size} 步 · 录制期间可正常操作目标 App，步骤会自动保存到当前任务",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(steps, key = { _, step -> step.id }) { index, step ->
                    StepEditorCard(index = index, step = step)
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
                    val script = Script(id = Ids.newId(), name = name)
                    showNewDialog = false
                    scope.launch {
                        runCatching { ServiceLocator.scripts.save(script) }
                            .onFailure { toast("创建任务失败：${it.message}") }
                        RecordingSession.begin(script)
                        toast("已绑定任务：$name")
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
                                    RecordingSession.begin(script)
                                    showPickDialog = false
                                    toast("已绑定任务：${script.name}")
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
    onStart: () -> Unit,
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
            Button(onClick = onStart, enabled = recording == RecordingState.IDLE) { Text("开始") }
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
                IconButton(onClick = { RecorderBus.removeStep(step.id) }) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除步骤")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = step.delayAfterMs.toString(),
                    onValueChange = { text ->
                        val value = text.filter { it.isDigit() }.toLongOrNull() ?: 0L
                        RecorderBus.updateStep(step.copy(delayAfterMs = value))
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
                        RecorderBus.updateStep(step.copy(repeatCount = value))
                    },
                    label = { Text("重复") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
            OutlinedTextField(
                value = step.note,
                onValueChange = { RecorderBus.updateStep(step.copy(note = it)) },
                label = { Text("备注") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
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