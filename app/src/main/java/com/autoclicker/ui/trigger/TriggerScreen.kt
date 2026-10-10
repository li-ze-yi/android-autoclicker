@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.autoclicker.ui.trigger

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.autoclicker.MyApplication
import com.autoclicker.core.data.scripts.ScriptFileRepository
import com.autoclicker.core.permissions.PermissionChecker
import com.autoclicker.core.trigger.Trigger
import com.autoclicker.core.trigger.TriggerScheduler
import com.autoclicker.core.trigger.TriggerStore
import com.autoclicker.domain.model.Script
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// =================================================================================
// 页面入口
// =================================================================================

/**
 * 定时触发页（FR-9 / Task 13）。
 *
 * 功能：
 * - 定时条目列表：脚本名 + 触发时间（单次/每天/每周）；
 * - 新建：选择脚本 → 选择日期时间（系统 DatePicker/TimePicker）→ 可选重复；
 * - 开关：关闭即取消系统闹钟，重新打开自动重注册（过期单次会被拦下）；
 * - 删除：取消闹钟并删除持久化条目。
 *
 * 精确闹钟权限（API 31+）或通知权限缺失时，顶部展示引导卡片。
 * MVI：本页只渲染 [TriggerUiState]、派发 [TriggerIntent]。
 * 导航接入由主 agent 完成（本文件不依赖 NavController）。
 */
@Composable
fun TriggerScreen(modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as MyApplication
    val viewModel: TriggerViewModel = viewModel(
        factory = remember(app) {
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    @Suppress("UNCHECKED_CAST")
                    return TriggerViewModel(
                        app = app,
                        store = TriggerStore(app),
                        scriptRepository = ScriptFileRepository(app),
                        scheduler = TriggerScheduler(app),
                        checker = PermissionChecker(app),
                    ) as T
                }
            }
        }
    )
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // 从系统设置返回（ON_RESUME）时自动刷新权限与列表
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.onIntent(TriggerIntent.Refresh)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 一次性中文提示（消息带序号，相同文本也能再次弹出）
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message.text)
        viewModel.onIntent(TriggerIntent.MessageShown)
    }

    // 通知权限运行时申请（Android 13+）
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        viewModel.onIntent(TriggerIntent.Refresh)
    }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("定时触发") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = { viewModel.onIntent(TriggerIntent.ShowCreate) }) {
                Icon(Icons.Filled.Add, contentDescription = "新建定时任务")
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            // 权限引导区
            if (!state.exactAlarmGranted) {
                PermissionBanner(
                    text = "未授予「精确闹钟」权限，定时任务可能无法准时触发。",
                    actionText = "去授权",
                    onAction = { PermissionChecker(app).openExactAlarmSettings() },
                )
            }
            if (!state.notificationsEnabled) {
                PermissionBanner(
                    text = "通知权限未开启，到点将收不到提醒。",
                    actionText = "去开启",
                    onAction = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermissionLauncher.launch(
                                Manifest.permission.POST_NOTIFICATIONS
                            )
                        } else {
                            PermissionChecker(app).openAppNotificationSettings()
                        }
                    },
                )
            }

            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    state.loading ->
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

                    state.items.isEmpty() ->
                        EmptyTriggers(modifier = Modifier.align(Alignment.Center))

                    else ->
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(state.items, key = { it.trigger.id }) { item ->
                                TriggerCard(
                                    item = item,
                                    onToggle = {
                                        viewModel.onIntent(TriggerIntent.Toggle(item))
                                    },
                                    onDelete = {
                                        viewModel.onIntent(TriggerIntent.RequestDelete(item))
                                    },
                                )
                            }
                        }
                }
            }
        }
    }

    // 弹窗层
    when (val dialog = state.dialog) {
        null -> Unit

        TriggerDialog.Create ->
            CreateTriggerDialog(
                scripts = state.scripts,
                onDismiss = { viewModel.onIntent(TriggerIntent.DismissDialog) },
                onCreate = { scriptId, atMs, repeatMs ->
                    viewModel.onIntent(
                        TriggerIntent.Create(scriptId, atMs, repeatMs)
                    )
                },
            )

        is TriggerDialog.ConfirmDelete ->
            AlertDialog(
                onDismissRequest = { viewModel.onIntent(TriggerIntent.DismissDialog) },
                title = { Text("删除定时任务") },
                text = {
                    Text("确定删除「${dialog.item.scriptName}」的定时任务吗？此操作不可撤销。")
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.onIntent(TriggerIntent.ConfirmDelete(dialog.item))
                    }) { Text("删除") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        viewModel.onIntent(TriggerIntent.DismissDialog)
                    }) { Text("取消") }
                },
            )
    }
}

// =================================================================================
// 子组件
// =================================================================================

/** 单个定时条目卡片：脚本名、触发描述、启用开关、删除按钮 */
@Composable
private fun TriggerCard(
    item: TriggerItem,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.scriptName,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = describeTrigger(item.trigger),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = item.trigger.enabled,
                onCheckedChange = { onToggle() },
            )
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "删除定时任务")
            }
        }
    }
}

/** 权限引导横幅卡片 */
@Composable
private fun PermissionBanner(
    text: String,
    actionText: String,
    onAction: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onAction) { Text(actionText) }
        }
    }
}

/** 空列表状态 */
@Composable
private fun EmptyTriggers(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("还没有定时任务", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "点击右下角 ＋，选择脚本和时间，到点会发通知提醒你执行。\n" +
                "受系统限制，闹钟无法保证精确到秒。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 新建定时任务弹窗：选脚本 + 选日期时间 + 重复方式。
 * 时间默认填「10 分钟后」，可点击时间框重新选择。
 */
@Composable
private fun CreateTriggerDialog(
    scripts: List<Script>,
    onDismiss: () -> Unit,
    onCreate: (scriptId: String, triggerAtMs: Long, repeatIntervalMs: Long) -> Unit,
) {
    val context = LocalContext.current

    var selectedScript by remember { mutableStateOf<Script?>(null) }
    var dropdownExpanded by remember { mutableStateOf(false) }
    var triggerAtMs by remember {
        mutableLongStateOf(System.currentTimeMillis() + TEN_MINUTES_MS)
    }
    var repeatIntervalMs by remember { mutableLongStateOf(0L) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建定时任务") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // 脚本选择（只读下拉框）
                ExposedDropdownMenuBox(
                    expanded = dropdownExpanded,
                    onExpandedChange = { dropdownExpanded = it },
                ) {
                    OutlinedTextField(
                        value = selectedScript?.name.orEmpty(),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("选择脚本") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded)
                        },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth(),
                    )
                    ExposedDropdownMenu(
                        expanded = dropdownExpanded,
                        onDismissRequest = { dropdownExpanded = false },
                    ) {
                        scripts.forEach { script ->
                            DropdownMenuItem(
                                text = { Text(script.name) },
                                onClick = {
                                    selectedScript = script
                                    dropdownExpanded = false
                                },
                            )
                        }
                    }
                }

                // 触发时间：点击依次弹出系统日期、时间选择器
                OutlinedTextField(
                    value = dateTimeFormatter.format(Date(triggerAtMs)),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("触发时间") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showDateTimePickers(context) { pickedMs ->
                                triggerAtMs = pickedMs
                            }
                        },
                )

                // 重复方式
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RepeatOption.entries.forEach { option ->
                        FilterChip(
                            selected = repeatIntervalMs == option.intervalMs,
                            onClick = { repeatIntervalMs = option.intervalMs },
                            label = { Text(option.label) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selectedScript != null &&
                    triggerAtMs > System.currentTimeMillis(),
                onClick = {
                    selectedScript?.let {
                        onCreate(it.id, triggerAtMs, repeatIntervalMs)
                    }
                },
            ) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 重复方式选项（间隔毫秒；0 = 单次） */
private enum class RepeatOption(val label: String, val intervalMs: Long) {
    ONCE("单次", 0L),
    DAILY("每天", DAY_MS),
    WEEKLY("每周", WEEK_MS),
}

/** 依次启动系统日期选择器、时间选择器，两者完成后回调最终毫秒时间 */
private fun showDateTimePickers(
    context: android.content.Context,
    onPicked: (Long) -> Unit,
) {
    val calendar = Calendar.getInstance()
    DatePickerDialog(
        context,
        { _, year, month, dayOfMonth ->
            TimePickerDialog(
                context,
                { _, hourOfDay, minute ->
                    val picked = Calendar.getInstance().apply {
                        set(year, month, dayOfMonth, hourOfDay, minute, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    onPicked(picked.timeInMillis)
                },
                calendar.get(Calendar.HOUR_OF_DAY),
                calendar.get(Calendar.MINUTE),
                true, // 24 小时制
            ).show()
        },
        calendar.get(Calendar.YEAR),
        calendar.get(Calendar.MONTH),
        calendar.get(Calendar.DAY_OF_MONTH),
    ).show()
}

/** 生成条目时间文案：单次显示完整日期时间，每天/每周显示周期描述 */
private fun describeTrigger(trigger: Trigger): String {
    val date = Date(trigger.triggerAtMs)
    return when (trigger.repeatIntervalMs) {
        0L -> dateTimeFormatter.format(date)
        DAY_MS -> "每天 " + timeFormatter.format(date)
        WEEK_MS -> "每" + weekdayFormatter.format(date) + " " + timeFormatter.format(date)
        else -> "每 ${trigger.repeatIntervalMs / HOUR_MS} 小时重复"
    }
}

// =================================================================================
// MVI：状态 / 意图 / 弹窗
// =================================================================================

/** 定时页 UI 状态（单一数据源） */
data class TriggerUiState(
    val loading: Boolean = true,
    /** 可选脚本列表 */
    val scripts: List<Script> = emptyList(),
    /** 定时条目（已附带脚本展示名） */
    val items: List<TriggerItem> = emptyList(),
    /** 精确闹钟权限（API 31+） */
    val exactAlarmGranted: Boolean = true,
    /** 通知总开关 */
    val notificationsEnabled: Boolean = true,
    val dialog: TriggerDialog? = null,
    val message: TriggerMessage? = null,
)

/** 列表行模型：定时条目 + 展示用脚本名 */
data class TriggerItem(
    val trigger: Trigger,
    val scriptName: String,
)

/** 一次性提示消息（带序号，相同文本也能重新触发 Snackbar） */
data class TriggerMessage(
    val seq: Long,
    val text: String,
)

/** 弹窗类型 */
sealed interface TriggerDialog {
    data object Create : TriggerDialog
    data class ConfirmDelete(val item: TriggerItem) : TriggerDialog
}

/** 用户意图 */
sealed interface TriggerIntent {
    data object Refresh : TriggerIntent
    data object ShowCreate : TriggerIntent
    data object DismissDialog : TriggerIntent

    /** 确认新建 */
    data class Create(
        val scriptId: String,
        val triggerAtMs: Long,
        val repeatIntervalMs: Long,
    ) : TriggerIntent

    /** 开关切换 */
    data class Toggle(val item: TriggerItem) : TriggerIntent

    /** 删除第一步：弹确认框 */
    data class RequestDelete(val item: TriggerItem) : TriggerIntent

    /** 删除第二步：真正删除 */
    data class ConfirmDelete(val item: TriggerItem) : TriggerIntent

    data object MessageShown : TriggerIntent
}

// =================================================================================
// ViewModel
// =================================================================================

/**
 * 定时页 ViewModel：持有单一 [TriggerUiState]。
 *
 * 所有文件 IO 与闹钟操作均经 viewModelScope + 仓库/调度器完成；
 * 闹钟注册失败时回滚已写入的条目，保证「文件状态」与「系统闹钟」一致。
 */
class TriggerViewModel(
    private val app: MyApplication,
    private val store: TriggerStore,
    private val scriptRepository: ScriptFileRepository,
    private val scheduler: TriggerScheduler,
    private val checker: PermissionChecker,
) : ViewModel() {

    private val _state = MutableStateFlow(TriggerUiState())
    val state: StateFlow<TriggerUiState> = _state.asStateFlow()

    /** 消息自增序号 */
    private var messageSeq: Long = 0L

    init {
        refresh()
    }

    fun onIntent(intent: TriggerIntent) {
        when (intent) {
            TriggerIntent.Refresh -> refresh()
            TriggerIntent.ShowCreate -> showCreate()
            TriggerIntent.DismissDialog ->
                _state.update { it.copy(dialog = null) }

            is TriggerIntent.Create -> create(intent.scriptId, intent.triggerAtMs, intent.repeatIntervalMs)
            is TriggerIntent.Toggle -> toggle(intent.item)
            is TriggerIntent.RequestDelete ->
                _state.update { it.copy(dialog = TriggerDialog.ConfirmDelete(intent.item)) }

            is TriggerIntent.ConfirmDelete -> delete(intent.item)

            TriggerIntent.MessageShown ->
                _state.update { it.copy(message = null) }
        }
    }

    /** 打开新建弹窗前检查是否有可用脚本 */
    private fun showCreate() {
        if (_state.value.scripts.isEmpty()) {
            postMessage("请先在「脚本库」中创建脚本，再设置定时任务")
            return
        }
        _state.update { it.copy(dialog = TriggerDialog.Create) }
    }

    /** 全量刷新：脚本、条目、两项权限状态 */
    private fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val scripts = runCatching { scriptRepository.list() }.getOrDefault(emptyList())
            val triggers = runCatching { store.list() }.getOrDefault(emptyList())
            val nameById = scripts.associate { it.id to it.name }
            val items = triggers.map { trigger ->
                TriggerItem(
                    trigger = trigger,
                    scriptName = nameById[trigger.scriptId] ?: "未知脚本（可能已删除）",
                )
            }
            _state.update {
                it.copy(
                    loading = false,
                    scripts = scripts,
                    items = items,
                    exactAlarmGranted = checker.canScheduleExactAlarms(),
                    notificationsEnabled = checker.notificationsEnabled(),
                )
            }
        }
    }

    /** 新建条目：先持久化再注册闹钟，注册失败回滚删除 */
    private fun create(scriptId: String, triggerAtMs: Long, repeatIntervalMs: Long) {
        viewModelScope.launch {
            if (triggerAtMs <= System.currentTimeMillis()) {
                postMessage("触发时间必须晚于当前时间")
                return@launch
            }
            if (!scheduler.canScheduleExact()) {
                postMessage("请先授予精确闹钟权限")
                return@launch
            }
            val trigger = Trigger(
                id = store.newId(),
                scriptId = scriptId,
                triggerAtMs = triggerAtMs,
                repeatIntervalMs = repeatIntervalMs,
                enabled = true,
            )
            store.upsert(trigger)
            val result = runCatching { scheduler.schedule(trigger) }
            if (result.isFailure) {
                // 闹钟没注册成功，回滚文件，避免出现「列表里有、系统没闹钟」的假条目
                store.delete(trigger.id)
                postMessage(result.exceptionOrNull()?.message ?: "定时设置失败，请重试")
                return@launch
            }
            _state.update { it.copy(dialog = null) }
            reloadItems()
        }
    }

    /** 开关切换：关闭即取消闹钟；打开时校验时间并重新注册 */
    private fun toggle(item: TriggerItem) {
        viewModelScope.launch {
            val trigger = item.trigger
            if (trigger.enabled) {
                // 关：取消系统闹钟，文件保留为 disabled
                scheduler.cancel(trigger.id)
                store.upsert(trigger.copy(enabled = false))
                reloadItems()
                return@launch
            }

            // 开：权限与时间校验
            if (!scheduler.canScheduleExact()) {
                postMessage("请先授予精确闹钟权限")
                return@launch
            }
            val now = System.currentTimeMillis()
            var nextAtMs = trigger.triggerAtMs
            when {
                !trigger.isRepeating && nextAtMs <= now -> {
                    postMessage("该单次任务的时间已过，请删除后重新新建")
                    return@launch
                }
                trigger.isRepeating && nextAtMs <= now -> {
                    // 重复任务停开期间错过若干周期：推进到下一个未来时刻
                    while (nextAtMs <= now) {
                        nextAtMs += trigger.repeatIntervalMs
                    }
                }
            }
            val enabled = trigger.copy(triggerAtMs = nextAtMs, enabled = true)
            store.upsert(enabled)
            val result = runCatching { scheduler.schedule(enabled) }
            if (result.isFailure) {
                // 注册失败回滚为关闭态，保持状态自洽（NFR-3）
                store.upsert(trigger.copy(enabled = false))
                postMessage(result.exceptionOrNull()?.message ?: "开启失败，请重试")
                return@launch
            }
            reloadItems()
        }
    }

    /** 删除：取消闹钟 + 删除文件 */
    private fun delete(item: TriggerItem) {
        viewModelScope.launch {
            scheduler.cancel(item.trigger.id)
            store.delete(item.trigger.id)
            _state.update { it.copy(dialog = null) }
            reloadItems()
        }
    }

    /** 仅重新加载条目列表（写操作完成后调用，脚本与权限状态不变） */
    private fun reloadItems() {
        viewModelScope.launch {
            val triggers = runCatching { store.list() }.getOrDefault(emptyList())
            val nameById = _state.value.scripts.associate { it.id to it.name }
            val items = triggers.map { trigger ->
                TriggerItem(
                    trigger = trigger,
                    scriptName = nameById[trigger.scriptId] ?: "未知脚本（可能已删除）",
                )
            }
            _state.update { it.copy(items = items) }
        }
    }

    private fun nextSeq(): Long {
        messageSeq += 1L
        return messageSeq
    }

    private fun postMessage(text: String) {
        _state.update { it.copy(message = TriggerMessage(nextSeq(), text)) }
    }

}

// 时间格式（共享实例，Locale 跟随系统）
private val dateTimeFormatter = SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.getDefault())
private val timeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())
private val weekdayFormatter = SimpleDateFormat("EEEE", Locale.getDefault())

// 时间/间隔常量
private const val TEN_MINUTES_MS = 10L * 60L * 1000L
private const val HOUR_MS = 3_600_000L
private const val DAY_MS = 86_400_000L
private const val WEEK_MS = 604_800_000L
