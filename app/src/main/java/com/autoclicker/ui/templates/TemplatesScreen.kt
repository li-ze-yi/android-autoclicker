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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
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
import com.autoclicker.domain.model.ImageTemplate
import com.autoclicker.service.capture.TemplateCaptureOverlay
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
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { templates = ServiceLocator.templates.list() }

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
                OutlinedButton(onClick = capture@{
                    if (!PermissionChecker.requireOverlay(context)) return@capture
                    TemplateCaptureOverlay.start(context) { template ->
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
            }
            TextButton(onClick = onRename) { Text("重命名") }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "删除")
            }
        }
    }
}