package com.autoclicker.ui.permission

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import com.autoclicker.core.util.PermissionChecker

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
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
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