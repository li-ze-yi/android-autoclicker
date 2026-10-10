package com.autoclicker.ui.record

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autoclicker.MyApplication
import com.autoclicker.core.bus.EngineState
import com.autoclicker.core.data.scripts.ScriptFileRepository
import com.autoclicker.domain.model.Action
import com.autoclicker.domain.model.Script
import com.autoclicker.domain.model.ScriptStep
import kotlinx.coroutines.launch

/**
 * 录制界面：实时显示已录步骤，支持删除、延时编辑、手动添加 Home/返回，
 * 保存为脚本或保存并运行（FR-5 / Task 9）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingScreen(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RecordingViewModel = rememberRecordingViewModel(),
) {
    val steps by viewModel.steps.collectAsState()
    val engineState by viewModel.engineState.collectAsState()

    var editingDelay by remember { mutableStateOf<ScriptStep?>(null) }
    var saving by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(title = { Text("录制中") })
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(8.dp))

            // 手动添加系统键
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { viewModel.addGlobalHome() }) { Text("添加 Home") }
                OutlinedButton(onClick = { viewModel.addGlobalBack() }) { Text("添加 返回") }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                if (steps.isEmpty()) "还没有录制到动作，请到其他 App 操作（精确模式可录点击与滑动）"
                else "已录制 ${steps.size} 步",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(steps, key = { it.id }) { step ->
                    StepRow(
                        step = step,
                        onDelete = { viewModel.removeStep(step.id) },
                        onEditDelay = { editingDelay = step },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // 底部操作
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { saving = true },
                    modifier = Modifier.weight(1f),
                    enabled = steps.isNotEmpty(),
                ) { Text("保存脚本") }
                OutlinedButton(
                    onClick = {
                        viewModel.stopRecording()
                        onFinished()
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("放弃并结束") }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    // 延时编辑对话框
    editingDelay?.let { step ->
        val action = (step as? ScriptStep.BasicStep)?.action as? Action.Delay
        if (action != null) {
            var text by remember(step.id) {
                mutableStateOf((action.durationMs / 1000.0).let {
                    if (action.durationMs % 1000 == 0L) (action.durationMs / 1000).toString()
                    else (action.durationMs / 1000.0).toString()
                })
            }
            AlertDialog(
                onDismissRequest = { editingDelay = null },
                title = { Text("延时（秒）") },
                text = {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        text.toDoubleOrNull()?.let { seconds ->
                            viewModel.updateDelay(step.id, (seconds * 1000).toLong())
                        }
                        editingDelay = null
                    }) { Text("确定") }
                },
                dismissButton = {
                    TextButton(onClick = { editingDelay = null }) { Text("取消") }
                },
            )
        }
    }

    // 保存对话框
    if (saving) {
        var name by remember { mutableStateOf("新建脚本") }
        AlertDialog(
            onDismissRequest = { saving = false },
            title = { Text("保存脚本") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it })
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.save(name) {
                        saving = false
                        viewModel.stopRecording()
                        onFinished()
                    }
                }) { Text("仅保存") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        viewModel.saveAndRun(name) {
                            saving = false
                            onFinished()
                        }
                    }) { Text("保存并运行") }
                    TextButton(onClick = { saving = false }) { Text("取消") }
                }
            },
        )
    }
}

/** 单个步骤卡片：摘要 + 延时编辑 + 删除 */
@Composable
private fun StepRow(
    step: ScriptStep,
    onDelete: () -> Unit,
    onEditDelay: () -> Unit,
) {
    val action = (step as? ScriptStep.BasicStep)?.action
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(action.summary(), style = MaterialTheme.typography.bodyLarge)
            }
            if (action is Action.Delay) {
                TextButton(onClick = onEditDelay) { Text("编辑") }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    androidx.compose.material.icons.Icons.Filled.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** 动作的中文摘要 */
private fun Action?.summary(): String = when (this) {
    is Action.Tap -> "点击 ($x, $y)"
    is Action.LongPress -> "长按 ($x, $y) ${durationMs}ms"
    is Action.Swipe -> "滑动 ($x1,$y1)→($x2,$y2) ${durationMs}ms"
    is Action.Delay -> "等待 ${durationMs}ms"
    Action.GlobalHome -> "Home 键"
    Action.GlobalBack -> "返回键"
    is Action.WaitImage -> "等待识图：$templateId"
    null -> ""
}
