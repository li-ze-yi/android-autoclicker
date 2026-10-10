package com.autoclicker.ui.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.autoclicker.core.bus.LogLevel
import com.autoclicker.core.bus.RecordingSession
import com.autoclicker.core.bus.RuntimeBus
import com.autoclicker.core.permission.PermissionChecker
import com.autoclicker.di.ServiceLocator
import com.autoclicker.domain.model.Action
import com.autoclicker.domain.model.FunctionPackage
import com.autoclicker.domain.model.GroupNode
import com.autoclicker.domain.model.Ids
import com.autoclicker.domain.model.ImageTemplate
import com.autoclicker.domain.model.ScriptNode
import com.autoclicker.domain.model.StepNode
import com.autoclicker.domain.model.TextGroup
import com.autoclicker.ui.editor.ACTION_CATEGORIES
import com.autoclicker.ui.editor.ActionCategory
import com.autoclicker.ui.editor.ActionEntry
import com.autoclicker.ui.editor.ActionFormContent
import com.autoclicker.ui.editor.actionSummary
import com.autoclicker.ui.editor.actionTitle
import com.autoclicker.ui.theme.AppTheme
import kotlin.math.roundToInt

/**
 * 悬浮窗内的完整任务编辑器：用 Compose 嵌入一个可编辑窗口，
 * 支持查看 / 编辑 / 排序 / 增删任务步骤，以及添加全部动作。
 *
 * 步骤读写统一走 [RecordingSession]，任何修改都会自动保存回任务。
 */
object OverlayTaskEditor {

    private const val WIDTH_DP = 340
    private const val MARGIN_DP = 12

    // 悬浮球默认位置与尺寸（见 OverlayService），用于避让。
    private const val BALL_X_DP = 12
    private const val BALL_Y_DP = 160
    private const val BALL_SIZE_DP = 52
    private const val BALL_GAP_DP = 8

    @Volatile
    private var active = false

    private var frame: View? = null
    private var manager: WindowManager? = null
    private var owner: OverlayOwners? = null

    fun isOpen(): Boolean = active

    /** 打开悬浮窗内的任务编辑器。 */
    fun open(context: Context) {
        if (active) return
        if (!PermissionChecker.requireOverlay(context)) return
        val app = context.applicationContext
        val windowManager = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val density = app.resources.displayMetrics.density
        active = true
        try {
            val lifecycleOwner = OverlayOwners().apply { attach() }
            val composeView = ComposeView(app).apply {
                setViewTreeLifecycleOwner(lifecycleOwner)
                setViewTreeViewModelStoreOwner(lifecycleOwner)
                setViewTreeSavedStateRegistryOwner(lifecycleOwner)
                setContent {
                    AppTheme {
                        OverlayTaskEditorPanel(onClose = { close() })
                    }
                }
            }
            val container = FrameLayout(app).apply {
                addView(
                    composeView,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
            val screenW = app.resources.displayMetrics.widthPixels
            val margin = dp(density, MARGIN_DP)
            val width = minOf(dp(density, WIDTH_DP), (screenW - margin * 2).coerceAtLeast(dp(density, 200)))
            val lp = WindowManager.LayoutParams(
                width,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                x = margin
                y = dp(density, BALL_Y_DP) + dp(density, BALL_SIZE_DP) + dp(density, BALL_GAP_DP)
            }
            windowManager.addView(container, lp)

            frame = container
            manager = windowManager
            owner = lifecycleOwner

            // 测量后再做一次边界 clamp，确保完全落在屏幕内。
            container.post { clampIntoScreen(container, lp) }
        } catch (t: Throwable) {
            RuntimeBus.log(LogLevel.ERROR, "任务编辑器打开失败：${t.message}")
            cleanup()
        }
    }

    /** 关闭任务编辑器窗口，允许再次 [open]。 */
    fun close() {
        if (!active) return
        cleanup()
    }

    private fun cleanup() {
        val view = frame
        val windowManager = manager
        if (view != null && windowManager != null) {
            runCatching { windowManager.removeView(view) }
        }
        owner?.destroy()
        frame = null
        manager = null
        owner = null
        active = false
    }

    private fun clampIntoScreen(view: View, lp: WindowManager.LayoutParams) {
        val windowManager = manager ?: return
        val density = view.resources.displayMetrics.density
        val screenW = view.resources.displayMetrics.widthPixels
        val screenH = view.resources.displayMetrics.heightPixels
        val winW = if (lp.width > 0) lp.width else view.measuredWidth
        val winH = if (view.height > 0) view.height else view.measuredHeight

        val ballY = dp(density, BALL_Y_DP)
        val ballSize = dp(density, BALL_SIZE_DP)
        val gap = dp(density, BALL_GAP_DP)
        val belowY = ballY + ballSize + gap
        val aboveY = ballY - winH - gap
        val y = when {
            winH <= 0 -> belowY
            belowY + winH <= screenH -> belowY
            aboveY >= 0 -> aboveY
            else -> belowY
        }

        val maxX = (screenW - winW).coerceAtLeast(0)
        val maxY = (screenH - winH).coerceAtLeast(0)
        lp.x = dp(density, BALL_X_DP).coerceIn(0, maxX)
        lp.y = y.coerceIn(0, maxY)
        runCatching { windowManager.updateViewLayout(view, lp) }
    }

    private fun dp(density: Float, value: Int): Int = (value * density).roundToInt()
}

/**
 * 非 Activity 窗口里的 ComposeView 需要手工挂载三大所有者：
 * [LifecycleOwner] / [SavedStateRegistryOwner] / [ViewModelStoreOwner]。
 */
private class OverlayOwners : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    override val viewModelStore: ViewModelStore get() = store

    /** 挂载到视图树之前调用：先恢复已保存状态（须处于 INITIALIZED），再推进到 RESUMED。 */
    fun attach() {
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    /** 移除视图时调用，销毁 lifecycle 以便 Composable 正确释放。 */
    fun destroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }
}

// ---------------- 面板 ----------------

/** 面板最大高度（超过则内部滚动）。 */
private const val PANEL_MAX_HEIGHT_DP = 560

private enum class EditorScreen { LIST, CATEGORY, ACTION, FORM, GROUP }

@Composable
private fun OverlayTaskEditorPanel(onClose: () -> Unit) {
    val nodes by RecordingSession.nodes.collectAsState()
    val scriptName by RecordingSession.scriptName.collectAsState()

    var templates by remember { mutableStateOf<List<ImageTemplate>>(emptyList()) }
    var packages by remember { mutableStateOf<List<FunctionPackage>>(emptyList()) }
    var textGroups by remember { mutableStateOf<List<TextGroup>>(emptyList()) }

    LaunchedEffect(Unit) {
        templates = runCatching { ServiceLocator.templates.list() }.getOrDefault(emptyList())
        packages = runCatching { ServiceLocator.packages.list() }.getOrDefault(emptyList())
        textGroups = runCatching { ServiceLocator.textGroups.list() }.getOrDefault(emptyList())
    }

    var screen by remember { mutableStateOf(EditorScreen.LIST) }
    var renaming by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<ActionCategory?>(null) }
    var editingStepId by remember { mutableStateOf<String?>(null) }
    var action by remember { mutableStateOf<Action?>(null) }
    var working by remember { mutableStateOf<Action?>(null) }
    var groupName by remember { mutableStateOf("") }
    var groupLoop by remember { mutableStateOf("1") }

    val backToList: () -> Unit = {
        screen = EditorScreen.LIST
        category = null
        editingStepId = null
        action = null
        working = null
    }

    Surface(modifier = Modifier.fillMaxWidth().heightIn(max = PANEL_MAX_HEIGHT_DP.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            // 标题 / 重命名（overlay 窗口里不使用 Compose Dialog，改为内联输入）
            if (renaming) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        label = { Text("任务名称") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    MiniButton("确定") {
                        RecordingSession.rename(renameText)
                        renaming = false
                    }
                    MiniButton("取消") { renaming = false }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "任务编辑：${scriptName ?: "未绑定任务"}",
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    MiniButton("重命名") {
                        renameText = scriptName.orEmpty()
                        renaming = true
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

            when (screen) {
                EditorScreen.LIST -> StepList(nodes) { step ->
                    editingStepId = step.id
                    action = step.action
                    working = step.action
                    screen = EditorScreen.FORM
                }

                EditorScreen.CATEGORY -> CategoryPicker { picked ->
                    category = picked
                    screen = EditorScreen.ACTION
                }

                EditorScreen.ACTION -> ActionPicker(category) { entry ->
                    editingStepId = null
                    val created = entry.create()
                    action = created
                    working = created
                    screen = EditorScreen.FORM
                }

                EditorScreen.FORM -> {
                    val initial = action
                    if (initial == null) {
                        Text("无动作")
                    } else {
                        Text(actionTitle(initial), style = MaterialTheme.typography.labelLarge)
                        ActionFormContent(
                            initial = initial,
                            templates = templates,
                            packages = packages,
                            textGroups = textGroups,
                            steps = nodes.filterIsInstance<StepNode>(),
                            onChanged = { working = it },
                        )
                        Row {
                            Button(
                                onClick = {
                                    val result = working ?: initial
                                    val stepId = editingStepId
                                    if (stepId == null) {
                                        RecordingSession.addNode(StepNode(Ids.newId(), result))
                                    } else {
                                        nodes.filterIsInstance<StepNode>()
                                            .firstOrNull { it.id == stepId }
                                            ?.let { RecordingSession.updateNode(it.copy(action = result)) }
                                    }
                                    backToList()
                                },
                            ) { Text("确定") }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = backToList) { Text("取消") }
                        }
                    }
                }

                EditorScreen.GROUP -> GroupEditor(
                    name = groupName,
                    loop = groupLoop,
                    onName = { groupName = it },
                    onLoop = { groupLoop = it },
                    onConfirm = {
                        RecordingSession.addNode(
                            GroupNode(
                                id = Ids.newId(),
                                name = groupName.ifBlank { "步骤组" },
                                loopCount = (groupLoop.trim().toIntOrNull() ?: 1).coerceAtLeast(1),
                            ),
                        )
                        groupName = ""
                        groupLoop = "1"
                        backToList()
                    },
                    onCancel = {
                        groupName = ""
                        groupLoop = "1"
                        backToList()
                    },
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (screen == EditorScreen.LIST) {
                    OutlinedButton(
                        onClick = {
                            category = null
                            screen = EditorScreen.CATEGORY
                        },
                    ) { Text("添加动作") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { screen = EditorScreen.GROUP }) { Text("添加步骤组") }
                } else {
                    OutlinedButton(onClick = backToList) { Text("返回列表") }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("关闭") }
            }
        }
    }
}

@Composable
private fun ColumnScope.StepList(nodes: List<ScriptNode>, onEditAction: (StepNode) -> Unit) {
    if (nodes.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("暂无步骤，点击下方「添加动作」", style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxWidth().weight(1f),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(nodes, key = { _, node -> node.id }) { index, node ->
            when (node) {
                is StepNode -> StepRow(index, node, onEditAction)
                is GroupNode -> GroupRow(index, node)
            }
        }
    }
}

@Composable
private fun StepRow(index: Int, step: StepNode, onEditAction: (StepNode) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "#${index + 1} ${actionSummary(step.action)}",
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "延时 ${step.delayAfterMs}ms · 次数 ${step.repeatCount}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row {
            MiniButton("上移") { RecordingSession.moveNode(step.id, -1) }
            MiniButton("下移") { RecordingSession.moveNode(step.id, 1) }
            MiniButton("编辑") { onEditAction(step) }
            MiniButton("删除") { RecordingSession.removeNode(step.id) }
        }
        Row {
            MiniButton("延时-") {
                RecordingSession.updateNode(
                    step.copy(delayAfterMs = (step.delayAfterMs - 100L).coerceAtLeast(0L)),
                )
            }
            MiniButton("延时+") {
                RecordingSession.updateNode(step.copy(delayAfterMs = step.delayAfterMs + 100L))
            }
            MiniButton("次数-") {
                RecordingSession.updateNode(
                    step.copy(repeatCount = (step.repeatCount - 1).coerceAtLeast(1)),
                )
            }
            MiniButton("次数+") {
                RecordingSession.updateNode(step.copy(repeatCount = step.repeatCount + 1))
            }
        }
    }
}

@Composable
private fun GroupRow(index: Int, group: GroupNode) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "#${index + 1} [步骤组] ${group.name.ifBlank { group.id.take(6) }} ×${group.loopCount}",
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row {
            MiniButton("上移") { RecordingSession.moveNode(group.id, -1) }
            MiniButton("下移") { RecordingSession.moveNode(group.id, 1) }
            MiniButton("循环-") {
                RecordingSession.updateNode(
                    group.copy(loopCount = (group.loopCount - 1).coerceAtLeast(1)),
                )
            }
            MiniButton("循环+") {
                RecordingSession.updateNode(group.copy(loopCount = group.loopCount + 1))
            }
            MiniButton("删除") { RecordingSession.removeNode(group.id) }
        }
    }
}

@Composable
private fun ColumnScope.CategoryPicker(onPick: (ActionCategory) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
        items(ACTION_CATEGORIES, key = { it.title }) { cat ->
            TextButton(
                onClick = { onPick(cat) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(cat.title, modifier = Modifier.weight(1f))
                Text("${cat.entries.size} 项", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun ColumnScope.ActionPicker(category: ActionCategory?, onPick: (ActionEntry) -> Unit) {
    Text(category?.title ?: "选择动作", style = MaterialTheme.typography.labelLarge)
    LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
        items(category?.entries.orEmpty(), key = { it.label }) { entry ->
            TextButton(
                onClick = { onPick(entry) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(entry.label, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun GroupEditor(
    name: String,
    loop: String,
    onName: (String) -> Unit,
    onLoop: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("新建步骤组", style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(
            value = name,
            onValueChange = onName,
            label = { Text("组名称") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = loop,
            onValueChange = onLoop,
            label = { Text("循环次数") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row {
            Button(onClick = onConfirm) { Text("确定") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onCancel) { Text("取消") }
        }
    }
}

@Composable
private fun MiniButton(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}