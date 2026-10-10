package com.autoclicker.ui.home

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.autoclicker.di.ServiceLocator
import com.autoclicker.domain.model.Script
import kotlinx.coroutines.launch

/**
 * 首页：任务列表 + 运行 + 新建/导入 + 各功能入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenScript: (String) -> Unit,
    onOpenRecord: () -> Unit,
    onOpenConsole: () -> Unit,
    onOpenPermissions: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var scripts by remember { mutableStateOf<List<Script>>(emptyList()) }
    val reload: () -> Unit = {
        scope.launch {
            scripts = runCatching { ServiceLocator.scripts.list() }.getOrDefault(emptyList())
        }
    }
    LaunchedEffect(Unit) { reload() }

    // 新建任务
    var showCreate by remember { mutableStateOf(false) }
    var createName by remember { mutableStateOf("") }
    // 分享导入
    var showImport by remember { mutableStateOf(false) }
    var importCode by remember { mutableStateOf("") }
    // 重命名
    var renameTarget by remember { mutableStateOf<Script?>(null) }
    var renameText by remember { mutableStateOf("") }
    // 导出分享码结果
    var exportCode by remember { mutableStateOf<String?>(null) }

    val toast: (String) -> Unit = { msg ->
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("自动点击") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { createName = ""; showCreate = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("新建任务") },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
        ) {
            // 功能入口
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onOpenRecord, modifier = Modifier.weight(1f)) {
                    Text("录制")
                }
                OutlinedButton(onClick = onOpenConsole, modifier = Modifier.weight(1f)) {
                    Text("运行控制台")
                }
                OutlinedButton(onClick = onOpenPermissions, modifier = Modifier.weight(1f)) {
                    Text("权限")
                }
            }
            OutlinedButton(
                onClick = { importCode = ""; showImport = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
            ) {
                Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("分享导入")
            }

            if (scripts.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无任务，点击右下角新建", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(scripts, key = { it.id }) { script ->
                        ScriptCard(
                            script = script,
                            onOpen = { onOpenScript(script.id) },
                            onRun = {
                                scope.launch {
                                    val player = ServiceLocator.player
                                    if (player == null) {
                                        toast("运行引擎未就绪")
                                    } else {
                                        runCatching { player.play(script) }
                                            .onFailure { toast("运行失败：${it.message}") }
                                    }
                                }
                            },
                            onRename = {
                                renameTarget = script
                                renameText = script.name
                            },
                            onExport = {
                                scope.launch {
                                    val code = runCatching {
                                        ServiceLocator.scripts.exportShareCode(script.id)
                                    }.getOrNull()
                                    if (code.isNullOrBlank()) {
                                        toast("导出失败")
                                    } else {
                                        exportCode = code
                                    }
                                }
                            },
                            onDelete = {
                                scope.launch {
                                    runCatching { ServiceLocator.scripts.delete(script.id) }
                                    reload()
                                }
                            },
                        )
                    }
                    item { Spacer(Modifier.height(88.dp)) }
                }
            }
        }
    }

    // 新建任务对话框
    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("新建任务") },
            text = {
                OutlinedTextField(
                    value = createName,
                    onValueChange = { createName = it },
                    label = { Text("任务名称") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = createName.ifBlank { "未命名任务" }
                        scope.launch {
                            val script = Script(id = com.autoclicker.domain.model.Ids.newId(), name = name)
                            runCatching { ServiceLocator.scripts.save(script) }
                                .onFailure { toast("创建失败：${it.message}") }
                            showCreate = false
                            reload()
                            onOpenScript(script.id)
                        }
                    },
                ) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("取消") } },
        )
    }

    // 分享导入对话框
    if (showImport) {
        AlertDialog(
            onDismissRequest = { showImport = false },
            title = { Text("分享导入") },
            text = {
                OutlinedTextField(
                    value = importCode,
                    onValueChange = { importCode = it },
                    label = { Text("分享码") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val code = importCode.trim()
                        if (code.isEmpty()) {
                            toast("请输入分享码")
                        } else {
                            scope.launch {
                                runCatching { ServiceLocator.scripts.importShareCode(code) }
                                    .onSuccess {
                                        showImport = false
                                        reload()
                                        toast("导入成功")
                                    }
                                    .onFailure { toast("导入失败：${it.message}") }
                            }
                        }
                    },
                ) { Text("导入") }
            },
            dismissButton = { TextButton(onClick = { showImport = false }) { Text("取消") } },
        )
    }

    // 重命名
    val target = renameTarget
    if (target != null) {
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("任务名称") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val newName = renameText.ifBlank { target.name }
                        scope.launch {
                            runCatching {
                                ServiceLocator.scripts.save(
                                    target.copy(
                                        name = newName,
                                        updatedAt = System.currentTimeMillis(),
                                    ),
                                )
                            }
                            renameTarget = null
                            reload()
                        }
                    },
                ) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("取消") } },
        )
    }

    // 导出分享码结果
    val code = exportCode
    if (code != null) {
        AlertDialog(
            onDismissRequest = { exportCode = null },
            title = { Text("分享码") },
            text = { Text(code) },
            confirmButton = { TextButton(onClick = { exportCode = null }) { Text("关闭") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ScriptCard(
    script: Script,
    onOpen: () -> Unit,
    onRun: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = { menuOpen = true }),
        colors = CardDefaults.cardColors(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(script.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(
                    "动作数：${script.actionCount}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledIconButton(
                onClick = onRun,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF12B76A)),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "运行")
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        onClick = { menuOpen = false; onRename() },
                    )
                    DropdownMenuItem(
                        text = { Text("导出分享码") },
                        leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                        onClick = { menuOpen = false; onExport() },
                    )
                    DropdownMenuItem(
                        text = { Text("删除") },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
            }
        }
    }
}