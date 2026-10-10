package com.autoclicker.ui.packages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.autoclicker.di.ServiceLocator
import com.autoclicker.domain.model.Action
import com.autoclicker.domain.model.ClickAction
import com.autoclicker.domain.model.ConditionAction
import com.autoclicker.domain.model.DelayAction
import com.autoclicker.domain.model.EmptyAction
import com.autoclicker.domain.model.FunctionPackage
import com.autoclicker.domain.model.GestureAction
import com.autoclicker.domain.model.GestureStroke
import com.autoclicker.domain.model.GlobalKeyAction
import com.autoclicker.domain.model.GlobalKeyName
import com.autoclicker.domain.model.GroupNode
import com.autoclicker.domain.model.Ids
import com.autoclicker.domain.model.InputTextAction
import com.autoclicker.domain.model.JumpAction
import com.autoclicker.domain.model.LiteralValue
import com.autoclicker.domain.model.LongPressAction
import com.autoclicker.domain.model.PercentPoint
import com.autoclicker.domain.model.ReturnDef
import com.autoclicker.domain.model.StepNode
import com.autoclicker.domain.model.SwipeAction
import com.autoclicker.domain.model.ToastAction
import com.autoclicker.domain.model.VarOp
import com.autoclicker.domain.model.VarType
import com.autoclicker.domain.model.VariableOpAction
import com.autoclicker.domain.rule.StructureValidator
import com.autoclicker.domain.rule.ValidationIssue
import kotlinx.coroutines.launch

/** 添加动作时可选择的最常用动作类型。 */
private enum class ActionKind(val label: String) {
    CLICK("点击"),
    LONG_PRESS("长按"),
    SWIPE("滑动"),
    GESTURE("手势"),
    GLOBAL_KEY("全局键"),
    DELAY("延时"),
    INPUT_TEXT("输入文字"),
    VARIABLE_OP("变量操作"),
    CONDITION("条件判断"),
    JUMP("跳转"),
    TOAST("提示"),
    EMPTY("空步骤"),
}

private fun defaultAction(kind: ActionKind): Action = when (kind) {
    ActionKind.CLICK -> ClickAction(PercentPoint(0.5f, 0.5f))
    ActionKind.LONG_PRESS -> LongPressAction(PercentPoint(0.5f, 0.5f))
    ActionKind.SWIPE -> SwipeAction(PercentPoint(0.3f, 0.5f), PercentPoint(0.7f, 0.5f))
    ActionKind.GESTURE -> GestureAction(
        listOf(
            GestureStroke(
                points = listOf(PercentPoint(0.3f, 0.5f), PercentPoint(0.7f, 0.5f)),
            ),
        ),
    )
    ActionKind.GLOBAL_KEY -> GlobalKeyAction(GlobalKeyName.BACK)
    ActionKind.DELAY -> DelayAction(ms = 1000)
    ActionKind.INPUT_TEXT -> InputTextAction()
    ActionKind.VARIABLE_OP -> VariableOpAction(varName = "v", op = VarOp.ASSIGN, operand = LiteralValue(""))
    ActionKind.CONDITION -> ConditionAction()
    ActionKind.JUMP -> JumpAction()
    ActionKind.TOAST -> ToastAction("提示")
    ActionKind.EMPTY -> EmptyAction()
}

private fun actionSummary(action: Action): String = when (action) {
    is ClickAction -> "点击 (${formatPercent(action.point.x)}, ${formatPercent(action.point.y)})"
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

/**
 * 函数包编辑器：编辑名称/描述、入参、返回值与动作步骤，保存时做结构校验。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackageEditorScreen(packageId: String, onBack: () -> Unit) {
    var pkg by remember { mutableStateOf<FunctionPackage?>(null) }
    var issues by remember { mutableStateOf<List<ValidationIssue>>(emptyList()) }
    var showIssues by remember { mutableStateOf(false) }
    var showKindDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun updatePkg(transform: (FunctionPackage) -> FunctionPackage) {
        pkg = pkg?.let(transform)
    }

    fun save() {
        val current = pkg ?: return
        val updated = current.copy(name = current.name.trim(), updatedAt = System.currentTimeMillis())
        pkg = updated
        issues = StructureValidator.validatePackage(updated)
        showIssues = true
        scope.launch { ServiceLocator.packages.save(updated) }
    }

    LaunchedEffect(packageId) {
        pkg = ServiceLocator.packages.get(packageId)
            ?: FunctionPackage(id = packageId.ifBlank { Ids.newId() }, name = "新函数包")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("函数包") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = { save() }) { Text("保存") }
                },
            )
        },
    ) { padding ->
        val current = pkg
        if (current == null) {
            Box(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { Text("加载中…") }
        } else {
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = current.name,
                    onValueChange = { value -> updatePkg { it.copy(name = value) } },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = current.description,
                    onValueChange = { value -> updatePkg { it.copy(description = value) } },
                    label = { Text("描述") },
                    modifier = Modifier.fillMaxWidth(),
                )

                SectionTitle("入参")
                current.params.forEachIndexed { index, param ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = param.name,
                                    onValueChange = { value ->
                                        updatePkg { p ->
                                            p.copy(params = p.params.mapIndexed { i, item ->
                                                if (i == index) item.copy(name = value) else item
                                            })
                                        }
                                    },
                                    label = { Text("参数名") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(onClick = {
                                    updatePkg { p -> p.copy(params = p.params.filterIndexed { i, _ -> i != index }) }
                                }) { Icon(Icons.Filled.Delete, contentDescription = "删除参数") }
                            }
                            TypeDropdown(
                                selected = param.type,
                                onSelect = { type ->
                                    updatePkg { p ->
                                        p.copy(params = p.params.mapIndexed { i, item ->
                                            if (i == index) item.copy(type = type) else item
                                        })
                                    }
                                },
                            )
                            OutlinedTextField(
                                value = param.defaultValue,
                                onValueChange = { value ->
                                    updatePkg { p ->
                                        p.copy(params = p.params.mapIndexed { i, item ->
                                            if (i == index) item.copy(defaultValue = value) else item
                                        })
                                    }
                                },
                                label = { Text("默认值") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = param.description,
                                onValueChange = { value ->
                                    updatePkg { p ->
                                        p.copy(params = p.params.mapIndexed { i, item ->
                                            if (i == index) item.copy(description = value) else item
                                        })
                                    }
                                },
                                label = { Text("说明") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                OutlinedButton(onClick = {
                    updatePkg { p -> p.copy(params = p.params + com.autoclicker.domain.model.ParamDef(name = "param${p.params.size + 1}")) }
                }) { Text("添加参数") }

                SectionTitle("返回值")
                current.returns.forEachIndexed { index, ret ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = ret.name,
                                    onValueChange = { value ->
                                        updatePkg { p ->
                                            p.copy(returns = p.returns.mapIndexed { i, item ->
                                                if (i == index) item.copy(name = value) else item
                                            })
                                        }
                                    },
                                    label = { Text("名称") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(onClick = {
                                    updatePkg { p -> p.copy(returns = p.returns.filterIndexed { i, _ -> i != index }) }
                                }) { Icon(Icons.Filled.Delete, contentDescription = "删除返回值") }
                            }
                            OutlinedTextField(
                                value = ret.description,
                                onValueChange = { value ->
                                    updatePkg { p ->
                                        p.copy(returns = p.returns.mapIndexed { i, item ->
                                            if (i == index) item.copy(description = value) else item
                                        })
                                    }
                                },
                                label = { Text("说明") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                OutlinedButton(onClick = {
                    updatePkg { p -> p.copy(returns = p.returns + ReturnDef(name = "result${p.returns.size + 1}")) }
                }) { Text("添加返回值") }

                SectionTitle("动作步骤")
                current.nodes.forEachIndexed { index, node ->
                    when (node) {
                        is StepNode -> StepNodeEditor(
                            index = index,
                            step = node,
                            onUpdate = { updated ->
                                updatePkg { p ->
                                    p.copy(nodes = p.nodes.mapIndexed { i, n -> if (i == index) updated else n })
                                }
                            },
                            onDelete = {
                                updatePkg { p -> p.copy(nodes = p.nodes.filterIndexed { i, _ -> i != index }) }
                            },
                        )
                        is GroupNode -> Card(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "组：${node.name.ifBlank { node.id }}（循环 ${node.loopCount} 次，含 ${node.children.size} 项）",
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(onClick = {
                                    updatePkg { p -> p.copy(nodes = p.nodes.filterIndexed { i, _ -> i != index }) }
                                }) { Icon(Icons.Filled.Delete, contentDescription = "删除步骤组") }
                            }
                        }
                    }
                }
                Button(onClick = { showKindDialog = true }) { Text("添加动作") }
            }
        }
    }

    if (showKindDialog) {
        AlertDialog(
            onDismissRequest = { showKindDialog = false },
            title = { Text("选择动作类型") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    ActionKind.entries.forEach { kind ->
                        TextButton(
                            onClick = {
                                val step = StepNode(id = Ids.newId(), action = defaultAction(kind))
                                updatePkg { p -> p.copy(nodes = p.nodes + step) }
                                showKindDialog = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(kind.label, modifier = Modifier.fillMaxWidth()) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showKindDialog = false }) { Text("取消") }
            },
        )
    }

    if (showIssues) {
        AlertDialog(
            onDismissRequest = { showIssues = false },
            title = { Text("校验结果") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (issues.isEmpty()) {
                        Text("校验通过")
                    } else {
                        issues.forEach { issue ->
                            Text(
                                text = "${if (issue.level == ValidationIssue.Level.ERROR) "错误" else "警告"}：${issue.message}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (issue.level == ValidationIssue.Level.ERROR) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showIssues = false }) { Text("确定") }
            },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider()
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(vertical = 4.dp),
        )
    }
}

@Composable
private fun TypeDropdown(selected: VarType, onSelect: (VarType) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text("类型：${selected.name}") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            VarType.entries.forEach { type ->
                DropdownMenuItem(
                    text = { Text(type.name) },
                    onClick = {
                        onSelect(type)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun StepNodeEditor(
    index: Int,
    step: StepNode,
    onUpdate: (StepNode) -> Unit,
    onDelete: () -> Unit,
) {
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
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除步骤")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = step.delayAfterMs.toString(),
                    onValueChange = { text ->
                        val value = text.filter { it.isDigit() }.toLongOrNull() ?: 0L
                        onUpdate(step.copy(delayAfterMs = value))
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
                        onUpdate(step.copy(repeatCount = value))
                    },
                    label = { Text("重复") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
            OutlinedTextField(
                value = step.note,
                onValueChange = { onUpdate(step.copy(note = it)) },
                label = { Text("备注") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}