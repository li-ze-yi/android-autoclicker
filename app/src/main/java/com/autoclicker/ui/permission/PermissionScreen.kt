package com.autoclicker.ui.permission

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import com.autoclicker.core.permissions.PermissionChecker
import com.autoclicker.core.permissions.PermissionKind
import com.autoclicker.core.permissions.PermissionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ==================== MVI：状态 / 事件 ====================

/**
 * 权限页 UI 状态（单一数据源）。
 *
 * @property permissions 四项权限的最新快照
 */
data class PermissionUiState(
    val permissions: PermissionState = PermissionState()
) {
    /** 四项权限是否全部就绪 */
    val allGranted: Boolean get() = permissions.allGranted
}

/** 权限页用户意图（事件）：Screen 只负责把交互转成意图发给 ViewModel */
sealed interface PermissionIntent {
    /** 手动重新检测全部权限 */
    data object Refresh : PermissionIntent

    /** 跳转某项权限对应的系统设置页 */
    data class OpenSettings(val kind: PermissionKind) : PermissionIntent
}

// ==================== ViewModel ====================

/**
 * 权限页 ViewModel：持有唯一 [StateFlow]，在 IO 线程做权限快照（NFR-1，避免主线程阻塞）。
 */
class PermissionViewModel(
    private val checker: PermissionChecker
) : ViewModel() {

    private val _uiState = MutableStateFlow(PermissionUiState())
    val uiState: StateFlow<PermissionUiState> = _uiState.asStateFlow()

    init {
        // 首次进入页面立即检测一次
        refresh()
    }

    /** Screen 唯一事件入口 */
    fun onIntent(intent: PermissionIntent) {
        when (intent) {
            PermissionIntent.Refresh -> refresh()
            is PermissionIntent.OpenSettings -> checker.openSettings(intent.kind)
        }
    }

    /** 后台线程重新拉取四项权限快照并更新状态 */
    fun refresh() {
        viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) { checker.snapshot() }
            _uiState.update { it.copy(permissions = snapshot) }
        }
    }
}

/**
 * 权限页 ViewModel 工厂：用应用级 Context 构造 [PermissionChecker]（内部取 applicationContext）。
 */
private class PermissionViewModelFactory(
    private val appContext: Context
) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return PermissionViewModel(PermissionChecker(appContext)) as T
    }
}

/** 权限项的展示文案（名称 + 一句话用途） */
private data class PermissionItemText(val title: String, val purpose: String)

private fun PermissionKind.display(): PermissionItemText = when (this) {
    PermissionKind.Accessibility -> PermissionItemText(
        title = "无障碍服务",
        purpose = "自动派发点击、滑动手势，是自动点击的核心通道"
    )
    PermissionKind.Overlay -> PermissionItemText(
        title = "悬浮窗",
        purpose = "显示悬浮球和控制面板，随时开始、暂停或停止"
    )
    PermissionKind.Notification -> PermissionItemText(
        title = "通知",
        purpose = "运行时显示状态通知，定时任务到点时提醒"
    )
    PermissionKind.ExactAlarm -> PermissionItemText(
        title = "精确闹钟",
        purpose = "保证定时触发在设定时间准时执行"
    )
}

// ==================== Screen ====================

/**
 * 权限引导页（FR-1，AC-1 / TR-14.1）。
 *
 * 纯 MVI：只渲染 [PermissionUiState]、把交互以 [PermissionIntent] 上抛。
 * 页面 ON_RESUME 时自动重新检测——用户从系统设置授权后返回，状态实时更新，无需重启 App。
 *
 * 导航接入（AppNavHost / MainActivity）由主 agent 统一完成，本文件不做改动。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionScreen(
    modifier: Modifier = Modifier,
    viewModel: PermissionViewModel = permissionViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    // 监听生命周期：每次 onResume（含从系统设置返回）都重新快照
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.onIntent(PermissionIntent.Refresh)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(title = { Text("权限设置") })
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // 顶部说明：权限失效教育（App 被杀后无障碍可能被收回）
            Text(
                text = "自动按键点击需要以下权限才能工作。App 被系统杀死后，" +
                    "无障碍权限可能被系统收回，重新开启即可恢复使用。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))

            // 四项权限列表
            PermissionKind.entries.forEach { kind ->
                val granted = state.permissions.isGranted(kind)
                PermissionItemCard(
                    kind = kind,
                    granted = granted,
                    onEnableClick = {
                        viewModel.onIntent(PermissionIntent.OpenSettings(kind))
                    }
                )
                Spacer(Modifier.height(12.dp))
            }

            // 就绪提示
            if (state.allGranted) {
                Text(
                    text = "所有权限已就绪，返回首页即可开始使用。",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF2E7D32)
                )
                Spacer(Modifier.height(12.dp))
            }

            // 底部"重新检测"
            Button(
                onClick = { viewModel.onIntent(PermissionIntent.Refresh) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("重新检测")
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 单个权限项卡片：状态图标 + 名称/用途 + 未授予时的"去开启"按钮 */
@Composable
private fun PermissionItemCard(
    kind: PermissionKind,
    granted: Boolean,
    onEnableClick: () -> Unit
) {
    val text = kind.display()
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 图标：已授予=绿色对勾；未授予=灰色
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = if (granted) "已开启" else "未开启",
                tint = if (granted) Color(0xFF2E7D32) else Color.Gray,
                modifier = Modifier.size(32.dp)
            )

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = text.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = text.purpose,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 未授予时才显示"去开启"
            if (!granted) {
                Spacer(Modifier.width(8.dp))
                Button(onClick = onEnableClick) {
                    Text("去开启")
                }
            }
        }
    }
}

/**
 * 获取权限页 ViewModel。单独封装便于 Screen 默认参数与导航宿主复用。
 */
@Composable
private fun permissionViewModel(): PermissionViewModel {
    val context = LocalContext.current
    return viewModel(
        factory = PermissionViewModelFactory(context.applicationContext)
    )
}
