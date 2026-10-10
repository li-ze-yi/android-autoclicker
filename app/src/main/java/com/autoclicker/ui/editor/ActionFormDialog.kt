package com.autoclicker.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.autoclicker.core.permission.PermissionChecker
import com.autoclicker.domain.model.Action
import com.autoclicker.domain.model.AreaRandomClickAction
import com.autoclicker.domain.model.BoolCombine
import com.autoclicker.domain.model.CallFunctionAction
import com.autoclicker.domain.model.ClickAction
import com.autoclicker.domain.model.ClickColorAction
import com.autoclicker.domain.model.ClickImageAction
import com.autoclicker.domain.model.ClickNodeAction
import com.autoclicker.domain.model.ClickTextAction
import com.autoclicker.domain.model.CloseAppAction
import com.autoclicker.domain.model.CompareOp
import com.autoclicker.domain.model.ConditionAction
import com.autoclicker.domain.model.ConditionClause
import com.autoclicker.domain.model.ConditionType
import com.autoclicker.domain.model.DelayAction
import com.autoclicker.domain.model.EmptyAction
import com.autoclicker.domain.model.ExtractContentAction
import com.autoclicker.domain.model.ExtractSource
import com.autoclicker.domain.model.FunctionPackage
import com.autoclicker.domain.model.GestureAction
import com.autoclicker.domain.model.GestureStroke
import com.autoclicker.domain.model.GlobalKeyAction
import com.autoclicker.domain.model.GlobalKeyName
import com.autoclicker.domain.model.ImageTemplate
import com.autoclicker.domain.model.InputTextAction
import com.autoclicker.domain.model.JumpAction
import com.autoclicker.domain.model.JumpMode
import com.autoclicker.domain.model.LiteralValue
import com.autoclicker.domain.model.LongPressAction
import com.autoclicker.domain.model.NodeSelector
import com.autoclicker.domain.model.OpenAppAction
import com.autoclicker.domain.model.PercentPoint
import com.autoclicker.domain.model.PercentRect
import com.autoclicker.domain.model.PickMode
import com.autoclicker.domain.model.NumberGenMode
import com.autoclicker.domain.model.PopupAction
import com.autoclicker.domain.model.RepeatClickAction
import com.autoclicker.domain.model.SpeakAction
import com.autoclicker.domain.model.StepNode
import com.autoclicker.domain.model.SwipeAction
import com.autoclicker.domain.model.TextGroup
import com.autoclicker.domain.model.TextMode
import com.autoclicker.domain.model.TextSource
import com.autoclicker.domain.model.ToastAction
import com.autoclicker.domain.model.VarOp
import com.autoclicker.domain.model.VariableOpAction
import com.autoclicker.service.capture.TemplateCaptureOverlay

/**
 * 动作参数表单对话框：以 [initial] 为模板编辑，确定时回调最终动作。
 */
@Composable
fun ActionFormDialog(
    initial: Action,
    templates: List<ImageTemplate>,
    packages: List<FunctionPackage>,
    textGroups: List<TextGroup>,
    steps: List<StepNode>,
    onConfirm: (Action) -> Unit,
    onDismiss: () -> Unit,
) {
    var built by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(actionTitle(initial)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                when (val a = initial) {
                    is ClickAction -> ClickForm(a) { built = it }
                    is LongPressAction -> LongPressForm(a) { built = it }
                    is RepeatClickAction -> RepeatClickForm(a) { built = it }
                    is AreaRandomClickAction -> AreaRandomForm(a) { built = it }
                    is ClickImageAction -> ClickImageForm(a, templates) { built = it }
                    is ClickColorAction -> ClickColorForm(a) { built = it }
                    is ClickTextAction -> ClickTextForm(a) { built = it }
                    is ClickNodeAction -> ClickNodeForm(a) { built = it }
                    is GestureAction -> GestureForm(a) { built = it }
                    is SwipeAction -> SwipeForm(a) { built = it }
                    is GlobalKeyAction -> GlobalKeyForm(a) { built = it }
                    is OpenAppAction -> OpenAppForm(a) { built = it }
                    is CloseAppAction -> CloseAppForm(a) { built = it }
                    is InputTextAction -> InputTextForm(a, textGroups) { built = it }
                    is ExtractContentAction -> ExtractContentForm(a, templates) { built = it }
                    is VariableOpAction -> VariableOpForm(a) { built = it }
                    is ConditionAction -> ConditionForm(a, templates) { built = it }
                    is JumpAction -> JumpForm(a, steps) { built = it }
                    is CallFunctionAction -> CallFunctionForm(a, packages) { built = it }
                    is DelayAction -> DelayForm(a) { built = it }
                    is EmptyAction -> EmptyForm(a) { built = it }
                    is ToastAction -> ToastForm(a) { built = it }
                    is PopupAction -> PopupForm(a) { built = it }
                    is SpeakAction -> SpeakForm(a) { built = it }
                    else -> Text("暂不支持编辑该动作")
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(built) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

// ---------------- 点击类 ----------------

@Composable
private fun ClickForm(a: ClickAction, onChange: (Action) -> Unit) {
    var x by remember { mutableStateOf(formatPercent(a.point.x)) }
    var y by remember { mutableStateOf(formatPercent(a.point.y)) }
    var dur by remember { mutableStateOf(a.durationMs.toString()) }
    val emit: () -> Unit = {
        onChange(
            ClickAction(
                point = PercentPoint(parsePercent(x, a.point.x), parsePercent(y, a.point.y)),
                durationMs = parseLong(dur, a.durationMs),
            ),
        )
    }
    PercentPointFields(x, y, { x = it; emit() }, { y = it; emit() })
    NumberFieldRow("按压时长（ms）", dur) { dur = it; emit() }
}

@Composable
private fun LongPressForm(a: LongPressAction, onChange: (Action) -> Unit) {
    var x by remember { mutableStateOf(formatPercent(a.point.x)) }
    var y by remember { mutableStateOf(formatPercent(a.point.y)) }
    var dur by remember { mutableStateOf(a.durationMs.toString()) }
    val emit: () -> Unit = {
        onChange(
            LongPressAction(
                point = PercentPoint(parsePercent(x, a.point.x), parsePercent(y, a.point.y)),
                durationMs = parseLong(dur, a.durationMs),
            ),
        )
    }
    PercentPointFields(x, y, { x = it; emit() }, { y = it; emit() })
    NumberFieldRow("长按时长（ms）", dur) { dur = it; emit() }
}

@Composable
private fun RepeatClickForm(a: RepeatClickAction, onChange: (Action) -> Unit) {
    var x by remember { mutableStateOf(formatPercent(a.point.x)) }
    var y by remember { mutableStateOf(formatPercent(a.point.y)) }
    var interval by remember { mutableStateOf(a.intervalMs.toString()) }
    var count by remember { mutableStateOf(a.count.toString()) }
    var dur by remember { mutableStateOf(a.durationMs.toString()) }
    val emit: () -> Unit = {
        onChange(
            RepeatClickAction(
                point = PercentPoint(parsePercent(x, a.point.x), parsePercent(y, a.point.y)),
                intervalMs = parseLong(interval, a.intervalMs),
                count = parseInt(count, a.count),
                durationMs = parseLong(dur, a.durationMs),
            ),
        )
    }
    PercentPointFields(x, y, { x = it; emit() }, { y = it; emit() })
    NumberFieldRow("间隔（ms）", interval) { interval = it; emit() }
    NumberFieldRow("次数", count) { count = it; emit() }
    NumberFieldRow("单次按压时长（ms）", dur) { dur = it; emit() }
}

@Composable
private fun AreaRandomForm(a: AreaRandomClickAction, onChange: (Action) -> Unit) {
    var l by remember { mutableStateOf(formatPercent(a.rect.l)) }
    var t by remember { mutableStateOf(formatPercent(a.rect.t)) }
    var r by remember { mutableStateOf(formatPercent(a.rect.r)) }
    var b by remember { mutableStateOf(formatPercent(a.rect.b)) }
    var dur by remember { mutableStateOf(a.durationMs.toString()) }
    val emit: () -> Unit = {
        onChange(
            AreaRandomClickAction(
                rect = PercentRect(
                    parsePercent(l, a.rect.l),
                    parsePercent(t, a.rect.t),
                    parsePercent(r, a.rect.r),
                    parsePercent(b, a.rect.b),
                ),
                durationMs = parseLong(dur, a.durationMs),
            ),
        )
    }
    PercentRectFields(l, t, r, b, { l = it; emit() }, { t = it; emit() }, { r = it; emit() }, { b = it; emit() })
    NumberFieldRow("按压时长（ms）", dur) { dur = it; emit() }
}

// ---------------- 识别点击类 ----------------

@Composable
private fun ClickImageForm(a: ClickImageAction, templates: List<ImageTemplate>, onChange: (Action) -> Unit) {
    val context = LocalContext.current
    var templateId by remember { mutableStateOf(a.templateId.ifBlank { templates.firstOrNull()?.id.orEmpty() }) }
    var extraTemplates by remember { mutableStateOf<List<ImageTemplate>>(emptyList()) }
    val allTemplates = remember(templates, extraTemplates) {
        templates + extraTemplates.filter { extra -> templates.none { it.id == extra.id } }
    }
    var similarity by remember { mutableStateOf(a.similarity.toString()) }
    var randomOffset by remember { mutableStateOf(a.randomOffset.toString()) }
    var regionEnabled by remember { mutableStateOf(a.region != null) }
    var l by remember { mutableStateOf(formatPercent(a.region?.l ?: 0f)) }
    var t by remember { mutableStateOf(formatPercent(a.region?.t ?: 0f)) }
    var r by remember { mutableStateOf(formatPercent(a.region?.r ?: 1f)) }
    var b by remember { mutableStateOf(formatPercent(a.region?.b ?: 1f)) }
    var detectCount by remember { mutableStateOf(a.detectCount.toString()) }
    var detectInterval by remember { mutableStateOf(a.detectIntervalMs.toString()) }
    val emit: () -> Unit = {
        onChange(
            ClickImageAction(
                templateId = templateId,
                similarity = parseFloat(similarity, a.similarity),
                region = if (regionEnabled) {
                    PercentRect(
                        parsePercent(l, 0f),
                        parsePercent(t, 0f),
                        parsePercent(r, 1f),
                        parsePercent(b, 1f),
                    )
                } else null,
                randomOffset = parseInt(randomOffset, a.randomOffset),
                detectCount = parseInt(detectCount, a.detectCount),
                detectIntervalMs = parseLong(detectInterval, a.detectIntervalMs),
            ),
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        ChoiceField(
            label = "模板",
            selected = templateId,
            options = allTemplates.map { it.id to it.name },
            modifier = Modifier.weight(1f),
        ) { templateId = it; emit() }
        OutlinedButton(
            onClick = capture@{
                if (!PermissionChecker.requireOverlay(context)) return@capture
                TemplateCaptureOverlay.start(context) { template ->
                    if (template != null) {
                        extraTemplates = extraTemplates + template
                        templateId = template.id
                        emit()
                    }
                }
            },
            modifier = Modifier.padding(start = 8.dp),
        ) { Text("截图新建模板") }
    }
    NumberFieldRow("相似度（0..1）", similarity) { similarity = it; emit() }
    NumberFieldRow("随机偏移（像素）", randomOffset) { randomOffset = it; emit() }
    BoolFieldRow("限定识别区域", regionEnabled) { regionEnabled = it; emit() }
    if (regionEnabled) {
        PercentRectFields(l, t, r, b, { l = it; emit() }, { t = it; emit() }, { r = it; emit() }, { b = it; emit() })
    }
    NumberFieldRow("检测次数", detectCount) { detectCount = it; emit() }
    NumberFieldRow("检测间隔（ms）", detectInterval) { detectInterval = it; emit() }
}

@Composable
private fun ClickColorForm(a: ClickColorAction, onChange: (Action) -> Unit) {
    var color by remember { mutableStateOf(colorToHex(a.color)) }
    var tolerance by remember { mutableStateOf(a.tolerance.toString()) }
    var l by remember { mutableStateOf(formatPercent(a.region.l)) }
    var t by remember { mutableStateOf(formatPercent(a.region.t)) }
    var r by remember { mutableStateOf(formatPercent(a.region.r)) }
    var b by remember { mutableStateOf(formatPercent(a.region.b)) }
    var randomOffset by remember { mutableStateOf(a.randomOffset.toString()) }
    var detectCount by remember { mutableStateOf(a.detectCount.toString()) }
    var detectInterval by remember { mutableStateOf(a.detectIntervalMs.toString()) }
    val emit: () -> Unit = {
        onChange(
            ClickColorAction(
                color = parseColor(color, a.color),
                tolerance = parseInt(tolerance, a.tolerance),
                region = PercentRect(
                    parsePercent(l, a.region.l),
                    parsePercent(t, a.region.t),
                    parsePercent(r, a.region.r),
                    parsePercent(b, a.region.b),
                ),
                randomOffset = parseInt(randomOffset, a.randomOffset),
                detectCount = parseInt(detectCount, a.detectCount),
                detectIntervalMs = parseLong(detectInterval, a.detectIntervalMs),
            ),
        )
    }
    TextFieldRow("颜色（#RRGGBB）", color) { color = it; emit() }
    NumberFieldRow("容差（0..255）", tolerance) { tolerance = it; emit() }
    PercentRectFields(l, t, r, b, { l = it; emit() }, { t = it; emit() }, { r = it; emit() }, { b = it; emit() })
    NumberFieldRow("随机偏移（像素）", randomOffset) { randomOffset = it; emit() }
    NumberFieldRow("检测次数", detectCount) { detectCount = it; emit() }
    NumberFieldRow("检测间隔（ms）", detectInterval) { detectInterval = it; emit() }
}

@Composable
private fun ClickTextForm(a: ClickTextAction, onChange: (Action) -> Unit) {
    var text by remember { mutableStateOf(a.text) }
    var useRegex by remember { mutableStateOf(a.useRegex) }
    var detectCount by remember { mutableStateOf(a.detectCount.toString()) }
    var detectInterval by remember { mutableStateOf(a.detectIntervalMs.toString()) }
    val emit: () -> Unit = {
        onChange(
            ClickTextAction(
                text = text,
                useRegex = useRegex,
                region = a.region,
                detectCount = parseInt(detectCount, a.detectCount),
                detectIntervalMs = parseLong(detectInterval, a.detectIntervalMs),
            ),
        )
    }
    TextFieldRow("文字", text) { text = it; emit() }
    BoolFieldRow("使用正则", useRegex) { useRegex = it; emit() }
    NumberFieldRow("检测次数", detectCount) { detectCount = it; emit() }
    NumberFieldRow("检测间隔（ms）", detectInterval) { detectInterval = it; emit() }
}

@Composable
private fun ClickNodeForm(a: ClickNodeAction, onChange: (Action) -> Unit) {
    var selector by remember { mutableStateOf(a.selector) }
    var detectCount by remember { mutableStateOf(a.detectCount.toString()) }
    var detectInterval by remember { mutableStateOf(a.detectIntervalMs.toString()) }
    val emit: () -> Unit = {
        onChange(
            ClickNodeAction(
                selector = selector,
                detectCount = parseInt(detectCount, a.detectCount),
                detectIntervalMs = parseLong(detectInterval, a.detectIntervalMs),
            ),
        )
    }
    NodeSelectorForm(selector) { selector = it; emit() }
    NumberFieldRow("检测次数", detectCount) { detectCount = it; emit() }
    NumberFieldRow("检测间隔（ms）", detectInterval) { detectInterval = it; emit() }
}

@Composable
private fun NodeSelectorForm(selector: NodeSelector, onChange: (NodeSelector) -> Unit) {
    var text by remember { mutableStateOf(selector.text.orEmpty()) }
    var viewId by remember { mutableStateOf(selector.viewId.orEmpty()) }
    var className by remember { mutableStateOf(selector.className.orEmpty()) }
    var contentDesc by remember { mutableStateOf(selector.contentDesc.orEmpty()) }
    var clickableOnly by remember { mutableStateOf(selector.clickableOnly) }
    var index by remember { mutableStateOf(selector.index.toString()) }
    val emit: () -> Unit = {
        onChange(
            NodeSelector(
                text = text.ifBlank { null },
                viewId = viewId.ifBlank { null },
                className = className.ifBlank { null },
                contentDesc = contentDesc.ifBlank { null },
                clickableOnly = clickableOnly,
                index = parseInt(index, selector.index),
            ),
        )
    }
    TextFieldRow("文本", text) { text = it; emit() }
    TextFieldRow("控件 id（viewId）", viewId) { viewId = it; emit() }
    TextFieldRow("类名", className) { className = it; emit() }
    TextFieldRow("内容描述", contentDesc) { contentDesc = it; emit() }
    BoolFieldRow("仅可点击节点", clickableOnly) { clickableOnly = it; emit() }
    NumberFieldRow("命中索引（从 0 起）", index) { index = it; emit() }
}

// ---------------- 手势类 ----------------

@Composable
private fun GestureForm(a: GestureAction, onChange: (Action) -> Unit) {
    val first = a.strokes.firstOrNull()
    var points by remember {
        mutableStateOf(
            (first?.points ?: listOf(PercentPoint(0.5f, 0.5f), PercentPoint(0.5f, 0.8f)))
                .joinToString(";") { "${formatPercent(it.x)},${formatPercent(it.y)}" },
        )
    }
    var dur by remember { mutableStateOf((first?.durationMs ?: 300L).toString()) }
    val emit: () -> Unit = {
        val parsed = points.split(';')
            .mapNotNull { seg ->
                val parts = seg.trim().split(',')
                if (parts.size != 2) return@mapNotNull null
                val px = parts[0].trim().toFloatOrNull()?.div(100f)?.coerceIn(0f, 1f) ?: return@mapNotNull null
                val py = parts[1].trim().toFloatOrNull()?.div(100f)?.coerceIn(0f, 1f) ?: return@mapNotNull null
                PercentPoint(px, py)
            }
        val strokes = if (parsed.isEmpty()) {
            emptyList()
        } else {
            listOf(GestureStroke(points = parsed, startTimeMs = 0L, durationMs = parseLong(dur, 300L)))
        }
        onChange(GestureAction(strokes = strokes))
    }
    TextFieldRow("轨迹点（x,y;x,y，单位 %）", points) { points = it; emit() }
    NumberFieldRow("轨迹时长（ms）", dur) { dur = it; emit() }
}

@Composable
private fun SwipeForm(a: SwipeAction, onChange: (Action) -> Unit) {
    var fx by remember { mutableStateOf(formatPercent(a.from.x)) }
    var fy by remember { mutableStateOf(formatPercent(a.from.y)) }
    var tx by remember { mutableStateOf(formatPercent(a.to.x)) }
    var ty by remember { mutableStateOf(formatPercent(a.to.y)) }
    var dur by remember { mutableStateOf(a.durationMs.toString()) }
    val emit: () -> Unit = {
        onChange(
            SwipeAction(
                from = PercentPoint(parsePercent(fx, a.from.x), parsePercent(fy, a.from.y)),
                to = PercentPoint(parsePercent(tx, a.to.x), parsePercent(ty, a.to.y)),
                durationMs = parseLong(dur, a.durationMs),
            ),
        )
    }
    Text("起点", modifier = Modifier.padding(top = 4.dp))
    PercentPointFields(fx, fy, { fx = it; emit() }, { fy = it; emit() })
    Text("终点", modifier = Modifier.padding(top = 4.dp))
    PercentPointFields(tx, ty, { tx = it; emit() }, { ty = it; emit() })
    NumberFieldRow("时长（ms）", dur) { dur = it; emit() }
}

// ---------------- 手机控制 ----------------

@Composable
private fun GlobalKeyForm(a: GlobalKeyAction, onChange: (Action) -> Unit) {
    var key by remember { mutableStateOf(a.key) }
    ChoiceField(
        label = "按键",
        selected = key,
        options = GlobalKeyName.entries.map { it to it.name },
    ) { key = it; onChange(GlobalKeyAction(key)) }
}

// ---------------- 打开/关闭应用 ----------------

@Composable
private fun OpenAppForm(a: OpenAppAction, onChange: (Action) -> Unit) {
    var packageName by remember { mutableStateOf(a.packageName) }
    var activity by remember { mutableStateOf(a.activity.orEmpty()) }
    val emit: () -> Unit = {
        onChange(OpenAppAction(packageName = packageName, activity = activity.ifBlank { null }))
    }
    TextFieldRow("包名", packageName) { packageName = it; emit() }
    TextFieldRow("Activity（可空）", activity) { activity = it; emit() }
}

@Composable
private fun CloseAppForm(a: CloseAppAction, onChange: (Action) -> Unit) {
    var packageName by remember { mutableStateOf(a.packageName) }
    TextFieldRow("包名", packageName) { onChange(CloseAppAction(packageName)) }
}

// ---------------- 内容处理 ----------------

@Composable
private fun InputTextForm(a: InputTextAction, textGroups: List<TextGroup>, onChange: (Action) -> Unit) {
    var source by remember { mutableStateOf(a.source) }
    var clearFirst by remember { mutableStateOf(a.clearFirst) }
    val emit: () -> Unit = { onChange(InputTextAction(source = source, clearFirst = clearFirst)) }
    ChoiceField(
        label = "文本来源",
        selected = source.mode,
        options = TextMode.entries.map { it to it.name },
    ) { mode -> source = source.copy(mode = mode); emit() }
    when (source.mode) {
        TextMode.LITERAL -> {
            var literal by remember { mutableStateOf(source.literal) }
            TextFieldRow("字面量", literal) { literal = it; source = source.copy(literal = it); emit() }
        }
        TextMode.TEXT_GROUP -> {
            ChoiceField(
                label = "文本组",
                selected = source.groupId.ifBlank { null },
                options = textGroups.map { it.id to it.name },
            ) { id -> source = source.copy(groupId = id); emit() }
            ChoiceField(
                label = "取值方式",
                selected = source.pickMode,
                options = PickMode.entries.map { it to it.name },
            ) { m -> source = source.copy(pickMode = m); emit() }
        }
        TextMode.NUMBER_GENERATOR -> {
            var from by remember { mutableStateOf(source.numberFrom.toString()) }
            var to by remember { mutableStateOf(source.numberTo.toString()) }
            var step by remember { mutableStateOf(source.numberStep.toString()) }
            var len by remember { mutableStateOf(source.numberLength.toString()) }
            val emitNum: () -> Unit = {
                source = source.copy(
                    numberFrom = parseLong(from, source.numberFrom),
                    numberTo = parseLong(to, source.numberTo),
                    numberStep = parseLong(step, source.numberStep),
                    numberLength = parseInt(len, source.numberLength),
                )
                emit()
            }
            ChoiceField(
                label = "数字模式",
                selected = source.numberMode,
                options = NumberGenMode.entries.map { it to it.name },
            ) { m -> source = source.copy(numberMode = m); emit() }
            NumberFieldRow("起始", from) { from = it; emitNum() }
            NumberFieldRow("结束", to) { to = it; emitNum() }
            NumberFieldRow("步长", step) { step = it; emitNum() }
            NumberFieldRow("补零位数（0 不补）", len) { len = it; emitNum() }
        }
        TextMode.VARIABLE -> {
            var varName by remember { mutableStateOf(source.variableName) }
            TextFieldRow("变量名", varName) { varName = it; source = source.copy(variableName = it); emit() }
        }
    }
    BoolFieldRow("输入前清空", clearFirst) { clearFirst = it; emit() }
}

@Composable
private fun ExtractContentForm(a: ExtractContentAction, templates: List<ImageTemplate>, onChange: (Action) -> Unit) {
    var source by remember { mutableStateOf(a.source) }
    var nodeSelector by remember { mutableStateOf(a.nodeSelector) }
    var regex by remember { mutableStateOf(a.regex.orEmpty()) }
    var replaceWith by remember { mutableStateOf(a.replaceWith.orEmpty()) }
    var targetVar by remember { mutableStateOf(a.targetVar) }
    var regionEnabled by remember { mutableStateOf(a.region != null) }
    var l by remember { mutableStateOf(formatPercent(a.region?.l ?: 0f)) }
    var t by remember { mutableStateOf(formatPercent(a.region?.t ?: 0f)) }
    var r by remember { mutableStateOf(formatPercent(a.region?.r ?: 1f)) }
    var b by remember { mutableStateOf(formatPercent(a.region?.b ?: 1f)) }
    val emit: () -> Unit = {
        onChange(
            ExtractContentAction(
                source = source,
                region = if (regionEnabled) {
                    PercentRect(parsePercent(l, 0f), parsePercent(t, 0f), parsePercent(r, 1f), parsePercent(b, 1f))
                } else null,
                nodeSelector = nodeSelector,
                regex = regex.ifBlank { null },
                replaceWith = replaceWith.ifBlank { null },
                targetVar = targetVar,
            ),
        )
    }
    ChoiceField(
        label = "来源",
        selected = source,
        options = ExtractSource.entries.map { it to it.name },
    ) { s -> source = s; emit() }
    TextFieldRow("目标变量", targetVar) { targetVar = it; emit() }
    TextFieldRow("正则（可空）", regex) { regex = it; emit() }
    TextFieldRow("替换为（可空）", replaceWith) { replaceWith = it; emit() }
    BoolFieldRow("限定区域", regionEnabled) { regionEnabled = it; emit() }
    if (regionEnabled) {
        PercentRectFields(l, t, r, b, { l = it; emit() }, { t = it; emit() }, { r = it; emit() }, { b = it; emit() })
    }
    if (source == ExtractSource.NODE) {
        Text("节点选择器", modifier = Modifier.padding(top = 8.dp))
        NodeSelectorForm(nodeSelector ?: NodeSelector()) { nodeSelector = it; emit() }
    }
}

@Composable
private fun VariableOpForm(a: VariableOpAction, onChange: (Action) -> Unit) {
    var varName by remember { mutableStateOf(a.varName) }
    var op by remember { mutableStateOf(a.op) }
    var operand by remember {
        mutableStateOf((a.operand as? LiteralValue)?.text.orEmpty())
    }
    val emit: () -> Unit = {
        onChange(VariableOpAction(varName = varName, op = op, operand = LiteralValue(operand)))
    }
    TextFieldRow("变量名", varName) { varName = it; emit() }
    ChoiceField(
        label = "操作",
        selected = op,
        options = VarOp.entries.map { it to it.name },
    ) { o -> op = o; emit() }
    TextFieldRow("操作数（字面量）", operand) { operand = it; emit() }
}

// ---------------- 逻辑控制 ----------------

@Composable
private fun ConditionForm(a: ConditionAction, templates: List<ImageTemplate>, onChange: (Action) -> Unit) {
    val clause = a.clauses.firstOrNull() ?: ConditionClause(type = ConditionType.NODE)
    var type by remember { mutableStateOf(clause.type) }
    var text by remember { mutableStateOf(clause.text.orEmpty()) }
    var useRegex by remember { mutableStateOf(clause.useRegex) }
    var templateId by remember { mutableStateOf(clause.templateId.orEmpty()) }
    var color by remember { mutableStateOf(colorToHex(clause.color ?: 0xFFFF0000.toInt())) }
    var tolerance by remember { mutableStateOf(clause.tolerance.toString()) }
    var nodeSelector by remember { mutableStateOf(clause.nodeSelector) }
    var varName by remember { mutableStateOf(clause.varName.orEmpty()) }
    var op by remember { mutableStateOf(clause.op) }
    var compareTo by remember { mutableStateOf(clause.compareTo) }
    var negate by remember { mutableStateOf(clause.negate) }
    var combine by remember { mutableStateOf(a.combine) }
    var detectCount by remember { mutableStateOf(a.detectCount.toString()) }
    var detectInterval by remember { mutableStateOf(a.detectIntervalMs.toString()) }
    var timeout by remember { mutableStateOf(a.timeoutMs.toString()) }
    val emit: () -> Unit = {
        val newClause = clause.copy(
            type = type,
            templateId = templateId.ifBlank { null },
            text = text.ifBlank { null },
            useRegex = useRegex,
            color = parseColor(color, 0xFFFF0000.toInt()),
            tolerance = parseInt(tolerance, clause.tolerance),
            nodeSelector = nodeSelector,
            varName = varName.ifBlank { null },
            op = op,
            compareTo = compareTo,
            negate = negate,
        )
        onChange(
            ConditionAction(
                clauses = listOf(newClause),
                combine = combine,
                detectCount = parseInt(detectCount, a.detectCount),
                detectIntervalMs = parseLong(detectInterval, a.detectIntervalMs),
                timeoutMs = parseLong(timeout, a.timeoutMs),
            ),
        )
    }
    ChoiceField(
        label = "条件类型",
        selected = type,
        options = ConditionType.entries.map { it to it.name },
    ) { t -> type = t; emit() }
    when (type) {
        ConditionType.IMAGE -> {
            ChoiceField(
                label = "模板",
                selected = templateId.ifBlank { null },
                options = templates.map { it.id to it.name },
            ) { id -> templateId = id; emit() }
        }
        ConditionType.TEXT -> {
            TextFieldRow("文字", text) { text = it; emit() }
            BoolFieldRow("使用正则", useRegex) { useRegex = it; emit() }
        }
        ConditionType.COLOR -> {
            TextFieldRow("颜色（#RRGGBB）", color) { color = it; emit() }
            NumberFieldRow("容差", tolerance) { tolerance = it; emit() }
        }
        ConditionType.NODE -> {
            NodeSelectorForm(nodeSelector ?: NodeSelector()) { nodeSelector = it; emit() }
        }
        ConditionType.VARIABLE -> {
            TextFieldRow("变量名", varName) { varName = it; emit() }
            ChoiceField(
                label = "比较",
                selected = op,
                options = CompareOp.entries.map { it to it.name },
            ) { o -> op = o; emit() }
            TextFieldRow("比较值", compareTo) { compareTo = it; emit() }
        }
    }
    BoolFieldRow("取反（不满足才通过）", negate) { negate = it; emit() }
    ChoiceField(
        label = "多条件组合",
        selected = combine,
        options = BoolCombine.entries.map { it to it.name },
    ) { c -> combine = c; emit() }
    NumberFieldRow("检测次数", detectCount) { detectCount = it; emit() }
    NumberFieldRow("检测间隔（ms）", detectInterval) { detectInterval = it; emit() }
    NumberFieldRow("超时等待（ms，0 不等待）", timeout) { timeout = it; emit() }
}

@Composable
private fun JumpForm(a: JumpAction, steps: List<StepNode>, onChange: (Action) -> Unit) {
    var mode by remember { mutableStateOf(a.mode) }
    var target by remember { mutableStateOf(a.targetStepId) }
    val emit: () -> Unit = { onChange(JumpAction(mode = mode, targetStepId = target)) }
    ChoiceField(
        label = "跳转方式",
        selected = mode,
        options = JumpMode.entries.map { it to it.name },
    ) { m -> mode = m; emit() }
    if (mode == JumpMode.STEP) {
        ChoiceField(
            label = "目标步骤",
            selected = target,
            options = steps.map { it.id to "${it.id.take(6)} · ${actionSummary(it.action)}" },
        ) { id -> target = id; emit() }
    }
}

@Composable
private fun CallFunctionForm(a: CallFunctionAction, packages: List<FunctionPackage>, onChange: (Action) -> Unit) {
    var packageId by remember { mutableStateOf(a.packageId) }
    ChoiceField(
        label = "函数包",
        selected = packageId.ifBlank { null },
        options = packages.map { it.id to it.name },
    ) { id ->
        packageId = id
        onChange(CallFunctionAction(packageId = id, args = a.args, resultVars = a.resultVars))
    }
    Text("入参/返回值编辑将在函数包功能完善后提供", modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun DelayForm(a: DelayAction, onChange: (Action) -> Unit) {
    var ms by remember { mutableStateOf(a.ms.toString()) }
    var randomMs by remember { mutableStateOf(a.randomMs.toString()) }
    val emit: () -> Unit = {
        onChange(DelayAction(ms = parseLong(ms, a.ms), randomMs = parseLong(randomMs, a.randomMs)))
    }
    NumberFieldRow("延时（ms）", ms) { ms = it; emit() }
    NumberFieldRow("随机追加（ms）", randomMs) { randomMs = it; emit() }
}

@Composable
private fun EmptyForm(a: EmptyAction, onChange: (Action) -> Unit) {
    var note by remember { mutableStateOf(a.note) }
    TextFieldRow("注释", note) { onChange(EmptyAction(note = it)) }
}

// ---------------- 提示类 ----------------

@Composable
private fun ToastForm(a: ToastAction, onChange: (Action) -> Unit) {
    var message by remember { mutableStateOf(a.message) }
    TextFieldRow("内容", message) { onChange(ToastAction(message = it)) }
}

@Composable
private fun PopupForm(a: PopupAction, onChange: (Action) -> Unit) {
    var title by remember { mutableStateOf(a.title) }
    var message by remember { mutableStateOf(a.message) }
    val emit: () -> Unit = { onChange(PopupAction(title = title, message = message)) }
    TextFieldRow("标题", title) { title = it; emit() }
    TextFieldRow("内容", message) { message = it; emit() }
}

@Composable
private fun SpeakForm(a: SpeakAction, onChange: (Action) -> Unit) {
    var message by remember { mutableStateOf(a.message) }
    TextFieldRow("播报内容", message) { onChange(SpeakAction(message = it)) }
}