@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.autoclicker.ui.templates

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.ScreenshotMonitor
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.autoclicker.core.data.templates.ImageTemplateMetadata
import com.autoclicker.core.data.templates.ImageTemplateRepository
import com.autoclicker.service.capture.CaptureCoordinator
import com.autoclicker.service.capture.CaptureException
import com.autoclicker.service.capture.CaptureRequester
import com.autoclicker.service.capture.ContinuousScreenSource
import com.autoclicker.service.capture.RegionPickerOverlay
import com.autoclicker.service.capture.ScreenCaptureService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// =================================================================================
// 页面入口
// =================================================================================

/**
 * 识图模板管理页（FR-7 / Task 11）。
 *
 * 能力：
 * - 模板列表：56dp 缩略图、名称、宽高；重命名、删除（二次确认）；
 * - 顶部「新建模板」：屏幕录制授权 → 服务抓全屏帧 → [RegionPickerOverlay]
 *   框选区域 → 裁剪入库（自动命名，可随后重命名）；
 * - 顶部「持续投屏」开关：为脚本中的 WaitImage 动作建立持续屏幕帧会话。
 *
 * @param captureRequester 可选的、已在 Activity onCreate（STARTED 之前）bind 好的
 *        授权控制器；为 null 时页面使用 Compose 的 rememberLauncherForActivityResult
 *        自行发起授权（与脚本库等页面一致，导航到达后也可用）。
 */
@Composable
fun TemplatesScreen(
    captureRequester: CaptureRequester? = null,
    modifier: Modifier = Modifier,
) {
    val appContext = LocalContext.current.applicationContext
    val viewModel: TemplatesViewModel = viewModel(
        factory = remember(appContext) {
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    @Suppress("UNCHECKED_CAST")
                    return TemplatesViewModel(
                        repository = ImageTemplateRepository(appContext),
                    ) as T
                }
            }
        },
    )
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 已抓取、待框选的全屏帧（必须在 launcher 之前声明：launcher 回调内会写入它）
    var pendingFrame by remember { mutableStateOf<Bitmap?>(null) }

    // 回到前台时刷新持续投屏状态（投屏可能已在系统侧结束）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.onIntent(TemplatesIntent.RefreshProjection)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 一次性中文提示：相同文本的新消息也能再次弹出（以 seq 区分）
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message.text)
        viewModel.onIntent(TemplatesIntent.MessageShown)
    }

    // -----------------------------------------------------------------------
    // 单次抓帧：未注入 captureRequester 时使用的 Compose 授权 launcher
    // -----------------------------------------------------------------------
    val frameAuthLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode != Activity.RESULT_OK || data == null) {
            viewModel.onIntent(
                TemplatesIntent.CaptureFailed("用户取消或拒绝了屏幕录制授权"),
            )
            return@rememberLauncherForActivityResult
        }
        // 复用既有一次性会话管线：登记授权 → 启动单次抓帧服务 → 等待全屏 Bitmap
        val deferred = CompletableDeferred<Bitmap>()
        CaptureCoordinator.attach(result.resultCode, data, deferred)
        try {
            ScreenCaptureService.start(context)
        } catch (t: Throwable) {
            CaptureCoordinator.failCurrent("无法启动屏幕采集服务：${t.message ?: "未知错误"}")
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val bitmap = try {
                deferred.await()
            } catch (e: CaptureException) {
                viewModel.onIntent(
                    TemplatesIntent.CaptureFailed(e.message ?: "屏幕采集失败，请重试"),
                )
                null
            }
            if (bitmap != null) pendingFrame = bitmap
        }
    }

    // -----------------------------------------------------------------------
    // 持续投屏授权 launcher
    // -----------------------------------------------------------------------
    val continuousLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode != Activity.RESULT_OK || data == null) {
            viewModel.onIntent(
                TemplatesIntent.CaptureFailed("用户取消或拒绝了屏幕录制授权"),
            )
            return@rememberLauncherForActivityResult
        }
        try {
            ContinuousScreenSource.start(context, result.resultCode, data)
            viewModel.onIntent(TemplatesIntent.RefreshProjection)
        } catch (t: Throwable) {
            viewModel.onIntent(
                TemplatesIntent.CaptureFailed("无法启动持续投屏：${t.message ?: "未知错误"}"),
            )
        }
    }

    // -----------------------------------------------------------------------
    // 「新建模板」授权门：VM 发请求序号 → UI 执行授权（requester 或自有 launcher）
    // -----------------------------------------------------------------------
    LaunchedEffect(state.authRequest) {
        val seq = state.authRequest ?: return@LaunchedEffect
        if (captureRequester != null) {
            try {
                captureRequester.request()
                pendingFrame = captureRequester.awaitBitmap()
            } catch (e: CaptureException) {
                viewModel.onIntent(
                    TemplatesIntent.CaptureFailed(e.message ?: "屏幕采集失败，请重试"),
                )
            }
        } else {
            val projectionManager =
                context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            runCatching { frameAuthLauncher.launch(projectionManager.createScreenCaptureIntent()) }
                .onFailure {
                    viewModel.onIntent(
                        TemplatesIntent.CaptureFailed("无法发起屏幕录制授权，请重试"),
                    )
                }
        }
        viewModel.onIntent(TemplatesIntent.AuthConsumed)
    }

    // -----------------------------------------------------------------------
    // 区域框选：存在待框选帧时挂载 RegionPickerOverlay
    // -----------------------------------------------------------------------
    val frame = pendingFrame
    if (frame != null) {
        if (!Settings.canDrawOverlays(context)) {
            // 框选悬浮层依赖悬浮窗权限：提示并丢弃该帧
            LaunchedEffect(frame) {
                viewModel.onIntent(
                    TemplatesIntent.CaptureFailed("框选区域需要悬浮窗权限，请先在权限页授予"),
                )
                frame.recycle()
                pendingFrame = null
            }
        } else {
            DisposableEffect(frame) {
                // 帧是否已被确认/取消回调消费（防止导航离开与回调双重回收）
                var consumed = false
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val overlay = RegionPickerOverlay(
                    context = context,
                    wm = wm,
                    onConfirm = { rect ->
                        consumed = true
                        val cropped = runCatching {
                            Bitmap.createBitmap(
                                frame,
                                rect.left,
                                rect.top,
                                rect.width(),
                                rect.height(),
                            )
                        }.getOrNull()
                        frame.recycle()
                        pendingFrame = null
                        if (cropped == null) {
                            viewModel.onIntent(
                                TemplatesIntent.CaptureFailed("裁剪模板失败，请重试"),
                            )
                        } else {
                            viewModel.onIntent(TemplatesIntent.StoreTemplate(cropped))
                        }
                    },
                    onCancel = {
                        consumed = true
                        frame.recycle()
                        pendingFrame = null
                    },
                )
                overlay.attach()
                onDispose {
                    overlay.detach()
                    // 页面在框选过程中离开：兜底回收帧
                    if (!consumed && !frame.isRecycled) {
                        frame.recycle()
                        pendingFrame = null
                    }
                }
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("识图模板") },
                actions = {
                    // 持续投屏开关（为 WaitImage 提供屏幕帧）
                    IconButton(onClick = {
                        if (ContinuousScreenSource.isActive()) {
                            ContinuousScreenSource.stop(context)
                            // 停止为异步投递，稍后再刷新状态
                            scope.launch {
                                delay(300)
                                viewModel.onIntent(TemplatesIntent.RefreshProjection)
                            }
                        } else {
                            val projectionManager =
                                context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                                    as MediaProjectionManager
                            runCatching {
                                continuousLauncher.launch(projectionManager.createScreenCaptureIntent())
                            }
                        }
                    }) {
                        Icon(
                            imageVector = Icons.Filled.ScreenshotMonitor,
                            contentDescription = if (state.projectionActive) "停止持续投屏" else "开始持续投屏",
                            tint = if (state.projectionActive) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    // 新建模板
                    IconButton(onClick = { viewModel.onIntent(TemplatesIntent.RequestCreate) }) {
                        Icon(Icons.Filled.Add, contentDescription = "新建模板")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            when {
                state.loading ->
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

                state.templates.isEmpty() ->
                    EmptyTemplates(
                        onCreate = { viewModel.onIntent(TemplatesIntent.RequestCreate) },
                        modifier = Modifier.align(Alignment.Center),
                    )

                else ->
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(state.templates, key = { it.id }) { template ->
                            // 进入卡片即请求缩略图（VM 内去重）
                            LaunchedEffect(template.id) {
                                viewModel.onIntent(TemplatesIntent.LoadThumbnail(template.id))
                            }
                            TemplateCard(
                                template = template,
                                thumbnail = state.thumbnails[template.id],
                                expanded = state.expandedId == template.id,
                                onToggleExpand = {
                                    viewModel.onIntent(TemplatesIntent.ToggleExpand(template))
                                },
                                onRename = {
                                    viewModel.onIntent(TemplatesIntent.ShowRenameDialog(template))
                                },
                                onDelete = {
                                    viewModel.onIntent(TemplatesIntent.RequestDelete(template))
                                },
                            )
                        }
                    }
            }
        }
    }

    // 弹窗层
    when (val dialog = state.dialog) {
        null -> Unit

        is TemplatesDialog.Rename ->
            NameInputDialog(
                title = "重命名模板",
                initialName = dialog.template.name,
                onConfirm = { viewModel.onIntent(TemplatesIntent.Rename(dialog.template, it)) },
                onDismiss = { viewModel.onIntent(TemplatesIntent.DismissDialog) },
            )

        is TemplatesDialog.ConfirmDelete ->
            AlertDialog(
                onDismissRequest = { viewModel.onIntent(TemplatesIntent.DismissDialog) },
                title = { Text("删除模板") },
                text = {
                    Text("确定删除模板「${dialog.template.name}」吗？此操作不可撤销。")
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.onIntent(TemplatesIntent.ConfirmDelete(dialog.template))
                    }) {
                        Text("删除")
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        viewModel.onIntent(TemplatesIntent.DismissDialog)
                    }) {
                        Text("取消")
                    }
                },
            )
    }
}

// =================================================================================
// 子组件
// =================================================================================

/** 单个模板卡片：缩略图 + 名称 + 尺寸，展开后出现重命名/删除操作 */
@Composable
private fun TemplateCard(
    template: ImageTemplateMetadata,
    thumbnail: Bitmap?,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 56dp 缩略图
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    if (thumbnail != null) {
                        // 注意：androidx.compose.foundation.Image 与 material 图标 Image
                        // 同名，故此处用全限定名，避免 import 冲突
                        androidx.compose.foundation.Image(
                            bitmap = androidx.compose.ui.graphics.asImageBitmap(thumbnail),
                            contentDescription = "模板预览",
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Image,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = template.name,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "${template.width}×${template.height} px",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onToggleExpand) {
                    Icon(
                        imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "收起操作" else "展开操作",
                    )
                }
            }
            AnimatedVisibility(visible = expanded) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(onClick = onRename) {
                        Icon(
                            Icons.Filled.DriveFileRenameOutline,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("重命名")
                    }
                    TextButton(onClick = onDelete) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("删除")
                    }
                }
            }
        }
    }
}

/** 空列表状态 */
@Composable
private fun EmptyTemplates(
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("还没有识图模板", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "授权屏幕录制后框选一块区域，即可保存为模板；脚本可用「等待识图结果」动作等它出现。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onCreate) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("新建模板")
        }
    }
}

/** 名称输入弹窗（重命名用），空白名称不可确认 */
@Composable
private fun NameInputDialog(
    title: String,
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("名称") },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank(),
            ) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

// =================================================================================
// MVI：状态 / 意图 / 弹窗
// =================================================================================

/** 模板管理页 UI 状态（单一数据源） */
data class TemplatesUiState(
    val loading: Boolean = true,
    val templates: List<ImageTemplateMetadata> = emptyList(),
    /** 模板缩略图：id → 位图（VM 统一管理回收） */
    val thumbnails: Map<String, Bitmap> = emptyList(),
    /** 当前展开操作区的模板 ID */
    val expandedId: String? = null,
    /** 持续投屏会话是否活动 */
    val projectionActive: Boolean = false,
    /** 新建模板授权请求序号（非 null 时 UI 执行授权流程） */
    val authRequest: Long? = null,
    val dialog: TemplatesDialog? = null,
    val message: TemplatesMessage? = null,
)

/** 一次性提示消息（带序号，相同文本也能重新触发 Snackbar） */
data class TemplatesMessage(
    val seq: Long,
    val text: String,
)

/** 弹窗类型 */
sealed interface TemplatesDialog {
    data class Rename(val template: ImageTemplateMetadata) : TemplatesDialog
    data class ConfirmDelete(val template: ImageTemplateMetadata) : TemplatesDialog
}

/** 用户意图 */
sealed interface TemplatesIntent {
    data object Refresh : TemplatesIntent
    data object RequestCreate : TemplatesIntent
    data object AuthConsumed : TemplatesIntent
    data class CaptureFailed(val message: String) : TemplatesIntent
    data class StoreTemplate(val bitmap: Bitmap) : TemplatesIntent
    data class ShowRenameDialog(val template: ImageTemplateMetadata) : TemplatesIntent
    data class Rename(val template: ImageTemplateMetadata, val name: String) : TemplatesIntent
    data class RequestDelete(val template: ImageTemplateMetadata) : TemplatesIntent
    data class ConfirmDelete(val template: ImageTemplateMetadata) : TemplatesIntent
    data class LoadThumbnail(val id: String) : TemplatesIntent
    data class ToggleExpand(val template: ImageTemplateMetadata) : TemplatesIntent
    data object DismissDialog : TemplatesIntent
    data object MessageShown : TemplatesIntent
    data object RefreshProjection : TemplatesIntent
}

// =================================================================================
// ViewModel
// =================================================================================

/**
 * 模板管理页 ViewModel：持有单一 [TemplatesUiState]，
 * 全部文件操作经 viewModelScope（仓库内部切 IO）发起。
 */
class TemplatesViewModel(
    private val repository: ImageTemplateRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(
        TemplatesUiState(projectionActive = ContinuousScreenSource.isActive()),
    )
    val state: StateFlow<TemplatesUiState> = _state.asStateFlow()

    /** 请求/消息自增序号 */
    private var seq: Long = 0

    init {
        refresh()
    }

    fun onIntent(intent: TemplatesIntent) {
        when (intent) {
            TemplatesIntent.Refresh -> refresh()

            TemplatesIntent.RequestCreate ->
                // 同一时刻只允许一个授权流程
                if (_state.value.authRequest == null) {
                    _state.update { it.copy(authRequest = nextSeq()) }
                }

            TemplatesIntent.AuthConsumed ->
                _state.update { it.copy(authRequest = null) }

            is TemplatesIntent.CaptureFailed ->
                postMessage(intent.message)

            is TemplatesIntent.StoreTemplate ->
                storeTemplate(intent.bitmap)

            is TemplatesIntent.ShowRenameDialog ->
                _state.update { it.copy(dialog = TemplatesDialog.Rename(intent.template)) }

            is TemplatesIntent.Rename -> rename(intent.template, intent.name)

            is TemplatesIntent.RequestDelete ->
                _state.update {
                    it.copy(dialog = TemplatesDialog.ConfirmDelete(intent.template))
                }

            is TemplatesIntent.ConfirmDelete -> delete(intent.template)

            is TemplatesIntent.LoadThumbnail -> loadThumbnail(intent.id)

            is TemplatesIntent.ToggleExpand ->
                _state.update { current ->
                    current.copy(
                        expandedId = if (current.expandedId == intent.template.id) {
                            null
                        } else {
                            intent.template.id
                        },
                    )
                }

            TemplatesIntent.DismissDialog ->
                _state.update { it.copy(dialog = null) }

            TemplatesIntent.MessageShown ->
                _state.update { it.copy(message = null) }

            TemplatesIntent.RefreshProjection ->
                _state.update {
                    it.copy(projectionActive = ContinuousScreenSource.isActive())
                }
        }
    }

    /** 从仓库重新加载列表，并清理已删除模板的缩略图 */
    private fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val result = runCatching { repository.list() }
            _state.update { current ->
                result.fold(
                    onSuccess = { templates ->
                        val validIds = templates.map { it.id }.toSet()
                        val stale = current.thumbnails.filterKeys { it !in validIds }
                        stale.values.forEach { b -> runCatching { b.recycle() } }
                        current.copy(
                            loading = false,
                            templates = templates,
                            thumbnails = current.thumbnails.filterKeys { it in validIds },
                            expandedId = current.expandedId?.takeIf { it in validIds },
                        )
                    },
                    onFailure = { e ->
                        current.copy(
                            loading = false,
                            message = TemplatesMessage(
                                nextSeq(),
                                "加载模板失败：${e.message ?: "未知错误"}",
                            ),
                        )
                    },
                )
            }
        }
    }

    /**
     * 裁剪结果入库：自动命名（"模板 N"，N 取现有最大编号 + 1，无编号时用列表数 + 1）。
     * 入库后立即回收裁剪位图并刷新列表。
     */
    private fun storeTemplate(bitmap: Bitmap) {
        viewModelScope.launch {
            val existing = _state.value.templates
            val maxNumber = existing.mapNotNull { t ->
                NUMBER_REGEX.matchEntire(t.name)
                    ?.groupValues?.getOrNull(1)?.toIntOrNull()
            }.maxOrNull()
            val next = (maxNumber ?: existing.size) + 1
            val id = repository.newId()
            val result = runCatching {
                repository.save(id = id, name = "模板 $next", bitmap = bitmap)
            }
            bitmap.recycle()
            result.onSuccess {
                postMessage("模板已保存，可重命名")
            }.onFailure { e ->
                postMessage("保存模板失败：${e.message ?: "未知错误"}")
            }
            refresh()
        }
    }

    /** 重命名：空白名称不允许；成功后关弹窗、刷新列表 */
    private fun rename(template: ImageTemplateMetadata, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) {
            postMessage("模板名称不能为空")
            return
        }
        viewModelScope.launch {
            val result = runCatching { repository.rename(template.id, trimmed) }
            result.onSuccess {
                _state.update { it.copy(dialog = null) }
                refresh()
            }.onFailure { e ->
                postMessage(e.message ?: "重命名失败，请重试")
            }
        }
    }

    /** 删除模板（已二次确认），完成后刷新列表 */
    private fun delete(template: ImageTemplateMetadata) {
        viewModelScope.launch {
            val result = runCatching { repository.delete(template.id) }
            result.onSuccess {
                _state.update { current ->
                    // 立即移除并回收缩略图
                    val bmp = current.thumbnails[template.id]
                    bmp?.let { runCatching { it.recycle() } }
                    current.copy(
                        dialog = null,
                        thumbnails = current.thumbnails.filterKeys { it != template.id },
                    )
                }
                refresh()
            }.onFailure { e ->
                postMessage("删除模板失败：${e.message ?: "未知错误"}")
            }
        }
    }

    /** 加载单张缩略图（已加载则跳过；重复结果只保留一份） */
    private fun loadThumbnail(id: String) {
        if (_state.value.thumbnails.containsKey(id)) return
        viewModelScope.launch {
            val bitmap = repository.loadThumbnail(id, THUMBNAIL_TARGET_PX) ?: return@launch
            _state.update { current ->
                if (current.thumbnails.containsKey(id)) {
                    runCatching { bitmap.recycle() }
                    current
                } else {
                    current.copy(thumbnails = current.thumbnails + (id to bitmap))
                }
            }
        }
    }

    private fun nextSeq(): Long {
        seq += 1
        return seq
    }

    private fun postMessage(text: String) {
        _state.update { it.copy(message = TemplatesMessage(nextSeq(), text)) }
    }

    override fun onCleared() {
        // VM 销毁：回收全部缩略图
        _state.value.thumbnails.values.forEach { b -> runCatching { b.recycle() } }
        super.onCleared()
    }

    private companion object {
        /** 缩略图目标长边像素（56dp @2x≈112px） */
        const val THUMBNAIL_TARGET_PX = 112

        /** 自动命名编号提取正则 */
        val NUMBER_REGEX = Regex("""模板\s*(\d+)""")
    }
}
