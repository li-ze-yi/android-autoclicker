package com.autoclicker.ui.scripts

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.autoclicker.core.accessibility.AutoAccessService
import com.autoclicker.core.runner.ScriptRunner
import com.autoclicker.core.script.Script
import com.autoclicker.core.script.ScriptRepository
import com.autoclicker.core.util.PermissionChecker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

private fun formatTime(timestamp: Long): String = timeFormat.format(Date(timestamp))

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ScriptListScreen(onOpenScript: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { ScriptRepository.get(context) }

    var scripts by remember { mutableStateOf<List<Script>>(emptyList()) }
    var openMenuId by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<Script?>(null) }
    var deleteTarget by remember { mutableStateOf<Script?>(null) }

    val reload: () -> Unit = { scripts = repository.listScripts() }

    val runScript: (Script) -> Unit = { script ->
        if (!AutoAccessService.isConnected) {
            Toast.makeText(context, "请先开启无障碍服务", Toast.LENGTH_SHORT).show()
            PermissionChecker.openAccessibilitySettings(context)
        } else {
            val started = ScriptRunner.start(context, script)
            Toast.makeText(
                context,
                if (started) "已开始运行：${script.name}" else "脚本正在运行中",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        reload()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) reload()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("脚本库") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                val created = repository.createNew("新脚本")
                onOpenScript(created.id)
            }) {
                Icon(Icons.Filled.Add, contentDescription = "新建脚本")
            }
        }
    ) { innerPadding ->
        if (scripts.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp)
            ) {
                Text("暂无脚本，点击右下角新建")
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(scripts, key = { it.id }) { script ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { onOpenScript(script.id) },
                                onLongClick = { openMenuId = script.id }
                            )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = script.name,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "${script.steps.size} 个步骤 · ${formatTime(script.updatedAt)}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Box {
                                IconButton(onClick = {
                                    openMenuId = if (openMenuId == script.id) null else script.id
                                }) {
                                    Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                                }
                                DropdownMenu(
                                    expanded = openMenuId == script.id,
                                    onDismissRequest = { openMenuId = null }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("运行") },
                                        onClick = {
                                            openMenuId = null
                                            runScript(script)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("重命名") },
                                        onClick = {
                                            openMenuId = null
                                            renameTarget = script
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("删除") },
                                        onClick = {
                                            openMenuId = null
                                            deleteTarget = script
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    renameTarget?.let { target ->
        var newName by remember(target.id) { mutableStateOf(target.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("脚本名") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    repository.rename(target.id, newName)
                    renameTarget = null
                    reload()
                }) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("取消") }
            }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除脚本") },
            text = { Text("确定要删除「${target.name}」吗？该操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    repository.delete(target.id)
                    deleteTarget = null
                    reload()
                }) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            }
        )
    }
}