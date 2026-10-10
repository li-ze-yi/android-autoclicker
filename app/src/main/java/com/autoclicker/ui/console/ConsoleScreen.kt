package com.autoclicker.ui.console

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.autoclicker.core.bus.LogLevel
import com.autoclicker.core.bus.PlaybackState
import com.autoclicker.core.bus.RuntimeBus
import com.autoclicker.di.ServiceLocator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 运行控制台：实时展示运行日志、播放状态与当前执行步骤，支持清空日志与停止播放。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsoleScreen(onBack: () -> Unit) {
    val logs by RuntimeBus.logs.collectAsState()
    val state by RuntimeBus.state.collectAsState()
    val currentStep by RuntimeBus.currentStep.collectAsState()
    val listState = rememberLazyListState()
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("运行控制台") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = { RuntimeBus.clearLogs() }) { Text("清空日志") }
                    TextButton(onClick = { ServiceLocator.player?.stop() }) { Text("停止") }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                Text(
                    text = "状态：${playbackLabel(state)}",
                    style = MaterialTheme.typography.titleMedium,
                )
                val step = currentStep
                if (step != null) {
                    Text(
                        text = "${step.scriptName} · 第 ${step.stepIndex + 1}/${step.stepTotal} 步 · ${step.description}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            HorizontalDivider()
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(logs, key = { it.id }) { entry ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = timeFormat.format(Date(entry.timeMs)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = entry.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = logColor(entry.level),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun logColor(level: LogLevel) = when (level) {
    LogLevel.ERROR -> MaterialTheme.colorScheme.error
    LogLevel.WARN -> MaterialTheme.colorScheme.tertiary
    LogLevel.SUCCESS -> MaterialTheme.colorScheme.primary
    LogLevel.INFO, LogLevel.DEBUG -> MaterialTheme.colorScheme.onSurface
}

private fun playbackLabel(state: PlaybackState): String = when (state) {
    PlaybackState.IDLE -> "空闲"
    PlaybackState.RUNNING -> "运行中"
    PlaybackState.PAUSED -> "已暂停"
    PlaybackState.STOPPED -> "已停止"
    PlaybackState.ERROR -> "出错"
}