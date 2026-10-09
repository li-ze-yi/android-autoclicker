package com.autoclicker.ui.permission

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.autoclicker.core.script.Script
import com.autoclicker.core.script.ScriptRepository
import com.autoclicker.core.trigger.NotificationTrigger
import com.autoclicker.core.trigger.NotificationTriggerStore
import com.autoclicker.core.util.PermissionChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    var accessibilityOn by remember { mutableStateOf(false) }
    var overlayOn by remember { mutableStateOf(false) }
    var notificationsOn by remember { mutableStateOf(false) }
    var batteryIgnored by remember { mutableStateOf(false) }

    val refresh: () -> Unit = {
        accessibilityOn = PermissionChecker.isAccessibilityEnabled(context)
        overlayOn = PermissionChecker.isOverlayGranted(context)
        notificationsOn = PermissionChecker.areNotificationsGranted(context)
        batteryIgnored = PermissionChecker.isIgnoringBatteryOptimizations(context)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        refresh()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("权限中心") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            PermissionRow(
                title = "无障碍服务",
                desc = "用于模拟点击、滑动、输入与查找界面元素",
                statusText = if (accessibilityOn) "已开启" else "未开启",
                ok = accessibilityOn,
                actionText = "去开启",
                onAction = { PermissionChecker.openAccessibilitySettings(context) }
            )
            PermissionRow(
                title = "悬浮窗",
                desc = "用于显示控制面板与坐标拾取",
                statusText = if (overlayOn) "已开启" else "未开启",
                ok = overlayOn,
                actionText = "去开启",
                onAction = { PermissionChecker.openOverlaySettings(context) }
            )
            PermissionRow(
                title = "通知",
                desc = "用于展示运行状态与定时提醒",
                statusText = if (notificationsOn) "已开启" else "未开启",
                ok = notificationsOn,
                actionText = "去开启",
                onAction = { PermissionChecker.openAppNotificationSettings(context) }
            )
            if (batteryIgnored) {
                PermissionRow(
                    title = "电池优化",
                    desc = "已加入白名单，可后台稳定运行",
                    statusText = "已忽略",
                    ok = true,
                    actionText = "查看设置",
                    onAction = { PermissionChecker.openBatteryOptimizationSettings(context) }
                )
            } else {
                PermissionRow(
                    title = "电池优化",
                    desc = "加入白名单可避免后台被系统限制",
                    statusText = "未忽略",
                    ok = false,
                    actionText = "加入白名单",
                    onAction = { PermissionChecker.requestIgnoreBatteryOptimizations(context) }
                )
            }

            NotificationTriggerCard()
        }
    }
}

/**
 * 通知触发配置卡片（P3）：选择来源包名 / 关键字与目标脚本。
 * 需要先在系统「通知使用权」中授权本应用。
 */
@Composable
private fun NotificationTriggerCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var scripts by remember { mutableStateOf<List<Script>>(emptyList()) }
    var config by remember { mutableStateOf(NotificationTriggerStore.load(context)) }
    var granted by remember { mutableStateOf(PermissionChecker.isNotificationAccessGranted(context)) }
    var menuOpen by remember { mutableStateOf(false) }

    // 脚本列表为文件 IO，放到 IO 线程，回到主线程再写入状态。
    val reloadScripts: () -> Unit = {
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { ScriptRepository.get(context).listScripts() }
            scripts = loaded
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        reloadScripts()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = PermissionChecker.isNotificationAccessGranted(context)
                reloadScripts()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val save: (NotificationTrigger) -> Unit = { updated ->
        config = updated
        NotificationTriggerStore.save(context, updated)
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "通知触发",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (granted) "已授权" else "未授权",
                    color = if (granted) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Text(
                text = "收到匹配的通知时自动运行脚本；需授予「通知使用权」",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedButton(onClick = { PermissionChecker.openNotificationAccessSettings(context) }) {
                Text("去开启通知使用权")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("启用", modifier = Modifier.weight(1f))
                Switch(
                    checked = config.enabled,
                    onCheckedChange = { save(config.copy(enabled = it)) }
                )
            }
            OutlinedTextField(
                value = config.packageName,
                onValueChange = { save(config.copy(packageName = it.trim())) },
                label = { Text("来源包名（空=不限）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = config.keyword,
                onValueChange = { save(config.copy(keyword = it)) },
                label = { Text("关键字（标题/正文包含，空=不限）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Box {
                OutlinedButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val name = scripts.firstOrNull { it.id == config.scriptId }?.name ?: "（未选择脚本）"
                    Text("触发脚本：$name")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (scripts.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("暂无脚本") },
                            onClick = { menuOpen = false }
                        )
                    } else {
                        scripts.forEach { script ->
                            DropdownMenuItem(
                                text = { Text(script.name) },
                                onClick = {
                                    menuOpen = false
                                    save(config.copy(scriptId = script.id))
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    desc: String,
    statusText: String,
    ok: Boolean,
    actionText: String,
    onAction: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = statusText,
                    color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Text(text = desc, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onAction) {
                Text(actionText)
            }
        }
    }
}