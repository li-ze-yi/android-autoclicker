package com.autoclicker.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.autoclicker.data.settings.AppSettings
import com.autoclicker.core.permission.PermissionChecker
import com.autoclicker.di.ServiceLocator
import com.autoclicker.service.overlay.OverlayService
import com.autoclicker.ui.editor.NumberFieldRow
import kotlinx.coroutines.launch

/**
 * 我的（设置）页：应用设置读写 + 权限入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onOpenPermissions: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by ServiceLocator.settings.settings.collectAsState(initial = AppSettings())

    var delayText by remember(settings.defaultStepDelayMs) {
        mutableStateOf(settings.defaultStepDelayMs.toString())
    }
    var linesText by remember(settings.logPanelMaxLines) {
        mutableStateOf(settings.logPanelMaxLines.toString())
    }
    var opacity by remember(settings.overlayOpacity) {
        mutableStateOf(settings.overlayOpacity)
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("我的") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
        ) {
            // 悬浮球
            SettingCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("悬浮球", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "开启后显示悬浮控制入口",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.floatingBallEnabled,
                        onCheckedChange = { enabled ->
                            if (!enabled) {
                                scope.launch { ServiceLocator.settings.setFloatingBallEnabled(false) }
                                runCatching { OverlayService.stop(context) }
                            } else if (PermissionChecker.isOverlayGranted(context)) {
                                scope.launch { ServiceLocator.settings.setFloatingBallEnabled(true) }
                                runCatching { OverlayService.start(context) }
                            } else {
                                // 缺少悬浮窗权限：回滚开关并跳转授权页。
                                scope.launch { ServiceLocator.settings.setFloatingBallEnabled(false) }
                                Toast.makeText(
                                    context,
                                    "请先开启悬浮窗权限，已为你打开设置页",
                                    Toast.LENGTH_SHORT,
                                ).show()
                                runCatching {
                                    context.startActivity(
                                        PermissionChecker.settingsIntent(
                                            context,
                                            PermissionChecker.Kind.OVERLAY,
                                        ),
                                    )
                                }
                            }
                        },
                    )
                }
            }

            // 默认步骤延时
            SettingCard {
                Text("默认步骤延时（ms）", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NumberFieldRow(
                        label = "延时（ms）",
                        value = delayText,
                        onValueChange = { delayText = it },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val v = delayText.trim().toLongOrNull() ?: settings.defaultStepDelayMs
                            scope.launch { ServiceLocator.settings.setDefaultStepDelay(v) }
                        },
                    ) { Text("应用") }
                }
            }

            // 悬浮窗不透明度
            SettingCard {
                Text("悬浮窗不透明度（${(opacity * 100).toInt()}%）", style = MaterialTheme.typography.titleSmall)
                Slider(
                    value = opacity,
                    onValueChange = { opacity = it },
                    onValueChangeFinished = {
                        scope.launch { ServiceLocator.settings.setOverlayOpacity(opacity) }
                    },
                    valueRange = 0.3f..1f,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // 日志行数
            SettingCard {
                Text("日志最大行数", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NumberFieldRow(
                        label = "行数",
                        value = linesText,
                        onValueChange = { linesText = it },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val v = linesText.trim().toIntOrNull() ?: settings.logPanelMaxLines
                            scope.launch { ServiceLocator.settings.setLogPanelMaxLines(v.coerceAtLeast(10)) }
                        },
                    ) { Text("应用") }
                }
            }

            // 录制自动记录延时
            SettingCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("录制自动记录延时", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "录制时自动插入步骤间延时",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.autoRecordDelay,
                        onCheckedChange = { enabled ->
                            scope.launch { ServiceLocator.settings.setAutoRecordDelay(enabled) }
                        },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = onOpenPermissions,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("权限开启") }
        }
    }
}

@Composable
private fun SettingCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) { content() }
    }
}