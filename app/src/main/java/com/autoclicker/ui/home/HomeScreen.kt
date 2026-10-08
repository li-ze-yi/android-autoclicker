package com.autoclicker.ui.home

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.autoclicker.R
import com.autoclicker.core.overlay.OverlayService
import com.autoclicker.core.recorder.ScriptRecorder
import com.autoclicker.core.script.ScriptRepository
import com.autoclicker.core.util.PermissionChecker
import com.autoclicker.ui.Routes

@Composable
fun HomeScreen(onNavigate: (String) -> Unit) {
    val context = LocalContext.current

    var accessibilityOn by remember { mutableStateOf(false) }
    var overlayOn by remember { mutableStateOf(false) }
    var notificationsOn by remember { mutableStateOf(false) }
    var batteryIgnored by remember { mutableStateOf(false) }
    var overlayServiceRunning by remember { mutableStateOf(OverlayService.isRunning) }

    val isRecording by ScriptRecorder.isRecording.collectAsState()
    val stepCount by ScriptRecorder.stepCount.collectAsState()

    val refresh: () -> Unit = {
        accessibilityOn = PermissionChecker.isAccessibilityEnabled(context)
        overlayOn = PermissionChecker.isOverlayGranted(context)
        notificationsOn = PermissionChecker.areNotificationsGranted(context)
        batteryIgnored = PermissionChecker.isIgnoringBatteryOptimizations(context)
        overlayServiceRunning = OverlayService.isRunning
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineSmall
        )

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onNavigate(Routes.PERMISSIONS) }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("运行环境", style = MaterialTheme.typography.titleMedium)
                StatusLine("无障碍服务", accessibilityOn)
                StatusLine("悬浮窗", overlayOn)
                StatusLine("通知", notificationsOn)
                StatusLine("电池优化", batteryIgnored)
                Text(
                    text = "点击查看并开启权限",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("悬浮窗控制面板", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = if (overlayServiceRunning) "已开启" else "已关闭",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    checked = overlayServiceRunning,
                    onCheckedChange = { checked ->
                        if (checked) {
                            if (!PermissionChecker.isOverlayGranted(context)) {
                                PermissionChecker.openOverlaySettings(context)
                            } else {
                                OverlayService.start(context)
                                overlayServiceRunning = true
                            }
                        } else {
                            OverlayService.stop(context)
                            overlayServiceRunning = false
                        }
                    }
                )
            }
        }

        Button(
            onClick = { onNavigate(Routes.SCRIPTS) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("脚本库")
        }

        OutlinedButton(
            onClick = { onNavigate(Routes.PERMISSIONS) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("权限中心")
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("录制", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = if (isRecording) "录制中，已记录 $stepCount 步" else "未在录制",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (isRecording) {
                    Button(
                        onClick = {
                            val script = ScriptRecorder.stop()
                            if (script != null) {
                                ScriptRepository.get(context).save(script)
                                Toast.makeText(
                                    context,
                                    "已保存脚本：${script.name}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                Toast.makeText(
                                    context,
                                    "没有录制到任何步骤",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("结束录制")
                    }
                } else {
                    Button(
                        onClick = {
                            ScriptRecorder.start()
                            Toast.makeText(
                                context,
                                "开始录制，请操作目标应用",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("开始录制")
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusLine(label: String, enabled: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, modifier = Modifier.weight(1f))
        Text(
            text = if (enabled) "已开启" else "未开启",
            color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
    }
}