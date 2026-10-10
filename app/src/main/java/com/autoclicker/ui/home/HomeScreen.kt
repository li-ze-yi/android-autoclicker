package com.autoclicker.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.autoclicker.MyApplication
import com.autoclicker.core.bus.EngineState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.collectLatest
import android.widget.Toast
import androidx.compose.material3.MaterialTheme.colorScheme

/**
 * 首页：单目标/多目标配置 + 重复策略 + 悬浮窗与播放控制。
 */
@Composable
fun HomeScreen(
    onNavigateToPermissions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val app = context.applicationContext as MyApplication
    val viewModel: HomeViewModel = viewModel(
        factory = androidx.lifecycle.viewmodel.initializer {
            HomeViewModel(app)
        },
    )
    val state by viewModel.state.collectAsState()

    // 从系统设置返回时重新检测权限
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.Event.ON_RESUME) {
            viewModel.onIntent(HomeIntent.Refresh)
        }
    }
    // 引擎一次性提示
    LaunchedEffect(Unit) {
        app.bus.eventMessages.collectLatest { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = "自动按键点击",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )

        // 权限未就绪提示
        if (!state.permissionsReady) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "需要先开启必要权限",
                        style = MaterialTheme.typography.titleMedium,
                        color = colorScheme.error,
                    )
                    Text("· 无障碍服务：执行点击/滑动\n· 悬浮窗：显示目标与控制面板")
                    Button(onClick = onNavigateToPermissions) { Text("去开启权限") }
                }
            }
        }

        // 模式切换
        ModeSwitch(state.mode) { mode ->
            viewModel.onIntent(HomeIntent.SwitchMode(mode))
        }

        // 重复策略
        PolicyCard(state) { intent -> viewModel.onIntent(intent) }

        // 悬浮窗开关
        OutlinedButton(
            onClick = { viewModel.onIntent(HomeIntent.ToggleOverlay) },
            modifier = Modifier.fillMaxWidth(),
            enabled = state.overlayReady,
        ) {
            Text(if (state.overlayShown) "收起悬浮窗" else "显示悬浮窗")
        }

        Spacer(Modifier.height(2.dp))

        // 开始 / 停止
        Button(
            onClick = { viewModel.onIntent(HomeIntent.TogglePlay) },
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            enabled = state.permissionsReady || state.busy,
        ) {
            Text(
                when (state.engineState) {
                    EngineState.Running -> "停止"
                    EngineState.Paused -> "停止"
                    EngineState.Recording -> "录制中…"
                    EngineState.Idle -> "开始"
                },
                style = MaterialTheme.typography.titleMedium,
            )
        }
        if (!state.permissionsReady && !state.busy) {
            Text(
                "开启无障碍与悬浮窗权限后即可开始",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

/** 单目标/多目标分段切换 */
@Composable
private fun ModeSwitch(mode: ConfigureMode, onChange: (ConfigureMode) -> Unit) {
    val options = listOf(ConfigureMode.SINGLE to "单目标", ConfigureMode.MULTI to "多目标")
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = mode == value,
                onClick = { onChange(value) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
            ) { Text(label) }
        }
    }
}

/** 重复策略卡片 */
@Composable
private fun PolicyCard(state: HomeUiState, onIntent: (HomeIntent) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("重复方式", style = MaterialTheme.typography.titleMedium)

            PolicyOption(
                selected = state.policyKind == PolicyKind.COUNT,
                title = "循环指定次数",
                onClick = { onIntent(HomeIntent.SwitchPolicy(PolicyKind.COUNT)) },
            ) {
                NumberField(
                    value = state.loopCount.toString(),
                    label = "循环次数",
                    enabled = state.policyKind == PolicyKind.COUNT,
                    onValue = { v ->
                        v.toIntOrNull()?.let { onIntent(HomeIntent.SetLoopCount(it)) }
                    },
                )
            }

            PolicyOption(
                selected = state.policyKind == PolicyKind.DURATION,
                title = "运行指定时长",
                onClick = { onIntent(HomeIntent.SwitchPolicy(PolicyKind.DURATION)) },
            ) {
                val seconds = state.runDurationMs / 1000
                NumberField(
                    value = seconds.toString(),
                    label = "运行秒数",
                    enabled = state.policyKind == PolicyKind.DURATION,
                    onValue = { v ->
                        v.toLongOrNull()?.let { onIntent(HomeIntent.SetDurationMs(it * 1000)) }
                    },
                )
            }

            PolicyOption(
                selected = state.policyKind == PolicyKind.UNTIL_STOPPED,
                title = "一直运行，直到手动停止",
                onClick = { onIntent(HomeIntent.SwitchPolicy(PolicyKind.UNTIL_STOPPED)) },
            )
        }
    }
}

@Composable
private fun PolicyOption(
    selected: Boolean,
    title: String,
    onClick: () -> Unit,
    content: @Composable (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(title, modifier = Modifier.padding(start = 4.dp))
    }
    if (selected && content != null) {
        Row(Modifier.padding(start = 36.dp, end = 8.dp)) { content() }
    }
}

@Composable
private fun NumberField(
    value: String,
    label: String,
    enabled: Boolean,
    onValue: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}
