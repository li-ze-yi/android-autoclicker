package com.autoclicker.ui.editor

import android.app.TimePickerDialog
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import com.autoclicker.core.overlay.OverlayService
import com.autoclicker.core.overlay.PickPointBridge
import com.autoclicker.core.script.CompareOp
import com.autoclicker.core.script.Condition
import com.autoclicker.core.script.OnTimeout
import com.autoclicker.core.script.Step
import com.autoclicker.core.script.describe
import com.autoclicker.core.script.typeLabel
import com.autoclicker.core.trigger.TriggerRecord
import com.autoclicker.core.trigger.TriggerScheduler
import com.autoclicker.core.util.PermissionChecker
import com.autoclicker.core.vision.CapturePermissionActivity
import com.autoclicker.core.vision.ColorMatcher
import com.autoclicker.core.vision.ImageTemplateRepository
import com.autoclicker.core.vision.RegionPickerBridge
import com.autoclicker.core.vision.ScreenCaptureService
import com.autoclicker.core.vision.VisionBridge
import com.autoclicker.core.vision.VisionSettings
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ROW_HEIGHT = 64.dp

private val NumberKeyboard = KeyboardOptions(keyboardType = KeyboardType.Number)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(scriptId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val viewModel: EditorViewModel = viewModel()
    LaunchedEffect(scriptId) { viewModel.load(scriptId) }

    val script by viewModel.script.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var showBatchDelayDialog by remember { mutableStateOf(false) }
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
                    TextButton(onClick = { showBatchDelayDialog = true }) {
                        Text("批量")
                    }
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
            var loopCountStr by remember(current.id) { mutableStateOf(current.loopCount.toString()) }
            var loopIntervalStr by remember(current.id) {
                mutableStateOf(current.loopIntervalMs.toString())
            }
            var jitterRadiusStr by remember(current.id) {
                mutableStateOf(current.jitterRadiusPx.toString())
            }
            var jitterDelayStr by remember(current.id) {
                mutableStateOf(current.jitterDelayPercent.toString())
            }
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

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("循环与拟人化", style = MaterialTheme.typography.titleMedium)

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("无限循环", modifier = Modifier.weight(1f))
                            Switch(
                                checked = current.loopInfinite,
                                onCheckedChange = { viewModel.updateLoopInfinite(it) }
                            )
                        }

                        NumberSettingField(
                            value = loopCountStr,
                            onValueChange = { loopCountStr = it },
                            label = "循环次数",
                            enabled = !current.loopInfinite,
                            onCommit = { text ->
                                val n = text.trim().toIntOrNull()
                                if (n == null) {
                                    loopCountStr = current.loopCount.toString()
                                    Toast.makeText(context, "循环次数需为整数", Toast.LENGTH_SHORT).show()
                                } else {
                                    val clamped = n.coerceAtLeast(1)
                                    viewModel.updateLoopCount(clamped)
                                    loopCountStr = clamped.toString()
                                }
                            }
                        )

                        NumberSettingField(
                            value = loopIntervalStr,
                            onValueChange = { loopIntervalStr = it },
                            label = "循环间隔(ms)",
                            onCommit = { text ->
                                val n = text.trim().toLongOrNull()
                                if (n == null) {
                                    loopIntervalStr = current.loopIntervalMs.toString()
                                    Toast.makeText(context, "循环间隔需为整数", Toast.LENGTH_SHORT).show()
                                } else {
                                    val clamped = n.coerceAtLeast(0L)
                                    viewModel.updateLoopIntervalMs(clamped)
                                    loopIntervalStr = clamped.toString()
                                }
                            }
                        )

                        HorizontalDivider()

                        NumberSettingField(
                            value = jitterRadiusStr,
                            onValueChange = { jitterRadiusStr = it },
                            label = "坐标随机偏移(px)",
                            onCommit = { text ->
                                val n = text.trim().toIntOrNull()
                                if (n == null) {
                                    jitterRadiusStr = current.jitterRadiusPx.toString()
                                    Toast.makeText(context, "坐标偏移需为整数", Toast.LENGTH_SHORT).show()
                                } else {
                                    val clamped = n.coerceAtLeast(0)
                                    viewModel.updateJitterRadiusPx(clamped)
                                    jitterRadiusStr = clamped.toString()
                                }
                            }
                        )

                        NumberSettingField(
                            value = jitterDelayStr,
                            onValueChange = { jitterDelayStr = it },
                            label = "延时随机浮动(%)",
                            onCommit = { text ->
                                val n = text.trim().toIntOrNull()
                                if (n == null) {
                                    jitterDelayStr = current.jitterDelayPercent.toString()
                                    Toast.makeText(context, "延时浮动需为整数", Toast.LENGTH_SHORT).show()
                                } else {
                                    val clamped = n.coerceIn(0, 50)
                                    viewModel.updateJitterDelayPercent(clamped)
                                    jitterDelayStr = clamped.toString()
                                }
                            }
                        )

                        Text(
                            "模拟人工点击，降低被识别为脚本的风险",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
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
        val defaultTemplateId = remember {
            ImageTemplateRepository.get(context).list().firstOrNull()?.id ?: ""
        }
        val entries = listOf<Pair<String, () -> Step>>(
            "点击" to { Step.Tap(x = centerX, y = centerY) },
            "长按" to { Step.LongPress(x = centerX, y = centerY) },
            "滑动" to { Step.Swipe(x1 = centerX, y1 = centerY, x2 = centerX, y2 = centerY - 300f) },
            "输入文本" to { Step.Input(text = "") },
            "等待" to { Step.Wait() },
            "启动应用" to { Step.LaunchApp(packageName = "") },
            "等待元素" to { Step.WaitForElement() },
            "返回键" to { Step.Back() },
            "主页键" to { Step.Home() },
            "连点" to { Step.Burst(x = centerX, y = centerY) },
            "智能定位点击" to { Step.TapElement(text = "文本") },
            "识图点击" to {
                Step.ImageTap(
                    templateId = defaultTemplateId,
                    thresholdPercent = VisionSettings.get(context).defaultThresholdPercent
                )
            },
            "识色点击" to { Step.ColorTap(color = -65536) },
            "标签" to { Step.Label(name = "label1") },
            "跳转" to { Step.Jump(label = "label1") },
            "条件判断" to { Step.IfElse() },
            "设置变量" to { Step.SetVar(name = "v1", value = "0") }
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

    if (showBatchDelayDialog) {
        var batchDelayText by remember { mutableStateOf("0") }
        AlertDialog(
            onDismissRequest = { showBatchDelayDialog = false },
            title = { Text("统一设置步骤延时") },
            text = {
                Column {
                    OutlinedTextField(
                        value = batchDelayText,
                        onValueChange = { batchDelayText = it },
                        label = { Text("每步执行前延时(ms)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = NumberKeyboard
                    )
                    Text("将覆盖所有步骤的延时值", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val v = batchDelayText.trim().toLongOrNull()
                    if (v == null) {
                        Toast.makeText(context, "请输入整数毫秒", Toast.LENGTH_SHORT).show()
                    } else {
                        viewModel.setAllStepDelay(v)
                        showBatchDelayDialog = false
                    }
                }) {
                    Text("应用")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDelayDialog = false }) { Text("取消") }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StepEditDialog(
    step: Step,
    onDismiss: () -> Unit,
    onConfirm: (Step) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val templateRepo = remember { ImageTemplateRepository.get(context) }
    val visionSettings = remember { VisionSettings.get(context) }
    var note by remember(step.id) { mutableStateOf(step.note) }
    var delayText by remember(step.id) { mutableStateOf(step.delayBeforeMs.toString()) }
    var xStr by remember(step.id) { mutableStateOf(initialX(step)) }
    var yStr by remember(step.id) { mutableStateOf(initialY(step)) }
    var x1Str by remember(step.id) { mutableStateOf(initialX1(step)) }
    var y1Str by remember(step.id) { mutableStateOf(initialY1(step)) }
    var x2Str by remember(step.id) { mutableStateOf(initialX2(step)) }
    var y2Str by remember(step.id) { mutableStateOf(initialY2(step)) }
    var durationStr by remember(step.id) { mutableStateOf(initialDuration(step)) }
    // P2：是否把带轨迹的滑动简化为直线。
    var clearSwipePath by remember(step.id) { mutableStateOf(false) }
    var textStr by remember(step.id) { mutableStateOf(initialText(step)) }
    var pkgStr by remember(step.id) {
        mutableStateOf((step as? Step.LaunchApp)?.packageName ?: "")
    }
    var viewIdStr by remember(step.id) {
        mutableStateOf((step as? Step.WaitForElement)?.viewId ?: (step as? Step.TapElement)?.viewId ?: "")
    }
    var descStr by remember(step.id) {
        mutableStateOf(
            (step as? Step.WaitForElement)?.contentDesc
                ?: (step as? Step.TapElement)?.contentDesc ?: ""
        )
    }
    var classNameStr by remember(step.id) {
        mutableStateOf(
            (step as? Step.WaitForElement)?.className
                ?: (step as? Step.TapElement)?.className ?: ""
        )
    }
    var timeoutStr by remember(step.id) {
        mutableStateOf(initialTimeoutMs(step).toString())
    }
    var onTimeout by remember(step.id) {
        mutableStateOf(initialOnTimeout(step))
    }
    var countStr by remember(step.id) {
        mutableStateOf(((step as? Step.Burst)?.count ?: 10).toString())
    }
    var intervalStr by remember(step.id) {
        mutableStateOf(((step as? Step.Burst)?.intervalMs ?: 100L).toString())
    }
    var touchDurStr by remember(step.id) {
        mutableStateOf(((step as? Step.Burst)?.touchDurationMs ?: 50L).toString())
    }
    var indexStr by remember(step.id) {
        mutableStateOf(((step as? Step.TapElement)?.index ?: 0).toString())
    }
    var thresholdStr by remember(step.id) {
        mutableStateOf(((step as? Step.ImageTap)?.thresholdPercent ?: 85).toString())
    }
    var useGlobalThreshold by remember(step.id) { mutableStateOf(false) }
    var toleranceStr by remember(step.id) {
        mutableStateOf(((step as? Step.ColorTap)?.tolerance ?: 20).toString())
    }
    var colorStr by remember(step.id) { mutableStateOf(initialColorText(step)) }
    var templateId by remember(step.id) {
        mutableStateOf((step as? Step.ImageTap)?.templateId ?: "")
    }
    var regionLeftStr by remember(step.id) {
        mutableStateOf(
            ((step as? Step.ImageTap)?.regionLeft ?: (step as? Step.ColorTap)?.regionLeft ?: 0).toString()
        )
    }
    var regionTopStr by remember(step.id) {
        mutableStateOf(
            ((step as? Step.ImageTap)?.regionTop ?: (step as? Step.ColorTap)?.regionTop ?: 0).toString()
        )
    }
    var regionWidthStr by remember(step.id) {
        mutableStateOf(
            ((step as? Step.ImageTap)?.regionWidth ?: (step as? Step.ColorTap)?.regionWidth ?: 0).toString()
        )
    }
    var regionHeightStr by remember(step.id) {
        mutableStateOf(
            ((step as? Step.ImageTap)?.regionHeight ?: (step as? Step.ColorTap)?.regionHeight ?: 0).toString()
        )
    }
    var offsetXStr by remember(step.id) {
        mutableStateOf(
            ((step as? Step.ImageTap)?.offsetX ?: (step as? Step.ColorTap)?.offsetX ?: 0).toString()
        )
    }
    var offsetYStr by remember(step.id) {
        mutableStateOf(
            ((step as? Step.ImageTap)?.offsetY ?: (step as? Step.ColorTap)?.offsetY ?: 0).toString()
        )
    }
    var error by remember(step.id) { mutableStateOf<String?>(null) }

    // P3：控制流与变量字段
    var varName by remember(step.id) { mutableStateOf((step as? Step.SetVar)?.name ?: "") }
    var varValue by remember(step.id) { mutableStateOf((step as? Step.SetVar)?.value ?: "") }
    var labelName by remember(step.id) { mutableStateOf((step as? Step.Label)?.name ?: "") }
    var jumpLabel by remember(step.id) { mutableStateOf((step as? Step.Jump)?.label ?: "") }
    var jumpMaxTimes by remember(step.id) {
        mutableStateOf(((step as? Step.Jump)?.maxTimes ?: -1).toString())
    }
    var thenLabelStr by remember(step.id) { mutableStateOf((step as? Step.IfElse)?.thenLabel ?: "") }
    var elseLabelStr by remember(step.id) { mutableStateOf((step as? Step.IfElse)?.elseLabel ?: "") }
    var condKind by remember(step.id) { mutableIntStateOf(initialCondKind(step)) }
    var condTextStr by remember(step.id) { mutableStateOf(condElement(step)?.text ?: "") }
    var condViewIdStr by remember(step.id) { mutableStateOf(condElement(step)?.viewId ?: "") }
    var condDescStr by remember(step.id) { mutableStateOf(condElement(step)?.contentDesc ?: "") }
    var condClassStr by remember(step.id) { mutableStateOf(condElement(step)?.className ?: "") }
    var condIndexStr by remember(step.id) { mutableStateOf((condElement(step)?.index ?: 0).toString()) }
    var condTimeoutStr by remember(step.id) {
        mutableStateOf((condElement(step)?.timeoutMs ?: 1000L).toString())
    }
    var condColorStr by remember(step.id) {
        mutableStateOf(
            condColor(step)?.let { "#%06X".format(Locale.US, it.color and 0xFFFFFF) } ?: "#FF0000"
        )
    }
    var condToleranceStr by remember(step.id) {
        mutableStateOf((condColor(step)?.tolerance ?: 20).toString())
    }
    var condVarNameStr by remember(step.id) { mutableStateOf(condVar(step)?.name ?: "") }
    var condVarOp by remember(step.id) { mutableStateOf(condVar(step)?.op ?: CompareOp.EQ) }
    var condVarValueStr by remember(step.id) { mutableStateOf(condVar(step)?.value ?: "") }

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

    // 悬浮窗框选搜索区域的结果回填：对话框打开期间只消费一次。
    LaunchedEffect(Unit) {
        RegionPickerBridge.result.collect { rect ->
            if (rect != null) {
                regionLeftStr = rect.left.toString()
                regionTopStr = rect.top.toString()
                regionWidthStr = rect.width().toString()
                regionHeightStr = rect.height().toString()
                RegionPickerBridge.consume()
                Toast.makeText(context, "已填入搜索区域", Toast.LENGTH_SHORT).show()
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
                        if ((step as? Step.Swipe)?.path != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = clearSwipePath,
                                    onCheckedChange = { clearSwipePath = it }
                                )
                                Text("清除轨迹（简化为直线滑动）")
                            }
                        }
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

                    is Step.Burst -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = xStr,
                                onValueChange = { xStr = it },
                                label = { Text("X") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                keyboardOptions = NumberKeyboard
                            )
                            OutlinedTextField(
                                value = yStr,
                                onValueChange = { yStr = it },
                                label = { Text("Y") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                keyboardOptions = NumberKeyboard
                            )
                        }
                        OutlinedTextField(
                            value = countStr,
                            onValueChange = { countStr = it },
                            label = { Text("次数") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = NumberKeyboard
                        )
                        OutlinedTextField(
                            value = intervalStr,
                            onValueChange = { intervalStr = it },
                            label = { Text("间隔(ms)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = NumberKeyboard
                        )
                        OutlinedTextField(
                            value = touchDurStr,
                            onValueChange = { touchDurStr = it },
                            label = { Text("触摸时长(ms)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = NumberKeyboard
                        )
                    }

                    is Step.TapElement -> {
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
                            value = indexStr,
                            onValueChange = { indexStr = it },
                            label = { Text("序号") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = NumberKeyboard
                        )
                        OutlinedTextField(
                            value = timeoutStr,
                            onValueChange = { timeoutStr = it },
                            label = { Text("超时(ms)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = NumberKeyboard
                        )
                        OnTimeoutSelector(onTimeout) { onTimeout = it }
                    }

                    is Step.ImageTap -> {
                        val templates = remember(step.id) { templateRepo.list() }
                        Text("识图模板", style = MaterialTheme.typography.bodyMedium)
                        if (templates.isEmpty()) {
                            Text(
                                "暂无模板，请先到「识图模板」页创建",
                                style = MaterialTheme.typography.bodySmall
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                templates.forEach { template ->
                                    FilterChip(
                                        selected = templateId == template.id,
                                        onClick = { templateId = template.id },
                                        label = {
                                            Text("${template.name} ${template.width}x${template.height}")
                                        }
                                    )
                                }
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                if (!ScreenCaptureService.isReady) {
                                    CapturePermissionActivity.request(context)
                                    Toast.makeText(context, "请先授权截屏，再点击「立即截屏」", Toast.LENGTH_SHORT).show()
                                } else {
                                    scope.launch {
                                        val screen = withContext(Dispatchers.IO) {
                                            ScreenCaptureService.capture(0)
                                        }
                                        if (screen != null) {
                                            // 交给识图页框选保存，切勿在此回收：位图已转交他人使用。
                                            VisionBridge.publishCapture(screen)
                                            Toast.makeText(
                                                context,
                                                "截屏成功，请在「识图模板」页框选保存",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        } else {
                                            Toast.makeText(
                                                context,
                                                "截屏失败，请重新授权截屏",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("立即截屏")
                        }
                        OutlinedTextField(
                            value = thresholdStr,
                            onValueChange = { thresholdStr = it },
                            label = { Text("阈值%") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = !useGlobalThreshold,
                            keyboardOptions = NumberKeyboard
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = useGlobalThreshold,
                                onCheckedChange = { useGlobalThreshold = it }
                            )
                            Text("使用全局默认(${visionSettings.defaultThresholdPercent}%)")
                        }
                        Text(
                            "搜索区域（宽或高为 0 表示全屏搜索）",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = regionLeftStr,
                                onValueChange = { regionLeftStr = it },
                                label = { Text("区域左") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                keyboardOptions = NumberKeyboard
                            )
                            OutlinedTextField(
                                value = regionTopStr,
                                onValueChange = { regionTopStr = it },
                                label = { Text("区域上") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                keyboardOptions = NumberKeyboard
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = regionWidthStr,
                                onValueChange = { regionWidthStr = it },
                                label = { Text("区域宽(0=全屏)") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                keyboardOptions = NumberKeyboard
                            )
                            OutlinedTextField(
                                value = regionHeightStr,
                                onValueChange = { regionHeightStr = it },
                                label = { Text("区域高(0=全屏)") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                keyboardOptions = NumberKeyboard
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    if (!ScreenCaptureService.isReady) {
                                        CapturePermissionActivity.request(context)
                                        Toast.makeText(
                                            context,
                                            "请先授权截屏，再重新框选",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        RegionPickerBridge.request(step.id)
                                        OverlayService.startRegionPick(context)
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("框选搜索区域")
                            }
                            OutlinedButton(
                                onClick = {
                                    regionLeftStr = "0"
                                    regionTopStr = "0"
                                    regionWidthStr = "0"
                                    regionHeightStr = "0"
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("重置为全屏")
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = offsetXStr,
                                onValueChange = { offsetXStr = it },
                                label = { Text("偏移X") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                keyboardOptions = NumberKeyboard
                            )
                            OutlinedTextField(
                                value = offsetYStr,
                                onValueChange = { offsetYStr = it },
                                label = { Text("偏移Y") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                keyboardOptions = NumberKeyboard
                            )
                        }
                        OutlinedTextField(
                            value = timeoutStr,
                            onValueChange = { timeoutStr = it },
                            label = { Text("超时(ms)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = NumberKeyboard
                        )
                        OnTimeoutSelector(onTimeout) { onTimeout = it }
                    }

                    is Step.ColorTap -> {
                        OutlinedTextField(
                            value = colorStr,
                            onValueChange = { colorStr = it },
                            label = { Text("颜色(#RRGGBB / 0x... / 十进制)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedButton(
                            onClick = {
                                if (!ScreenCaptureService.isReady) {
                                    CapturePermissionActivity.request(context)
                                    Toast.makeText(context, "请先授权截屏，再点击「拾取屏幕颜色」", Toast.LENGTH_SHORT).show()
                                } else {
                                    scope.launch {
                                        val screen = withContext(Dispatchers.IO) {
                                            ScreenCaptureService.capture(0)
                                        }
                                        if (screen == null) {
                                            Toast.makeText(context, "截屏失败，请重新授权截屏", Toast.LENGTH_SHORT).show()
                                        } else {
                                            val picked = ColorMatcher.colorAt(
                                                screen,
                                                screen.width / 2,
                                                screen.height / 2
                                            )
                                            screen.recycle()
                                            if (picked != null) {
                                                colorStr = "#%06X".format(Locale.US, picked and 0xFFFFFF)
                                                Toast.makeText(
                                                    context,
                                                    "已取屏幕中心颜色，可手动微调",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            } else {
                                                Toast.makeText(context, "取色失败", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("拾取屏幕颜色")
                        }
                        OutlinedTextField(
                            value = toleranceStr,
                            onValueChange = { toleranceStr = it },
                            label = { Text("容差") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = NumberKeyboard
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = regionLeftStr,
                                onValueChange = { regionLeftStr = it },
                                label = { Text("区域左") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                keyboardOptions = NumberKeyboard
                            )
                            OutlinedTextField(
                                value = regionTopStr,
                                onValueChange = { regionTopStr = it },
                                label = { Text("区域上") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                keyboardOptions = NumberKeyboard
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = regionWidthStr,
                                onValueChange = { regionWidthStr = it },
                                label = { Text("区域宽(0=全屏)") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                keyboardOptions = NumberKeyboard
                            )
                            OutlinedTextField(
                                value = regionHeightStr,
                                onValueChange = { regionHeightStr = it },
                                label = { Text("区域高(0=全屏)") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                keyboardOptions = NumberKeyboard
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = offsetXStr,
                                onValueChange = { offsetXStr = it },
                                label = { Text("偏移X") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                keyboardOptions = NumberKeyboard
                            )
                            OutlinedTextField(
                                value = offsetYStr,
                                onValueChange = { offsetYStr = it },
                                label = { Text("偏移Y") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                keyboardOptions = NumberKeyboard
                            )
                        }
                        OutlinedTextField(
                            value = timeoutStr,
                            onValueChange = { timeoutStr = it },
                            label = { Text("超时(ms)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = NumberKeyboard
                        )
                        OnTimeoutSelector(onTimeout) { onTimeout = it }
                    }

                    is Step.MultiGesture -> {
                        val strokes = (step as? Step.MultiGesture)?.strokes ?: emptyList()
                        Text(
                            "多指手势：${strokes.size} 指 / ${strokes.sumOf { it.size }} 点" +
                                "（由精确录制生成，轨迹不可在此编辑）",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    is Step.SetVar -> {
                        OutlinedTextField(
                            value = varName,
                            onValueChange = { varName = it },
                            label = { Text("变量名") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = varValue,
                            onValueChange = { varValue = it },
                            label = { Text("值（支持 ${'$'}{其它变量}）") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    is Step.Label -> {
                        OutlinedTextField(
                            value = labelName,
                            onValueChange = { labelName = it },
                            label = { Text("标签名") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Text("配合「跳转」/「条件判断」使用", style = MaterialTheme.typography.bodySmall)
                    }

                    is Step.Jump -> {
                        OutlinedTextField(
                            value = jumpLabel,
                            onValueChange = { jumpLabel = it },
                            label = { Text("目标标签名") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = jumpMaxTimes,
                            onValueChange = { jumpMaxTimes = it },
                            label = { Text("最大跳转次数（-1=不限）") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = NumberKeyboard
                        )
                    }

                    is Step.IfElse -> {
                        Text("条件类型", style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = condKind == 0,
                                onClick = { condKind = 0 },
                                label = { Text("元素存在") }
                            )
                            FilterChip(
                                selected = condKind == 1,
                                onClick = { condKind = 1 },
                                label = { Text("识色") }
                            )
                            FilterChip(
                                selected = condKind == 2,
                                onClick = { condKind = 2 },
                                label = { Text("变量比较") }
                            )
                        }
                        when (condKind) {
                            0 -> {
                                OutlinedTextField(
                                    value = condTextStr, onValueChange = { condTextStr = it },
                                    label = { Text("文本（可空）") },
                                    modifier = Modifier.fillMaxWidth(), singleLine = true
                                )
                                OutlinedTextField(
                                    value = condViewIdStr, onValueChange = { condViewIdStr = it },
                                    label = { Text("资源ID（可空）") },
                                    modifier = Modifier.fillMaxWidth(), singleLine = true
                                )
                                OutlinedTextField(
                                    value = condDescStr, onValueChange = { condDescStr = it },
                                    label = { Text("描述（可空）") },
                                    modifier = Modifier.fillMaxWidth(), singleLine = true
                                )
                                OutlinedTextField(
                                    value = condClassStr, onValueChange = { condClassStr = it },
                                    label = { Text("类名（可空）") },
                                    modifier = Modifier.fillMaxWidth(), singleLine = true
                                )
                                OutlinedTextField(
                                    value = condIndexStr, onValueChange = { condIndexStr = it },
                                    label = { Text("序号") },
                                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                                    keyboardOptions = NumberKeyboard
                                )
                                OutlinedTextField(
                                    value = condTimeoutStr, onValueChange = { condTimeoutStr = it },
                                    label = { Text("等待超时(ms)") },
                                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                                    keyboardOptions = NumberKeyboard
                                )
                            }

                            1 -> {
                                OutlinedTextField(
                                    value = condColorStr, onValueChange = { condColorStr = it },
                                    label = { Text("颜色(#RRGGBB)") },
                                    modifier = Modifier.fillMaxWidth(), singleLine = true
                                )
                                OutlinedTextField(
                                    value = condToleranceStr, onValueChange = { condToleranceStr = it },
                                    label = { Text("容差") },
                                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                                    keyboardOptions = NumberKeyboard
                                )
                            }

                            else -> {
                                OutlinedTextField(
                                    value = condVarNameStr, onValueChange = { condVarNameStr = it },
                                    label = { Text("变量名") },
                                    modifier = Modifier.fillMaxWidth(), singleLine = true
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CompareOp.values().forEach { op ->
                                        FilterChip(
                                            selected = condVarOp == op,
                                            onClick = { condVarOp = op },
                                            label = { Text(op.name) }
                                        )
                                    }
                                }
                                OutlinedTextField(
                                    value = condVarValueStr,
                                    onValueChange = { condVarValueStr = it },
                                    label = { Text("比较值") },
                                    modifier = Modifier.fillMaxWidth(), singleLine = true
                                )
                            }
                        }
                        OutlinedTextField(
                            value = thenLabelStr, onValueChange = { thenLabelStr = it },
                            label = { Text("成立时跳转标签（可空）") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true
                        )
                        OutlinedTextField(
                            value = elseLabelStr, onValueChange = { elseLabelStr = it },
                            label = { Text("不成立时跳转标签（可空）") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true
                        )
                        Text("留空表示不跳转，继续执行下一步", style = MaterialTheme.typography.bodySmall)
                    }
                }

                OutlinedTextField(
                    value = delayText,
                    onValueChange = { delayText = it },
                    label = { Text("延时(ms)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = NumberKeyboard
                )
                Text(
                    "执行本步前先等待",
                    style = MaterialTheme.typography.bodySmall
                )

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
                val parsedDelay = delayText.trim().toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                when (step) {
                    is Step.Tap -> {
                        val x = xStr.trim().toFloatOrNull()
                        val y = yStr.trim().toFloatOrNull()
                        if (x == null || y == null) {
                            error = "请输入有效的 X、Y 坐标"
                        } else {
                            onConfirm(
                                Step.Tap(
                                    id = step.id,
                                    note = note,
                                    delayBeforeMs = parsedDelay,
                                    x = x,
                                    y = y
                                )
                            )
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
                                Step.LongPress(
                                    id = step.id,
                                    note = note,
                                    delayBeforeMs = parsedDelay,
                                    x = x,
                                    y = y,
                                    durationMs = d
                                )
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
                                    delayBeforeMs = parsedDelay,
                                    x1 = x1,
                                    y1 = y1,
                                    x2 = x2,
                                    y2 = y2,
                                    durationMs = d,
                                    path = if (clearSwipePath) null else (step as? Step.Swipe)?.path
                                )
                            )
                        }
                    }

                    is Step.Input -> onConfirm(
                        Step.Input(id = step.id, note = note, delayBeforeMs = parsedDelay, text = textStr)
                    )

                    is Step.Wait -> {
                        val d = durationStr.trim().toLongOrNull()
                        if (d == null) {
                            error = "请输入有效的时长"
                        } else {
                            onConfirm(
                                Step.Wait(
                                    id = step.id,
                                    note = note,
                                    delayBeforeMs = parsedDelay,
                                    durationMs = d
                                )
                            )
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
                                    delayBeforeMs = parsedDelay,
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
                                    delayBeforeMs = parsedDelay,
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

                    is Step.Back -> onConfirm(
                        Step.Back(id = step.id, note = note, delayBeforeMs = parsedDelay)
                    )

                    is Step.Home -> onConfirm(
                        Step.Home(id = step.id, note = note, delayBeforeMs = parsedDelay)
                    )

                    is Step.Burst -> {
                        val x = xStr.trim().toFloatOrNull()
                        val y = yStr.trim().toFloatOrNull()
                        val c = countStr.trim().toIntOrNull()
                        val iv = intervalStr.trim().toLongOrNull()
                        val td = touchDurStr.trim().toLongOrNull()
                        if (x == null || y == null || c == null || iv == null || td == null) {
                            error = "请检查坐标与次数等数值"
                        } else {
                            onConfirm(
                                Step.Burst(
                                    id = step.id,
                                    note = note,
                                    delayBeforeMs = parsedDelay,
                                    x = x,
                                    y = y,
                                    count = c,
                                    intervalMs = iv,
                                    touchDurationMs = td
                                )
                            )
                        }
                    }

                    is Step.TapElement -> {
                        val idx = indexStr.trim().toIntOrNull()
                        val t = timeoutStr.trim().toLongOrNull()
                        if (idx == null || t == null) {
                            error = "请检查序号与超时时间"
                        } else {
                            onConfirm(
                                Step.TapElement(
                                    id = step.id,
                                    note = note,
                                    delayBeforeMs = parsedDelay,
                                    text = textStr.ifBlank { null },
                                    viewId = viewIdStr.ifBlank { null },
                                    contentDesc = descStr.ifBlank { null },
                                    className = classNameStr.ifBlank { null },
                                    index = idx,
                                    timeoutMs = t,
                                    onTimeout = onTimeout
                                )
                            )
                        }
                    }

                    is Step.ImageTap -> {
                        val th = if (useGlobalThreshold) {
                            visionSettings.defaultThresholdPercent
                        } else {
                            thresholdStr.trim().toIntOrNull()
                        }
                        val rl = regionLeftStr.trim().toIntOrNull()
                        val rt = regionTopStr.trim().toIntOrNull()
                        val rw = regionWidthStr.trim().toIntOrNull()
                        val rh = regionHeightStr.trim().toIntOrNull()
                        val ox = offsetXStr.trim().toIntOrNull()
                        val oy = offsetYStr.trim().toIntOrNull()
                        val t = timeoutStr.trim().toLongOrNull()
                        if (templateId.isBlank()) {
                            error = "请选择识图模板"
                        } else if (th == null || rl == null || rt == null || rw == null ||
                            rh == null || ox == null || oy == null || t == null
                        ) {
                            error = "请检查阈值、区域与超时等数值"
                        } else {
                            onConfirm(
                                Step.ImageTap(
                                    id = step.id,
                                    note = note,
                                    delayBeforeMs = parsedDelay,
                                    templateId = templateId,
                                    thresholdPercent = th,
                                    regionLeft = rl,
                                    regionTop = rt,
                                    regionWidth = rw,
                                    regionHeight = rh,
                                    offsetX = ox,
                                    offsetY = oy,
                                    timeoutMs = t,
                                    onTimeout = onTimeout
                                )
                            )
                        }
                    }

                    is Step.ColorTap -> {
                        val color = parseColorOrNull(colorStr)
                        val tol = toleranceStr.trim().toIntOrNull()
                        val rl = regionLeftStr.trim().toIntOrNull()
                        val rt = regionTopStr.trim().toIntOrNull()
                        val rw = regionWidthStr.trim().toIntOrNull()
                        val rh = regionHeightStr.trim().toIntOrNull()
                        val ox = offsetXStr.trim().toIntOrNull()
                        val oy = offsetYStr.trim().toIntOrNull()
                        val t = timeoutStr.trim().toLongOrNull()
                        if (color == null) {
                            error = "颜色格式无效，请使用 #RRGGBB / 0x... / 十进制"
                        } else if (tol == null || rl == null || rt == null || rw == null ||
                            rh == null || ox == null || oy == null || t == null
                        ) {
                            error = "请检查容差、区域与超时等数值"
                        } else {
                            onConfirm(
                                Step.ColorTap(
                                    id = step.id,
                                    note = note,
                                    delayBeforeMs = parsedDelay,
                                    color = color,
                                    tolerance = tol,
                                    regionLeft = rl,
                                    regionTop = rt,
                                    regionWidth = rw,
                                    regionHeight = rh,
                                    offsetX = ox,
                                    offsetY = oy,
                                    timeoutMs = t,
                                    onTimeout = onTimeout
                                )
                            )
                        }
                    }

                    is Step.MultiGesture -> onConfirm(
                        Step.MultiGesture(
                            id = step.id,
                            note = note,
                            delayBeforeMs = parsedDelay,
                            strokes = (step as? Step.MultiGesture)?.strokes ?: emptyList()
                        )
                    )

                    is Step.SetVar -> {
                        if (varName.isBlank()) {
                            error = "请输入变量名"
                        } else {
                            onConfirm(
                                Step.SetVar(
                                    id = step.id,
                                    note = note,
                                    delayBeforeMs = parsedDelay,
                                    name = varName.trim(),
                                    value = varValue
                                )
                            )
                        }
                    }

                    is Step.Label -> {
                        if (labelName.isBlank()) {
                            error = "请输入标签名"
                        } else {
                            onConfirm(
                                Step.Label(
                                    id = step.id,
                                    note = note,
                                    delayBeforeMs = parsedDelay,
                                    name = labelName.trim()
                                )
                            )
                        }
                    }

                    is Step.Jump -> {
                        val maxTimes = jumpMaxTimes.trim().toIntOrNull()
                        if (jumpLabel.isBlank()) {
                            error = "请输入目标标签名"
                        } else if (maxTimes == null) {
                            error = "最大跳转次数需为整数"
                        } else {
                            onConfirm(
                                Step.Jump(
                                    id = step.id,
                                    note = note,
                                    delayBeforeMs = parsedDelay,
                                    label = jumpLabel.trim(),
                                    maxTimes = maxTimes
                                )
                            )
                        }
                    }

                    is Step.IfElse -> {
                        val condition: Condition? = when (condKind) {
                            0 -> {
                                val idx = condIndexStr.trim().toIntOrNull()
                                val t = condTimeoutStr.trim().toLongOrNull()
                                if (idx == null || t == null) {
                                    null
                                } else {
                                    Condition.ElementExists(
                                        text = condTextStr.ifBlank { null },
                                        viewId = condViewIdStr.ifBlank { null },
                                        contentDesc = condDescStr.ifBlank { null },
                                        className = condClassStr.ifBlank { null },
                                        index = idx,
                                        timeoutMs = t
                                    )
                                }
                            }

                            1 -> {
                                val color = parseColorOrNull(condColorStr)
                                val tol = condToleranceStr.trim().toIntOrNull()
                                if (color == null || tol == null) {
                                    null
                                } else {
                                    Condition.ColorFound(color = color, tolerance = tol)
                                }
                            }

                            else -> {
                                if (condVarNameStr.isBlank()) {
                                    null
                                } else {
                                    Condition.VarCompare(
                                        name = condVarNameStr.trim(),
                                        op = condVarOp,
                                        value = condVarValueStr
                                    )
                                }
                            }
                        }
                        if (condition == null) {
                            error = "请检查条件参数（序号/超时/颜色/变量名）"
                        } else {
                            onConfirm(
                                Step.IfElse(
                                    id = step.id,
                                    note = note,
                                    delayBeforeMs = parsedDelay,
                                    condition = condition,
                                    thenLabel = thenLabelStr.trim().ifBlank { null },
                                    elseLabel = elseLabelStr.trim().ifBlank { null }
                                )
                            )
                        }
                    }
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
    is Step.Burst -> step.x.toEditable()
    else -> ""
}

private fun initialY(step: Step): String = when (step) {
    is Step.Tap -> step.y.toEditable()
    is Step.LongPress -> step.y.toEditable()
    is Step.Burst -> step.y.toEditable()
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
    is Step.TapElement -> step.text ?: ""
    else -> ""
}

private fun initialTimeoutMs(step: Step): Long = when (step) {
    is Step.WaitForElement -> step.timeoutMs
    is Step.TapElement -> step.timeoutMs
    is Step.ImageTap -> step.timeoutMs
    is Step.ColorTap -> step.timeoutMs
    else -> 10000L
}

private fun initialOnTimeout(step: Step): OnTimeout = when (step) {
    is Step.WaitForElement -> step.onTimeout
    is Step.TapElement -> step.onTimeout
    is Step.ImageTap -> step.onTimeout
    is Step.ColorTap -> step.onTimeout
    else -> OnTimeout.STOP
}

private fun initialColorText(step: Step): String {
    val color = (step as? Step.ColorTap)?.color ?: return "#FF0000"
    return "#%06X".format(Locale.US, color and 0xFFFFFF)
}

/** 条件类型下标：0=元素存在 1=识色 2=变量比较。 */
private fun initialCondKind(step: Step): Int = when ((step as? Step.IfElse)?.condition) {
    is Condition.ColorFound -> 1
    is Condition.VarCompare -> 2
    else -> 0
}

private fun condElement(step: Step): Condition.ElementExists? =
    (step as? Step.IfElse)?.condition as? Condition.ElementExists

private fun condColor(step: Step): Condition.ColorFound? =
    (step as? Step.IfElse)?.condition as? Condition.ColorFound

private fun condVar(step: Step): Condition.VarCompare? =
    (step as? Step.IfElse)?.condition as? Condition.VarCompare

/**
 * 解析颜色文本，支持 `#RRGGBB` / `#AARRGGBB` / `0x...` / 十进制三种写法。
 * 解析失败返回 null（不抛异常）。
 */
private fun parseColorOrNull(text: String): Int? {
    val s = text.trim()
    if (s.isEmpty()) return null
    return try {
        when {
            s.startsWith("#") -> parseHexColor(s.substring(1))
            s.startsWith("0x", ignoreCase = true) -> parseHexColor(s.substring(2))
            else -> s.toLongOrNull()?.let { normalizeColor(it) }
        }
    } catch (e: Exception) {
        null
    }
}

private fun parseHexColor(hex: String): Int? {
    if (hex.isEmpty() || hex.length > 8) return null
    val value = hex.toLongOrNull(16) ?: return null
    return if (hex.length <= 6) {
        (0xFF000000L or (value and 0xFFFFFFL)).toInt()
    } else {
        value.toInt()
    }
}

private fun normalizeColor(raw: Long): Int =
    if (raw in 0..0xFFFFFFL) (0xFF000000L or raw).toInt() else raw.toInt()

@Composable
private fun NumberSettingField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean = true,
    onCommit: (String) -> Unit
) {
    var hadFocus by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { state ->
                if (hadFocus && !state.isFocused) onCommit(value)
                hadFocus = state.isFocused
            },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = NumberKeyboard,
        keyboardActions = KeyboardActions(onDone = { onCommit(value) })
    )
}

@Composable
private fun OnTimeoutSelector(onTimeout: OnTimeout, onChange: (OnTimeout) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(
            selected = onTimeout == OnTimeout.STOP,
            onClick = { onChange(OnTimeout.STOP) }
        )
        Text("超时停止")
        RadioButton(
            selected = onTimeout == OnTimeout.SKIP,
            onClick = { onChange(OnTimeout.SKIP) }
        )
        Text("超时跳过")
    }
}