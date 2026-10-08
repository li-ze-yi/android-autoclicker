package com.autoclicker.ui.editor

import android.app.TimePickerDialog
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import com.autoclicker.core.overlay.OverlayService
import com.autoclicker.core.overlay.PickPointBridge
import com.autoclicker.core.script.OnTimeout
import com.autoclicker.core.script.Step
import com.autoclicker.core.script.describe
import com.autoclicker.core.script.typeLabel
import com.autoclicker.core.trigger.TriggerRecord
import com.autoclicker.core.trigger.TriggerScheduler
import com.autoclicker.core.util.PermissionChecker
import kotlin.math.roundToInt

private val ROW_HEIGHT = 64.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(scriptId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val viewModel: EditorViewModel = viewModel()
    LaunchedEffect(scriptId) { viewModel.load(scriptId) }

    val script by viewModel.script.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<Step?>(null) }

    val rowHeightPx = with(LocalDensity.current) { ROW_HEIGHT.toPx() }
    var draggingIndex by remember { mutableIntStateOf(-1) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }

    var triggerEnabled by remember { mutableStateOf(false) }
    var triggerHour by remember { mutableIntStateOf(8) }
    var triggerMinute by remember { mutableIntStateOf(0) }

    LaunchedEffect(scriptId) {
        val existing = TriggerScheduler.get(context).list().find { it.scriptId == scriptId }
        if (existing != null) {
            triggerEnabled = existing.enabled
            triggerHour = existing.hour
            triggerMinute = existing.minute
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val name = script?.name
                    Text(if (name.isNullOrBlank()) "未命名脚本" else name)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = {
                        viewModel.save()
                        Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
                    }) {
                        Text("保存")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "添加步骤")
            }
        }
    ) { innerPadding ->
        val current = script
        if (current == null) {
            Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                Text("加载中")
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = current.name,
                    onValueChange = { viewModel.updateName(it) },
                    label = { Text("脚本名") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("停止于错误", modifier = Modifier.weight(1f))
                    Switch(
                        checked = current.stopOnError,
                        onCheckedChange = { viewModel.updateStopOnError(it) }
                    )
                }

                HorizontalDivider()
                Text("步骤（${current.steps.size}）", style = MaterialTheme.typography.titleMedium)

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    itemsIndexed(current.steps, key = { _, item -> item.id }) { index, step ->
                        StepRow(
                            index = index,
                            step = step,
                            isDragging = index == draggingIndex,
                            dragOffsetY = dragOffsetY,
                            onEdit = { editTarget = step },
                            onDelete = { viewModel.removeStep(step.id) },
                            onMoveUp = { if (index > 0) viewModel.moveStep(index, index - 1) },
                            onMoveDown = {
                                if (index < current.steps.lastIndex) viewModel.moveStep(index, index + 1)
                            },
                            onDragStart = { startIndex ->
                                draggingIndex = startIndex
                                dragOffsetY = 0f
                            },
                            onDragBy = { dy ->
                                if (draggingIndex >= 0) {
                                    dragOffsetY += dy
                                    val shift = (dragOffsetY / rowHeightPx).roundToInt()
                                    if (shift != 0) {
                                        val target = (draggingIndex + shift)
                                            .coerceIn(0, current.steps.lastIndex)
                                        if (target != draggingIndex) {
                                            viewModel.moveStep(draggingIndex, target)
                                            draggingIndex = target
                                            dragOffsetY -= shift * rowHeightPx
                                        }
                                    }
                                }
                            },
                            onDragStop = {
                                draggingIndex = -1
                                dragOffsetY = 0f
                            }
                        )
                    }
                }

                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("定时运行", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = if (triggerEnabled) {
                                "每天 ${"%02d:%02d".format(triggerHour, triggerMinute)}"
                            } else {
                                "未启用"
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = triggerEnabled,
                        onCheckedChange = { enabled ->
                            triggerEnabled = enabled
                            val scheduler = TriggerScheduler.get(context)
                            if (enabled) {
                                scheduler.schedule(
                                    TriggerRecord(
                                        scriptId = scriptId,
                                        scriptName = current.name,
                                        hour = triggerHour,
                                        minute = triggerMinute,
                                        enabled = true
                                    )
                                )
                            } else {
                                scheduler.cancel(scriptId)
                            }
                        }
                    )
                }
                OutlinedButton(
                    onClick = {
                        TimePickerDialog(
                            context,
                            { _, h, m ->
                                triggerHour = h
                                triggerMinute = m
                                if (triggerEnabled) {
                                    TriggerScheduler.get(context).schedule(
                                        TriggerRecord(
                                            scriptId = scriptId,
                                            scriptName = current.name,
                                            hour = h,
                                            minute = m,
                                            enabled = true
                                        )
                                    )
                                }
                            },
                            triggerHour,
                            triggerMinute,
                            true
                        ).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("选择时间")
                }
            }
        }
    }

    if (showAddDialog) {
        val metrics = context.resources.displayMetrics
        val centerX = metrics.widthPixels / 2f
        val centerY = metrics.heightPixels / 2f
        val entries = listOf<Pair<String, () -> Step>>(
            "点击" to { Step.Tap(x = centerX, y = centerY) },
            "长按" to { Step.LongPress(x = centerX, y = centerY) },
            "滑动" to { Step.Swipe(x1 = centerX, y1 = centerY, x2 = centerX, y2 = centerY - 300f) },
            "输入文本" to { Step.Input(text = "") },
            "等待" to { Step.Wait() },
            "启动应用" to { Step.LaunchApp(packageName = "") },
            "等待元素" to { Step.WaitForElement() },
            "返回键" to { Step.Back() },
            "主页键" to { Step.Home() }
        )
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("选择步骤类型") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    entries.forEach { entry ->
                        TextButton(
                            onClick = {
                                viewModel.addStep(entry.second())
                                showAddDialog = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(entry.first)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("取消") }
            }
        )
    }

    editTarget?.let { target ->
        StepEditDialog(
            step = target,
            onDismiss = { editTarget = null },
            onConfirm = { updated ->
                viewModel.updateStep(updated)
                editTarget = null
            }
        )
    }
}

@Composable
private fun StepRow(
    index: Int,
    step: Step,
    isDragging: Boolean,
    dragOffsetY: Float,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDragStart: (Int) -> Unit,
    onDragBy: (Float) -> Unit,
    onDragStop: () -> Unit
) {
    val currentIndex by rememberUpdatedState(index)
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDragBy by rememberUpdatedState(onDragBy)
    val currentOnDragStop by rememberUpdatedState(onDragStop)
    val shape = RoundedCornerShape(8.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .zIndex(if (isDragging) 1f else 0f)
            .graphicsLayer { translationY = if (isDragging) dragOffsetY else 0f }
            .shadow(if (isDragging) 8.dp else 0.dp, shape)
            .background(
                color = if (isDragging) {
                    MaterialTheme.colorScheme.surfaceVariant
                } else {
                    MaterialTheme.colorScheme.surface
                },
                shape = shape
            )
            .pointerInput(step.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { currentOnDragStart(currentIndex) },
                    onDrag = { change, amount ->
                        change.consume()
                        currentOnDragBy(amount.y)
                    },
                    onDragEnd = { currentOnDragStop() },
                    onDragCancel = { currentOnDragStop() }
                )
            }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "${index + 1}",
            modifier = Modifier.width(28.dp),
            style = MaterialTheme.typography.bodyMedium
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = step.typeLabel, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = step.describe(),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1
            )
        }
        IconButton(onClick = onMoveUp) {
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "上移")
        }
        IconButton(onClick = onMoveDown) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "下移")
        }
        IconButton(onClick = onEdit) {
            Icon(Icons.Filled.Edit, contentDescription = "编辑")
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "删除")
        }
    }
}

@Composable
private fun StepEditDialog(
    step: Step,
    onDismiss: () -> Unit,
    onConfirm: (Step) -> Unit
) {
    val context = LocalContext.current
    var note by remember(step.id) { mutableStateOf(step.note) }
    var xStr by remember(step.id) { mutableStateOf(initialX(step)) }
    var yStr by remember(step.id) { mutableStateOf(initialY(step)) }
    var x1Str by remember(step.id) { mutableStateOf(initialX1(step)) }
    var y1Str by remember(step.id) { mutableStateOf(initialY1(step)) }
    var x2Str by remember(step.id) { mutableStateOf(initialX2(step)) }
    var y2Str by remember(step.id) { mutableStateOf(initialY2(step)) }
    var durationStr by remember(step.id) { mutableStateOf(initialDuration(step)) }
    var textStr by remember(step.id) { mutableStateOf(initialText(step)) }
    var pkgStr by remember(step.id) {
        mutableStateOf((step as? Step.LaunchApp)?.packageName ?: "")
    }
    var viewIdStr by remember(step.id) {
        mutableStateOf((step as? Step.WaitForElement)?.viewId ?: "")
    }
    var descStr by remember(step.id) {
        mutableStateOf((step as? Step.WaitForElement)?.contentDesc ?: "")
    }
    var classNameStr by remember(step.id) {
        mutableStateOf((step as? Step.WaitForElement)?.className ?: "")
    }
    var timeoutStr by remember(step.id) {
        mutableStateOf(((step as? Step.WaitForElement)?.timeoutMs ?: 10000L).toString())
    }
    var onTimeout by remember(step.id) {
        mutableStateOf((step as? Step.WaitForElement)?.onTimeout ?: OnTimeout.STOP)
    }
    var error by remember(step.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(step.id) {
        PickPointBridge.clear()
        PickPointBridge.picked.collect { point ->
            if (point != null) {
                xStr = point.x.toEditable()
                yStr = point.y.toEditable()
                PickPointBridge.clear()
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(step.typeLabel) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when (step) {
                    is Step.Tap -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = xStr,
                                onValueChange = { xStr = it },
                                label = { Text("X") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = yStr,
                                onValueChange = { yStr = it },
                                label = { Text("Y") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                if (!PermissionChecker.isOverlayGranted(context)) {
                                    PermissionChecker.openOverlaySettings(context)
                                } else {
                                    OverlayService.startPickPoint(context)
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("拾取坐标")
                        }
                    }

                    is Step.LongPress -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = xStr,
                                onValueChange = { xStr = it },
                                label = { Text("X") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = yStr,
                                onValueChange = { yStr = it },
                                label = { Text("Y") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                        }
                        OutlinedTextField(
                            value = durationStr,
                            onValueChange = { durationStr = it },
                            label = { Text("时长(ms)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }

                    is Step.Swipe -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = x1Str,
                                onValueChange = { x1Str = it },
                                label = { Text("X1") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = y1Str,
                                onValueChange = { y1Str = it },
                                label = { Text("Y1") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = x2Str,
                                onValueChange = { x2Str = it },
                                label = { Text("X2") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = y2Str,
                                onValueChange = { y2Str = it },
                                label = { Text("Y2") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                        }
                        OutlinedTextField(
                            value = durationStr,
                            onValueChange = { durationStr = it },
                            label = { Text("时长(ms)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }

                    is Step.Input -> {
                        OutlinedTextField(
                            value = textStr,
                            onValueChange = { textStr = it },
                            label = { Text("文本") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    is Step.Wait -> {
                        OutlinedTextField(
                            value = durationStr,
                            onValueChange = { durationStr = it },
                            label = { Text("时长(ms)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }

                    is Step.LaunchApp -> {
                        OutlinedTextField(
                            value = pkgStr,
                            onValueChange = { pkgStr = it },
                            label = { Text("包名") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }

                    is Step.WaitForElement -> {
                        OutlinedTextField(
                            value = textStr,
                            onValueChange = { textStr = it },
                            label = { Text("文本（可空）") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = viewIdStr,
                            onValueChange = { viewIdStr = it },
                            label = { Text("资源ID（可空）") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = descStr,
                            onValueChange = { descStr = it },
                            label = { Text("描述（可空）") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = classNameStr,
                            onValueChange = { classNameStr = it },
                            label = { Text("类名（可空）") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = timeoutStr,
                            onValueChange = { timeoutStr = it },
                            label = { Text("超时(ms)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = onTimeout == OnTimeout.STOP,
                                onClick = { onTimeout = OnTimeout.STOP }
                            )
                            Text("超时停止")
                            RadioButton(
                                selected = onTimeout == OnTimeout.SKIP,
                                onClick = { onTimeout = OnTimeout.SKIP }
                            )
                            Text("超时跳过")
                        }
                    }

                    is Step.Back, is Step.Home -> {
                        Text("该步骤没有参数")
                    }
                }

                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注（可选）") },
                    modifier = Modifier.fillMaxWidth()
                )

                error?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                error = null
                when (step) {
                    is Step.Tap -> {
                        val x = xStr.trim().toFloatOrNull()
                        val y = yStr.trim().toFloatOrNull()
                        if (x == null || y == null) {
                            error = "请输入有效的 X、Y 坐标"
                        } else {
                            onConfirm(Step.Tap(id = step.id, note = note, x = x, y = y))
                        }
                    }

                    is Step.LongPress -> {
                        val x = xStr.trim().toFloatOrNull()
                        val y = yStr.trim().toFloatOrNull()
                        val d = durationStr.trim().toLongOrNull()
                        if (x == null || y == null || d == null) {
                            error = "请检查坐标与时长"
                        } else {
                            onConfirm(
                                Step.LongPress(id = step.id, note = note, x = x, y = y, durationMs = d)
                            )
                        }
                    }

                    is Step.Swipe -> {
                        val x1 = x1Str.trim().toFloatOrNull()
                        val y1 = y1Str.trim().toFloatOrNull()
                        val x2 = x2Str.trim().toFloatOrNull()
                        val y2 = y2Str.trim().toFloatOrNull()
                        val d = durationStr.trim().toLongOrNull()
                        if (x1 == null || y1 == null || x2 == null || y2 == null || d == null) {
                            error = "请检查坐标与时长"
                        } else {
                            onConfirm(
                                Step.Swipe(
                                    id = step.id,
                                    note = note,
                                    x1 = x1,
                                    y1 = y1,
                                    x2 = x2,
                                    y2 = y2,
                                    durationMs = d
                                )
                            )
                        }
                    }

                    is Step.Input -> onConfirm(Step.Input(id = step.id, note = note, text = textStr))

                    is Step.Wait -> {
                        val d = durationStr.trim().toLongOrNull()
                        if (d == null) {
                            error = "请输入有效的时长"
                        } else {
                            onConfirm(Step.Wait(id = step.id, note = note, durationMs = d))
                        }
                    }

                    is Step.LaunchApp -> {
                        if (pkgStr.isBlank()) {
                            error = "请输入包名"
                        } else {
                            onConfirm(
                                Step.LaunchApp(
                                    id = step.id,
                                    note = note,
                                    packageName = pkgStr.trim()
                                )
                            )
                        }
                    }

                    is Step.WaitForElement -> {
                        val t = timeoutStr.trim().toLongOrNull()
                        if (t == null) {
                            error = "请输入有效的超时时间"
                        } else {
                            onConfirm(
                                Step.WaitForElement(
                                    id = step.id,
                                    note = note,
                                    text = textStr.ifBlank { null },
                                    viewId = viewIdStr.ifBlank { null },
                                    contentDesc = descStr.ifBlank { null },
                                    className = classNameStr.ifBlank { null },
                                    timeoutMs = t,
                                    onTimeout = onTimeout
                                )
                            )
                        }
                    }

                    is Step.Back -> onConfirm(Step.Back(id = step.id, note = note))

                    is Step.Home -> onConfirm(Step.Home(id = step.id, note = note))
                }
            }) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

private fun Float.toEditable(): String =
    if (this == toLong().toFloat()) toLong().toString() else toString()

private fun initialX(step: Step): String = when (step) {
    is Step.Tap -> step.x.toEditable()
    is Step.LongPress -> step.x.toEditable()
    else -> ""
}

private fun initialY(step: Step): String = when (step) {
    is Step.Tap -> step.y.toEditable()
    is Step.LongPress -> step.y.toEditable()
    else -> ""
}

private fun initialX1(step: Step): String = when (step) {
    is Step.Swipe -> step.x1.toEditable()
    else -> ""
}

private fun initialY1(step: Step): String = when (step) {
    is Step.Swipe -> step.y1.toEditable()
    else -> ""
}

private fun initialX2(step: Step): String = when (step) {
    is Step.Swipe -> step.x2.toEditable()
    else -> ""
}

private fun initialY2(step: Step): String = when (step) {
    is Step.Swipe -> step.y2.toEditable()
    else -> ""
}

private fun initialDuration(step: Step): String = when (step) {
    is Step.LongPress -> step.durationMs.toString()
    is Step.Swipe -> step.durationMs.toString()
    is Step.Wait -> step.durationMs.toString()
    else -> ""
}

private fun initialText(step: Step): String = when (step) {
    is Step.Input -> step.text
    is Step.WaitForElement -> step.text ?: ""
    else -> ""
}