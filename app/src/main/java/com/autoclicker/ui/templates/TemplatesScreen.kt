package com.autoclicker.ui.templates

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.autoclicker.core.permission.PermissionChecker
import com.autoclicker.di.ServiceLocator
import com.autoclicker.domain.model.Ids
import com.autoclicker.domain.model.ImageTemplate
import com.autoclicker.domain.model.ImageTemplateGroup
import com.autoclicker.service.capture.TemplateCaptureOverlay
import com.autoclicker.service.overlay.OverlayService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 图像模板页（底部 Tab 页之一，不自带底部导航栏）。
 *
 * 导入本地图片并保存为模板，支持缩略图预览、重命名与删除。
 */
@Composable
fun TemplatesScreen() {
    val context = LocalContext.current
    var templates by remember { mutableStateOf<List<ImageTemplate>>(emptyList()) }
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var pendingName by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<ImageTemplate?>(null) }
    var renameName by remember { mutableStateOf("") }
    var groups by remember { mutableStateOf<List<ImageTemplateGroup>>(emptyList()) }
    var groupManagerOpen by remember { mutableStateOf(false) }
    var editingGroup by remember { mutableStateOf<ImageTemplateGroup?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        templates = ServiceLocator.templates.list()
        groups = ServiceLocator.templateGroups.list()
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            pendingName = uri.lastPathSegment
                ?.substringAfterLast('/')
                ?.substringBeforeLast('.')
                ?.ifBlank { "模板" }
                ?: "模板"
            pendingUri = uri
        }
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { picker.launch("image/*") }) {
                Icon(Icons.Filled.Add, contentDescription = "导入图片")
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "模板",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = { groupManagerOpen = true }) { Text("模板组") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = capture@{
                    if (!PermissionChecker.requireOverlay(context)) return@capture
                    // 截图前先收起悬浮窗，避免悬浮球被截进画面。
                    OverlayService.enterCaptureMode(context)
                    TemplateCaptureOverlay.start(context) { template, _ ->
                        OverlayService.exitCaptureMode(context)
                        if (template != null) {
                            scope.launch { templates = ServiceLocator.templates.list() }
                        }
                    }
                }) { Text("截图添加") }
            }
            if (templates.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无模板，点击右下角导入图片", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(templates, key = { it.id }) { template ->
                        TemplateRow(
                            template = template,
                            onRename = {
                                renameName = template.name
                                renameTarget = template
                            },
                            onDelete = {
                                scope.launch {
                                    ServiceLocator.templates.delete(template.id)
                                    templates = ServiceLocator.templates.list()
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    pendingUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingUri = null },
            title = { Text("导入模板") },
            text = {
                OutlinedTextField(
                    value = pendingName,
                    onValueChange = { pendingName = it },
                    label = { Text("模板名称") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = pendingName.trim().ifBlank { "模板" }
                    pendingUri = null
                    scope.launch {
                        val bitmap = withContext(Dispatchers.IO) {
                            context.contentResolver.openInputStream(uri)?.use { stream ->
                                BitmapFactory.decodeStream(stream)
                            }
                        }
                        if (bitmap != null) {
                            ServiceLocator.templates.save(name, bitmap)
                            templates = ServiceLocator.templates.list()
                        }
                    }
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { pendingUri = null }) { Text("取消") }
            },
        )
    }

    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名") },
            text = {
                OutlinedTextField(
                    value = renameName,
                    onValueChange = { renameName = it },
                    label = { Text("名称") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = renameName.trim().ifBlank { target.name }
                    renameTarget = null
                    scope.launch {
                        ServiceLocator.templates.rename(target.id, name)
                        templates = ServiceLocator.templates.list()
                    }
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("取消") }
            },
        )
    }

    if (groupManagerOpen) {
        TemplateGroupManagerDialog(
            groups = groups,
            onClose = { groupManagerOpen = false },
            onCreate = {
                val group = ImageTemplateGroup(id = Ids.newId(), name = "模板组 ${groups.size + 1}")
                scope.launch {
                    ServiceLocator.templateGroups.save(group)
                    groups = ServiceLocator.templateGroups.list()
                    editingGroup = group
                }
            },
            onEdit = { editingGroup = it },
            onDelete = { g ->
                scope.launch {
                    ServiceLocator.templateGroups.delete(g.id)
                    groups = ServiceLocator.templateGroups.list()
                }
            },
        )
    }

    editingGroup?.let { target ->
        TemplateGroupEditorDialog(
            initial = target,
            templates = templates,
            onSave = { updated ->
                scope.launch {
                    ServiceLocator.templateGroups.save(updated)
                    groups = ServiceLocator.templateGroups.list()
                    editingGroup = null
                }
            },
            onDismiss = { editingGroup = null },
        )
    }
}

/** 模板组管理对话框：列出各组并支持新建 / 编辑 / 删除。 */
@Composable
private fun TemplateGroupManagerDialog(
    groups: List<ImageTemplateGroup>,
    onClose: () -> Unit,
    onCreate: () -> Unit,
    onEdit: (ImageTemplateGroup) -> Unit,
    onDelete: (ImageTemplateGroup) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("模板组") },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "模板组把同一目标的多张图片放在一起识别，任一张命中即可成功（按组内顺序先命中先停）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (groups.isEmpty()) {
                    Text("暂无模板组", modifier = Modifier.padding(top = 8.dp))
                } else {
                    groups.forEach { g ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(g.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${g.memberIds.size} 张模板",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { onEdit(g) }) { Text("编辑") }
                            IconButton(onClick = { onDelete(g) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "删除")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onCreate) { Text("新建模板组") } },
        dismissButton = { TextButton(onClick = onClose) { Text("关闭") } },
    )
}

/** 模板组编辑对话框：改名称、勾选成员（顺序即识别顺序）。 */
@Composable
private fun TemplateGroupEditorDialog(
    initial: ImageTemplateGroup,
    templates: List<ImageTemplate>,
    onSave: (ImageTemplateGroup) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var members by remember { mutableStateOf(initial.memberIds.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑模板组") },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("模板组名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "勾选该组包含的模板；识别时按下方列表顺序逐个匹配，命中第一个即停止。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (templates.isEmpty()) {
                    Text("还没有图像模板", modifier = Modifier.padding(top = 8.dp))
                } else {
                    templates.forEach { t ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = t.id in members,
                                onCheckedChange = { checked ->
                                    members = if (checked) members + t.id else members - t.id
                                },
                            )
                            Text(t.name, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // 成员顺序固定为界面列表顺序，避免勾选先后造成识别顺序漂移。
                val ordered = templates.filter { it.id in members }.map { it.id }
                onSave(initial.copy(name = name.trim().ifBlank { initial.name }, memberIds = ordered))
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun TemplateRow(
    template: ImageTemplate,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var bitmap by remember(template.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(template.id) {
        bitmap = ServiceLocator.templates.loadBitmap(template.id)
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                val current = bitmap
                if (current != null) {
                    Image(
                        bitmap = current.asImageBitmap(),
                        contentDescription = template.name,
                        modifier = Modifier.size(64.dp),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Text("无图", style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = template.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "${template.width} × ${template.height}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "识别区域：" + (
                        template.region?.let {
                            "已限定 ${(it.l * 100).toInt()}%,${(it.t * 100).toInt()}% → " +
                                "${(it.r * 100).toInt()}%,${(it.b * 100).toInt()}%"
                        } ?: "全屏"
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onRename) { Text("重命名") }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "删除")
            }
        }
    }
}