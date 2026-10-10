package com.autoclicker.ui.packages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.autoclicker.di.ServiceLocator
import com.autoclicker.domain.model.FunctionPackage
import com.autoclicker.domain.model.Ids
import kotlinx.coroutines.launch

/**
 * 函数包列表页（底部 Tab 页之一，不自带底部导航栏）。
 *
 * 支持新建、打开、导入/导出 JSON 与删除。
 */
@Composable
fun PackagesScreen(onOpenPackage: (String) -> Unit) {
    var packages by remember { mutableStateOf<List<FunctionPackage>>(emptyList()) }
    val scope = rememberCoroutineScope()

    var showCreateDialog by remember { mutableStateOf(false) }
    var createName by remember { mutableStateOf("") }
    var importText by remember { mutableStateOf<String?>(null) }
    var exportText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { packages = ServiceLocator.packages.list() }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = {
                createName = ""
                showCreateDialog = true
            }) {
                Icon(Icons.Filled.Add, contentDescription = "新建函数包")
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            Text(
                text = "函数包",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(16.dp),
            )
            if (packages.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无函数包，点击右下角新建", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(packages, key = { it.id }) { pkg ->
                        PackageRow(
                            pkg = pkg,
                            onOpen = { onOpenPackage(pkg.id) },
                            onExport = {
                                scope.launch {
                                    exportText = ServiceLocator.packages.exportJson(pkg.id).orEmpty()
                                }
                            },
                            onImport = { importText = "" },
                            onDelete = {
                                scope.launch {
                                    ServiceLocator.packages.delete(pkg.id)
                                    packages = ServiceLocator.packages.list()
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("新建函数包") },
            text = {
                OutlinedTextField(
                    value = createName,
                    onValueChange = { createName = it },
                    label = { Text("名称") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = createName.trim().ifBlank { "新函数包" }
                    val pkg = FunctionPackage(id = Ids.newId(), name = name)
                    showCreateDialog = false
                    scope.launch {
                        ServiceLocator.packages.save(pkg)
                        onOpenPackage(pkg.id)
                    }
                }) { Text("创建") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text("取消") }
            },
        )
    }

    importText?.let { text ->
        AlertDialog(
            onDismissRequest = { importText = null },
            title = { Text("导入 JSON") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { importText = it },
                    label = { Text("粘贴 JSON") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val json = text
                    importText = null
                    if (json.isNotBlank()) {
                        scope.launch {
                            runCatching { ServiceLocator.packages.importJson(json) }
                            packages = ServiceLocator.packages.list()
                        }
                    }
                }) { Text("导入") }
            },
            dismissButton = {
                TextButton(onClick = { importText = null }) { Text("取消") }
            },
        )
    }

    exportText?.let { text ->
        AlertDialog(
            onDismissRequest = { exportText = null },
            title = { Text("导出 JSON") },
            text = {
                Box(modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp).verticalScroll(rememberScrollState())) {
                    Text(text = text, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = { exportText = null }) { Text("关闭") }
            },
        )
    }
}

@Composable
private fun PackageRow(
    pkg: FunctionPackage,
    onOpen: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = pkg.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "${pkg.actionCount} 个动作 · ${pkg.params.size} 参数 · ${pkg.returns.size} 返回值",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除")
                }
            }
            if (pkg.description.isNotBlank()) {
                Text(text = pkg.description, style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onOpen) { Text("打开") }
                TextButton(onClick = onExport) { Text("导出 JSON") }
                TextButton(onClick = onImport) { Text("导入 JSON") }
            }
        }
    }
}