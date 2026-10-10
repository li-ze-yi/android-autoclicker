package com.autoclicker.ui.permission

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.autoclicker.core.permission.PermissionChecker

/**
 * 权限页：展示各权限状态并提供跳转。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var hint by remember { mutableStateOf<String?>(null) }

    val accessibility = remember(tick) { PermissionChecker.isAccessibilityEnabled(context) }
    val overlay = remember(tick) { PermissionChecker.isOverlayGranted(context) }
    val notification = remember(tick) { PermissionChecker.isNotificationGranted(context) }
    val battery = remember(tick) { PermissionChecker.isBatteryOptimizationIgnored(context) }
    val screenCapture = remember(tick) { PermissionChecker.isScreenCaptureReady() }

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { tick++ }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("权限") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { tick++ }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
        ) {
            PermissionRow(
                title = "无障碍服务",
                granted = accessibility,
                actionLabel = "去设置",
            ) {
                safeStart(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) { hint = it }
            }
            PermissionRow(
                title = "悬浮窗",
                granted = overlay,
                actionLabel = "去设置",
            ) {
                safeStart(
                    context,
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
                ) { hint = it }
            }
            PermissionRow(
                title = "通知",
                granted = notification,
                actionLabel = "授权",
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    safeStart(
                        context,
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                    ) { hint = it }
                }
            }
            PermissionRow(
                title = "电池优化白名单",
                granted = battery,
                actionLabel = "去设置",
            ) {
                val intent = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}"),
                )
                val ok = safeStart(context, intent) { hint = it }
                if (!ok) {
                    safeStart(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) { hint = it }
                }
            }
            PermissionRow(
                title = "屏幕采集",
                granted = screenCapture,
                actionLabel = "检测",
            ) {
                tick++
                if (!screenCapture) {
                    hint = "请在「运行控制台」中发起屏幕采集授权"
                }
            }
            val message = hint
            if (message != null) {
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    granted: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    if (granted) "已开启" else "未开启",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (granted) Color(0xFF12B76A) else MaterialTheme.colorScheme.error,
                )
            }
            OutlinedButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/** 安全启动系统设置页，失败时返回 false 并上报提示。 */
private fun safeStart(context: Context, intent: Intent, onError: (String) -> Unit): Boolean {
    return try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        onError("无法打开设置页：${e.message}")
        false
    }
}