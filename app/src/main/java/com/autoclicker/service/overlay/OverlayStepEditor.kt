package com.autoclicker.service.overlay

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.autoclicker.MainActivity
import com.autoclicker.core.bus.LogLevel
import com.autoclicker.core.bus.RecordingSession
import com.autoclicker.core.bus.RuntimeBus
import com.autoclicker.core.permission.PermissionChecker
import com.autoclicker.domain.model.Action
import com.autoclicker.domain.model.AreaRandomClickAction
import com.autoclicker.domain.model.CallFunctionAction
import com.autoclicker.domain.model.ClickAction
import com.autoclicker.domain.model.ClickColorAction
import com.autoclicker.domain.model.ClickImageAction
import com.autoclicker.domain.model.ClickNodeAction
import com.autoclicker.domain.model.ClickTextAction
import com.autoclicker.domain.model.CloseAppAction
import com.autoclicker.domain.model.ConditionAction
import com.autoclicker.domain.model.DelayAction
import com.autoclicker.domain.model.EmptyAction
import com.autoclicker.domain.model.ExtractContentAction
import com.autoclicker.domain.model.FunctionPackage
import com.autoclicker.domain.model.GestureAction
import com.autoclicker.domain.model.GestureStroke
import com.autoclicker.domain.model.GlobalKeyAction
import com.autoclicker.domain.model.GlobalKeyName
import com.autoclicker.domain.model.GroupNode
import com.autoclicker.domain.model.Ids
import com.autoclicker.domain.model.ImageTemplate
import com.autoclicker.domain.model.InputTextAction
import com.autoclicker.domain.model.JumpAction
import com.autoclicker.domain.model.LongPressAction
import com.autoclicker.domain.model.NodeSelector
import com.autoclicker.domain.model.OpenAppAction
import com.autoclicker.domain.model.PercentPoint
import com.autoclicker.domain.model.PercentRect
import com.autoclicker.domain.model.PopupAction
import com.autoclicker.domain.model.RepeatClickAction
import com.autoclicker.domain.model.ScriptNode
import com.autoclicker.domain.model.SpeakAction
import com.autoclicker.domain.model.StepNode
import com.autoclicker.domain.model.SwipeAction
import com.autoclicker.domain.model.TextSource
import com.autoclicker.domain.model.ToastAction
import com.autoclicker.domain.model.VariableOpAction
import com.autoclicker.di.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.autoclicker.ui.EditorAction
import com.autoclicker.ui.editor.actionSummary
import kotlin.math.roundToInt

/**
 * 悬浮球任务编辑器（**纯原生 View**，直接在悬浮窗里改，不跳出 App）。
 *
 * 为什么不用 Compose：把 ComposeView 挂进 WindowManager 的悬浮窗窗口里在部分机型上会让进程直接退出；
 * 而本 App 里所有原生 View 悬浮窗（悬浮球 / 控制台 / 取点层 / 截图裁剪层）都工作正常，故这里统一用原生 View。
 *
 * 能力范围：查看步骤、**直接输入延时 / 次数**、常用动作的关键参数、上移下移删除、
 * 追加常用动作、勾选已有步骤新建步骤组、重命名任务；更深度的参数可点「完整编辑」跳 App 编辑器。
 */
object OverlayStepEditor {

    /** 打开时的初始页面。 */
    enum class Mode { LIST, GROUP, RENAME }

    private const val WIDTH_DP = 330
    private const val MARGIN_DP = 12
    private const val BALL_X_DP = 12
    private const val BALL_Y_DP = 160
    private const val BALL_SIZE_DP = 52
    private const val GAP_DP = 8
    private const val HEIGHT_RATIO = 0.62f
    private val PANEL_BG = 0xF21B1B1B.toInt()

    @Volatile
    private var active = false

    private var appContext: Context? = null
    private var manager: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null
    private var root: LinearLayout? = null
    private var density = 1f

    private var mode = Mode.LIST
    private var groupSelection = mutableSetOf<String>()

    /** 「添加动作」的目标步骤组 id；null 表示追加到顶层。 */
    private var actionTargetGroupId: String? = null

    /** 「移入已有动作」的目标步骤组 id 与勾选结果。 */
    private var moveInGroupId: String? = null
    private val moveInSelection = mutableSetOf<String>()

    fun isOpen(): Boolean = active

    // ---------------- 窗口 ----------------

    fun open(context: Context, startMode: Mode = Mode.LIST) {
        if (active) return
        if (!PermissionChecker.requireOverlay(context)) return
        val app = context.applicationContext
        val windowManager = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        if (!RecordingSession.isActive) {
            Toast.makeText(app, "请先创建或打开一个任务", Toast.LENGTH_SHORT).show()
            return
        }
        active = true
        appContext = app
        manager = windowManager
        density = app.resources.displayMetrics.density
        mode = startMode
        groupSelection = mutableSetOf()
        try {
            val panel = LinearLayout(app).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(PANEL_BG)
                setPadding(dp(10), dp(10), dp(10), dp(10))
            }
            root = panel
            render()

            val screenH = app.resources.displayMetrics.heightPixels
            val screenW = app.resources.displayMetrics.widthPixels
            val margin = dp(MARGIN_DP)
            val width = minOf(dp(WIDTH_DP), (screenW - margin * 2).coerceAtLeast(dp(200)))
            val height = (screenH * HEIGHT_RATIO).toInt()
            val lp = WindowManager.LayoutParams(
                width,
                height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // 必须可获焦点才能弹出输入法；NOT_TOUCH_MODAL 保证窗口外的触摸（如悬浮球）不被吞掉。
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                x = dp(BALL_X_DP)
                y = dp(BALL_Y_DP) + dp(BALL_SIZE_DP) + dp(GAP_DP)
            }
            params = lp
            windowManager.addView(panel, lp)
            panel.post { clampIntoScreen(panel, lp) }
            RuntimeBus.log("已打开悬浮窗任务编辑器（可直接改延时，无需跳转）")
        } catch (t: Throwable) {
            RuntimeBus.log(LogLevel.ERROR, "任务编辑器打开失败：${t.message}")
            Toast.makeText(app, "任务编辑器打开失败：${t.message}", Toast.LENGTH_LONG).show()
            cleanup()
        }
    }

    fun close() {
        if (!active) return
        cleanup()
    }

    private fun cleanup() {
        val view = root
        val windowManager = manager
        if (view != null && windowManager != null) {
            runCatching { windowManager.removeView(view) }
        }
        root = null
        manager = null
        params = null
        appContext = null
        active = false
        mode = Mode.LIST
        groupSelection.clear()
    }

    private fun clampIntoScreen(view: View, lp: WindowManager.LayoutParams) {
        val windowManager = manager ?: return
        val screenW = appContext?.resources?.displayMetrics?.widthPixels ?: return
        val screenH = appContext?.resources?.displayMetrics?.heightPixels ?: return
        val maxX = (screenW - lp.width).coerceAtLeast(0)
        val maxY = (screenH - lp.height).coerceAtLeast(0)
        lp.x = lp.x.coerceIn(0, maxX)
        lp.y = lp.y.coerceIn(0, maxY)
        runCatching { windowManager.updateViewLayout(view, lp) }
    }

    // ---------------- 内容渲染 ----------------

    private fun render() {
        val panel = root ?: return
        val context = appContext ?: return
        panel.removeAllViews()
        panel.addView(buildHeader(context))
        val body = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        body.addView(content)
        panel.addView(body)
        when (mode) {
            Mode.LIST -> {
                buildList(content)
                panel.addView(buildFooter(context))
            }

            Mode.GROUP -> buildGroupForm(content)
            Mode.RENAME -> buildRenameForm(content)
        }
    }

    private fun buildHeader(context: Context): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            TextView(context).apply {
                text = "任务：${RecordingSession.scriptName.value ?: "未绑定任务"}"
                setTextColor(Color.WHITE)
                textSize = 13f
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        row.addView(smallButton("重命名") {
            groupSelection = mutableSetOf()
            mode = Mode.RENAME
            render()
        })
        row.addView(smallButton("关闭") { close() })
        return row
    }

    private fun buildFooter(context: Context): View {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(smallButton("添加动作") {
            actionTargetGroupId = null
            mode = Mode.LIST
            showAddActionPicker()
        })
        row.addView(smallButton("新建步骤组") {
            groupSelection = mutableSetOf()
            mode = Mode.GROUP
            render()
        })
        row.addView(smallButton("完整编辑") { openInApp() })
        return row
    }

    // ---------------- 步骤列表 ----------------

    private fun buildList(container: LinearLayout) {
        val context = appContext ?: return
        val nodes = RecordingSession.nodes.value
        if (nodes.isEmpty()) {
            container.addView(hintText("暂无步骤，点下方「添加动作」"))
            return
        }
        nodes.forEachIndexed { index, node ->
            when (node) {
                is StepNode -> container.addView(buildStepRow(context, index, node))
                is GroupNode -> container.addView(buildGroupRow(context, index, node))
            }
        }
        container.addView(hintText("延时 / 次数 直接输入即可生效；组内步骤与深度参数可点「完整编辑」"))
    }

    private fun buildStepRow(context: Context, index: Int, step: StepNode): View {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        column.addView(
            TextView(context).apply {
                text = "#${index + 1}  ${actionSummary(step.action)}"
                setTextColor(Color.WHITE)
                textSize = 12f
            },
        )

        // 延时 / 次数：直接输入，没有加减按钮
        val numbers = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        numbers.addView(fieldLabel("延时ms"))
        numbers.addView(
            numberField(step.delayAfterMs, 1f) { value ->
                updateStep(step.id) { it.copy(delayAfterMs = value.coerceAtLeast(0L)) }
            },
        )
        numbers.addView(fieldLabel("次数"))
        numbers.addView(
            numberField(step.repeatCount.toLong(), 1f) { value ->
                updateStep(step.id) { it.copy(repeatCount = value.toInt().coerceAtLeast(1)) }
            },
        )
        column.addView(numbers)

        buildParamRow(step.id)?.let { column.addView(it) }

        val ops = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        ops.addView(smallButton("上移") {
            RecordingSession.moveNode(step.id, -1)
            render()
        })
        ops.addView(smallButton("下移") {
            RecordingSession.moveNode(step.id, 1)
            render()
        })
        ops.addView(smallButton("删除") {
            RecordingSession.removeNode(step.id)
            render()
        })
        column.addView(ops)
        return column
    }

    private fun buildGroupRow(context: Context, index: Int, group: GroupNode): View {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        column.addView(
            TextView(context).apply {
                text = "#${index + 1}  [步骤组] ${group.name.ifBlank { "步骤组" }} · ${group.children.size} 步"
                setTextColor(Color.parseColor("#4FC3F7"))
                textSize = 12f
            },
        )
        val numbers = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        numbers.addView(fieldLabel("循环"))
        numbers.addView(
            numberField(group.loopCount.toLong(), 1f) { value ->
                updateNode(group.id) { if (it is GroupNode) it.copy(loopCount = value.toInt().coerceAtLeast(1)) else it }
            },
        )
        numbers.addView(smallButton("上移") {
            RecordingSession.moveNode(group.id, -1)
            render()
        })
        numbers.addView(smallButton("下移") {
            RecordingSession.moveNode(group.id, 1)
            render()
        })
        numbers.addView(smallButton("删除") {
            RecordingSession.removeNode(group.id)
            render()
        })
        column.addView(numbers)

        // 组内增删：往组里追加动作，或把组内步骤移出到组后面。
        val groupOps = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        groupOps.addView(smallButton("组内加动作") {
            actionTargetGroupId = group.id
            showAddActionPicker()
        })
        groupOps.addView(smallButton("移入已有动作") { showMoveInPicker(group.id) })
        groupOps.addView(smallButton("移出全部(${group.children.size})") {
            RecordingSession.moveChildrenOutOfGroup(group.id)
            render()
        })
        column.addView(groupOps)
        if (group.children.isNotEmpty()) {
            column.addView(
                hintText(
                    "组内：" + group.children.joinToString("、") { child ->
                        if (child is StepNode) actionSummary(child.action) else "[步骤组]"
                    },
                ),
            )
        }
        return column
    }

    /** 常用动作的关键参数行；不支持的类型返回 null（提示去完整编辑）。 */
    private fun buildParamRow(stepId: String): View? {
        val context = appContext ?: return null
        val action = currentAction(stepId) ?: return null
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun percent(labelText: String, value: Float, apply: (Float) -> Unit) {
            row.addView(fieldLabel(labelText))
            row.addView(
                numberField((value * 100f).roundToInt().toLong(), 1f) { v ->
                    apply((v.coerceIn(0L, 100L)) / 100f)
                },
            )
        }

        fun number(labelText: String, value: Long, apply: (Long) -> Unit) {
            row.addView(fieldLabel(labelText))
            row.addView(numberField(value, 1f, apply))
        }

        fun text(labelText: String, value: String, apply: (String) -> Unit) {
            row.addView(fieldLabel(labelText))
            row.addView(textField(value, 2f, apply))
        }

        when (action) {
            is ClickAction -> {
                percent("X%", action.point.x) { v ->
                    updateActionTyped<ClickAction>(stepId) { it.copy(point = PercentPoint(v, it.point.y)) }
                }
                percent("Y%", action.point.y) { v ->
                    updateActionTyped<ClickAction>(stepId) { it.copy(point = PercentPoint(it.point.x, v)) }
                }
                number("按压", action.durationMs) { v ->
                    updateActionTyped<ClickAction>(stepId) { it.copy(durationMs = v) }
                }
            }

            is LongPressAction -> {
                percent("X%", action.point.x) { v ->
                    updateActionTyped<LongPressAction>(stepId) { it.copy(point = PercentPoint(v, it.point.y)) }
                }
                percent("Y%", action.point.y) { v ->
                    updateActionTyped<LongPressAction>(stepId) { it.copy(point = PercentPoint(it.point.x, v)) }
                }
                number("时长", action.durationMs) { v ->
                    updateActionTyped<LongPressAction>(stepId) { it.copy(durationMs = v) }
                }
            }

            is RepeatClickAction -> {
                percent("X%", action.point.x) { v ->
                    updateActionTyped<RepeatClickAction>(stepId) { it.copy(point = PercentPoint(v, it.point.y)) }
                }
                percent("Y%", action.point.y) { v ->
                    updateActionTyped<RepeatClickAction>(stepId) { it.copy(point = PercentPoint(it.point.x, v)) }
                }
                number("间隔", action.intervalMs) { v ->
                    updateActionTyped<RepeatClickAction>(stepId) { it.copy(intervalMs = v.coerceAtLeast(0L)) }
                }
                number("次数", action.count.toLong()) { v ->
                    updateActionTyped<RepeatClickAction>(stepId) { it.copy(count = v.toInt().coerceAtLeast(1)) }
                }
            }

            is SwipeAction -> {
                percent("起X", action.from.x) { v ->
                    updateActionTyped<SwipeAction>(stepId) { it.copy(from = PercentPoint(v, it.from.y)) }
                }
                percent("起Y", action.from.y) { v ->
                    updateActionTyped<SwipeAction>(stepId) { it.copy(from = PercentPoint(it.from.x, v)) }
                }
                percent("终X", action.to.x) { v ->
                    updateActionTyped<SwipeAction>(stepId) { it.copy(to = PercentPoint(v, it.to.y)) }
                }
                percent("终Y", action.to.y) { v ->
                    updateActionTyped<SwipeAction>(stepId) { it.copy(to = PercentPoint(it.to.x, v)) }
                }
                number("时长", action.durationMs) { v ->
                    updateActionTyped<SwipeAction>(stepId) { it.copy(durationMs = v) }
                }
            }

            is DelayAction -> {
                number("延时ms", action.ms) { v ->
                    updateActionTyped<DelayAction>(stepId) { it.copy(ms = v.coerceAtLeast(0L)) }
                }
                number("随机", action.randomMs) { v ->
                    updateActionTyped<DelayAction>(stepId) { it.copy(randomMs = v.coerceAtLeast(0L)) }
                }
            }

            is OpenAppAction -> text("包名", action.packageName) { v ->
                updateActionTyped<OpenAppAction>(stepId) { it.copy(packageName = v) }
            }

            is CloseAppAction -> text("包名", action.packageName) { v ->
                updateActionTyped<CloseAppAction>(stepId) { it.copy(packageName = v) }
            }

            is ToastAction -> text("内容", action.message) { v ->
                updateActionTyped<ToastAction>(stepId) { it.copy(message = v) }
            }

            is EmptyAction -> text("注释", action.note) { v ->
                updateActionTyped<EmptyAction>(stepId) { it.copy(note = v) }
            }

            is GlobalKeyAction -> {
                row.addView(fieldLabel("全局键"))
                row.addView(smallButton(action.key.name) {
                    val next = when (action.key) {
                        GlobalKeyName.BACK -> GlobalKeyName.HOME
                        GlobalKeyName.HOME -> GlobalKeyName.RECENTS
                        GlobalKeyName.RECENTS -> GlobalKeyName.BACK
                    }
                    updateActionTyped<GlobalKeyAction>(stepId) { it.copy(key = next) }
                    render()
                })
            }

            is AreaRandomClickAction -> {
                percent("左", action.rect.l) { v ->
                    updateActionTyped<AreaRandomClickAction>(stepId) { it.copy(rect = it.rect.copy(l = v)) }
                }
                percent("上", action.rect.t) { v ->
                    updateActionTyped<AreaRandomClickAction>(stepId) { it.copy(rect = it.rect.copy(t = v)) }
                }
                percent("右", action.rect.r) { v ->
                    updateActionTyped<AreaRandomClickAction>(stepId) { it.copy(rect = it.rect.copy(r = v)) }
                }
                percent("下", action.rect.b) { v ->
                    updateActionTyped<AreaRandomClickAction>(stepId) { it.copy(rect = it.rect.copy(b = v)) }
                }
            }

            is ClickTextAction -> text("文字", action.text) { v ->
                updateActionTyped<ClickTextAction>(stepId) { it.copy(text = v) }
            }

            is InputTextAction -> text("文本", action.source.literal) { v ->
                updateActionTyped<InputTextAction>(stepId) { it.copy(source = it.source.copy(literal = v)) }
            }

            is PopupAction -> {
                text("标题", action.title) { v ->
                    updateActionTyped<PopupAction>(stepId) { it.copy(title = v) }
                }
                text("内容", action.message) { v ->
                    updateActionTyped<PopupAction>(stepId) { it.copy(message = v) }
                }
            }

            is SpeakAction -> text("内容", action.message) { v ->
                updateActionTyped<SpeakAction>(stepId) { it.copy(message = v) }
            }

            is JumpAction -> {
                row.addView(fieldLabel("跳转到"))
                row.addView(
                    smallButton(if (action.mode == com.autoclicker.domain.model.JumpMode.END_TASK) "结束任务" else "指定步骤") {
                        val next = if (action.mode == com.autoclicker.domain.model.JumpMode.END_TASK) {
                            com.autoclicker.domain.model.JumpMode.STEP
                        } else {
                            com.autoclicker.domain.model.JumpMode.END_TASK
                        }
                        updateActionTyped<JumpAction>(stepId) { it.copy(mode = next) }
                        render()
                    },
                )
            }

            else -> return null
        }
        return row
    }

    // ---------------- 添加动作 ----------------

    /** 按当前目标追加步骤：在组内则追加到组末尾，否则追加到顶层。 */
    private fun appendStep(step: StepNode) {
        val groupId = actionTargetGroupId
        if (groupId != null && RecordingSession.nodes.value.any { it is GroupNode && it.id == groupId }) {
            RecordingSession.addNodeToGroup(groupId, step)
        } else {
            RecordingSession.addNode(step)
        }
    }

    // ---------------- 移入已有动作 ----------------

    private fun showMoveInPicker(groupId: String) {
        moveInGroupId = groupId
        moveInSelection.clear()
        renderMoveInPicker()
    }

    /** 勾选顶层已有步骤，收进指定步骤组。 */
    private fun renderMoveInPicker() {
        val panel = root ?: return
        val context = appContext ?: return
        val groupId = moveInGroupId ?: return
        val group = RecordingSession.nodes.value
            .firstOrNull { it is GroupNode && it.id == groupId } as? GroupNode
        val groupName = group?.name?.ifBlank { "步骤组" } ?: "步骤组"
        panel.removeAllViews()
        panel.addView(buildHeader(context))
        val body = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        body.addView(content)
        panel.addView(body)

        val candidates = RecordingSession.nodes.value.filterIsInstance<StepNode>()
        content.addView(hintText("勾选要收进【$groupName】的已有动作（可多选）："))
        if (candidates.isEmpty()) {
            content.addView(hintText("顶层暂无可移入的动作；组内步骤可点「完整编辑」调整"))
        }
        candidates.forEach { step ->
            content.addView(
                CheckBox(context).apply {
                    text = actionSummary(step.action)
                    setTextColor(Color.WHITE)
                    textSize = 12f
                    isChecked = step.id in moveInSelection
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) moveInSelection.add(step.id) else moveInSelection.remove(step.id)
                    }
                },
            )
        }

        val footer = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        footer.addView(smallButton("确认移入") {
            val nodes = RecordingSession.nodes.value
            val moving = nodes.filterIsInstance<StepNode>().filter { it.id in moveInSelection }
            if (moving.isEmpty()) {
                Toast.makeText(appContext, "请先勾选要移入的动作", Toast.LENGTH_SHORT).show()
                return@smallButton
            }
            val rest = nodes.filterNot { it is StepNode && it.id in moveInSelection }
            val updated = rest.map { node ->
                if (node is GroupNode && node.id == groupId) {
                    node.copy(children = node.children + moving)
                } else {
                    node
                }
            }
            RecordingSession.replaceNodes(updated)
            RuntimeBus.log("步骤组「$groupName」移入 ${moving.size} 个动作")
            mode = Mode.LIST
            render()
        })
        footer.addView(smallButton("取消") {
            mode = Mode.LIST
            render()
        })
        panel.addView(footer)
    }

    /** 编辑器自身的协程域：用于异步加载模板 / 函数包列表（object 常驻，任务短，结束后自然回收）。 */
    private val pickerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * 全部可添加的动作（与 Action.kt 的全集一一对应）。
     * 「点击图片」「调用函数」需要先选模板 / 函数包，单独走选择器，不在此表内。
     */
    private val quickActions: List<Pair<String, () -> Action>> = listOf(
        "点击坐标" to { ClickAction(PercentPoint(0.5f, 0.5f)) },
        "长按" to { LongPressAction(PercentPoint(0.5f, 0.5f)) },
        "连续点击" to { RepeatClickAction(PercentPoint(0.5f, 0.5f)) },
        "区域随机点击" to { AreaRandomClickAction(PercentRect(0.4f, 0.4f, 0.6f, 0.6f)) },
        "点击颜色" to { ClickColorAction(color = 0xFFFF0000.toInt()) },
        "点击文字" to { ClickTextAction(text = "") },
        "点击节点" to { ClickNodeAction(selector = NodeSelector()) },
        "手势" to {
            GestureAction(
                listOf(
                    GestureStroke(
                        listOf(PercentPoint(0.5f, 0.8f), PercentPoint(0.5f, 0.2f)),
                    ),
                ),
            )
        },
        "滑动" to { SwipeAction(PercentPoint(0.5f, 0.8f), PercentPoint(0.5f, 0.2f)) },
        "返回键" to { GlobalKeyAction(GlobalKeyName.BACK) },
        "返回桌面" to { GlobalKeyAction(GlobalKeyName.HOME) },
        "最近任务" to { GlobalKeyAction(GlobalKeyName.RECENTS) },
        "打开应用" to { OpenAppAction(packageName = "") },
        "关闭应用" to { CloseAppAction(packageName = "") },
        "输入文字" to { InputTextAction(source = TextSource()) },
        "内容提取" to { ExtractContentAction(targetVar = "result") },
        "变量操作" to { VariableOpAction(varName = "var1") },
        "条件判断" to { ConditionAction() },
        "跳转" to { JumpAction() },
        "延时" to { DelayAction() },
        "弹窗提示" to { PopupAction(message = "") },
        "语音播报" to { SpeakAction(message = "") },
        "Toast 提示" to { ToastAction(message = "") },
        "占位注释" to { EmptyAction(note = "") },
    )

    /** 在面板内展开「添加动作」列表（不弹对话框，避免悬浮窗 token 问题）。 */
    private fun showAddActionPicker() {
        val panel = root ?: return
        val context = appContext ?: return
        panel.removeAllViews()
        panel.addView(buildHeader(context))
        val body = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        body.addView(content)
        panel.addView(body)
        content.addView(
            hintText(
                if (actionTargetGroupId != null) {
                    "选择要添加到【步骤组内】的动作（添加后可在此直接改参数/延时）"
                } else {
                    "选择要添加的动作（添加后可在此直接改参数/延时）"
                },
            ),
        )
        // 需要先选资源的两类动作放最前。
        content.addView(
            smallButton("点击图片（先选模板）") { showTemplatePicker() },
        )
        content.addView(
            smallButton("调用函数（先选函数包）") { showPackagePicker() },
        )
        quickActions.forEach { (label, factory) ->
            content.addView(
                smallButton(label) {
                    appendStep(StepNode(Ids.newId(), factory()))
                    mode = Mode.LIST
                    render()
                },
            )
        }
        val footer = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        footer.addView(smallButton("返回列表") {
            mode = Mode.LIST
            render()
        })
        panel.addView(footer)
    }

    /** 「点击图片」的模板选择器：面板内列出现有模板，点选后追加 ClickImageAction。 */
    private fun showTemplatePicker() {
        val panel = root ?: return
        val context = appContext ?: return
        pickerScope.launch {
            val templates = runCatching { ServiceLocator.templates.list() }.getOrElse { emptyList() }
            if (!active) return@launch
            panel.removeAllViews()
            panel.addView(buildHeader(context))
            val body = ScrollView(context).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            }
            val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            body.addView(content)
            panel.addView(body)
            if (templates.isEmpty()) {
                content.addView(hintText("还没有图像模板：请先在控制台「截图建模板」创建，再回来添加"))
            } else {
                content.addView(hintText("选择要点击的模板："))
                templates.forEach { template: ImageTemplate ->
                    content.addView(
                        smallButton(template.name) {
                            appendStep(StepNode(Ids.newId(), ClickImageAction(templateId = template.id)))
                            mode = Mode.LIST
                            render()
                        },
                    )
                }
            }
            val footer = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            footer.addView(smallButton("返回") { showAddActionPicker() })
            panel.addView(footer)
        }
    }

    /** 「调用函数」的函数包选择器：面板内列出现有函数包，点选后追加 CallFunctionAction。 */
    private fun showPackagePicker() {
        val panel = root ?: return
        val context = appContext ?: return
        pickerScope.launch {
            val packages = runCatching { ServiceLocator.packages.list() }.getOrElse { emptyList() }
            if (!active) return@launch
            panel.removeAllViews()
            panel.addView(buildHeader(context))
            val body = ScrollView(context).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            }
            val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            body.addView(content)
            panel.addView(body)
            if (packages.isEmpty()) {
                content.addView(hintText("还没有函数包：请先在 App 内「函数包」页创建，再回来添加"))
            } else {
                content.addView(hintText("选择要调用的函数包："))
                packages.forEach { pkg: FunctionPackage ->
                    content.addView(
                        smallButton(pkg.name) {
                            appendStep(StepNode(Ids.newId(), CallFunctionAction(packageId = pkg.id)))
                            mode = Mode.LIST
                            render()
                        },
                    )
                }
            }
            val footer = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            footer.addView(smallButton("返回") { showAddActionPicker() })
            panel.addView(footer)
        }
    }

    // ---------------- 新建步骤组（勾选已有步骤） ----------------

    private fun buildGroupForm(container: LinearLayout) {
        val context = appContext ?: return
        container.addView(hintText("新建步骤组：填写名称后勾选要并入的步骤，确定后这些步骤会移入该组"))
        val nameField = textField("", 3f) { /* 输入即取，无需回调 */ }
        nameField.hint = "步骤组名称"
        container.addView(labeledRow("名称", nameField))
        val loopField = numberField(1L, 3f) { /* 确定时读取 */ }
        container.addView(labeledRow("循环次数", loopField))

        val nodes = RecordingSession.nodes.value
        if (nodes.none { it is StepNode }) {
            container.addView(hintText("当前任务还没有步骤，将创建空步骤组"))
        } else {
            container.addView(hintText("勾选要并入的步骤："))
            nodes.forEachIndexed { index, node ->
                when (node) {
                    is StepNode -> container.addView(
                        CheckBox(context).apply {
                            setTextColor(Color.WHITE)
                            textSize = 12f
                            text = "#${index + 1} ${actionSummary(node.action)}"
                            isChecked = false
                            setOnCheckedChangeListener { _, checked ->
                                if (checked) groupSelection.add(node.id) else groupSelection.remove(node.id)
                            }
                        },
                    )

                    is GroupNode -> container.addView(
                        hintText("#${index + 1} [步骤组] ${node.name.ifBlank { "步骤组" }}（不可并入）"),
                    )
                }
            }
        }

        val ops = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        ops.addView(
            smallButton("确定") {
                val name = nameField.text?.toString()?.trim().orEmpty().ifBlank { "步骤组" }
                val loop = loopField.text?.toString()?.toLongOrNull()?.toInt()?.coerceAtLeast(1) ?: 1
                createGroup(name, loop)
                groupSelection = mutableSetOf()
                mode = Mode.LIST
                render()
            },
        )
        ops.addView(
            smallButton("取消") {
                groupSelection = mutableSetOf()
                mode = Mode.LIST
                render()
            },
        )
        container.addView(ops)
    }

    /** 选中的顶层步骤移入新组，组插在第一个被选中步骤的位置；未选中则创建空组。 */
    private fun createGroup(name: String, loop: Int) {
        val nodes = RecordingSession.nodes.value
        val picked = nodes.filterIsInstance<StepNode>().filter { it.id in groupSelection }
        val rest = nodes.filterNot { it is StepNode && it.id in groupSelection }
        val firstPickedId = picked.firstOrNull()?.id
        val insertAt = if (firstPickedId == null) {
            rest.size
        } else {
            val firstIndex = nodes.indexOfFirst { it.id == firstPickedId }
            nodes.take(firstIndex.coerceAtLeast(0)).count { it !is StepNode || it.id !in groupSelection }
        }
        val group = GroupNode(
            id = Ids.newId(),
            name = name,
            loopCount = loop,
            children = picked,
        )
        val updated = rest.toMutableList().apply { add(insertAt.coerceIn(0, size), group) }
        RecordingSession.replaceNodes(updated)
    }

    // ---------------- 重命名 ----------------

    private fun buildRenameForm(container: LinearLayout) {
        val context = appContext ?: return
        container.addView(hintText("重命名当前任务"))
        val nameField = textField(RecordingSession.scriptName.value.orEmpty(), 3f) { /* 确定时读取 */ }
        container.addView(labeledRow("任务名称", nameField))
        val ops = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        ops.addView(
            smallButton("确定") {
                val name = nameField.text?.toString()?.trim().orEmpty()
                if (name.isNotBlank()) RecordingSession.rename(name)
                mode = Mode.LIST
                render()
            },
        )
        ops.addView(
            smallButton("取消") {
                mode = Mode.LIST
                render()
            },
        )
        container.addView(ops)
    }

    private fun openInApp() {
        val context = appContext ?: return
        val scriptId = RecordingSession.scriptId.value ?: return
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
            .putExtra(MainActivity.EXTRA_EDITOR_SCRIPT_ID, scriptId)
            .putExtra(MainActivity.EXTRA_EDITOR_ACTION, EditorAction.NONE)
        runCatching { context.startActivity(intent) }
            .onFailure { RuntimeBus.log(LogLevel.WARN, "打开 App 编辑器失败：${it.message}") }
    }

    // ---------------- 数据读写 ----------------

    private fun currentAction(stepId: String): Action? =
        RecordingSession.nodes.value.firstOrNull { it.id == stepId }?.let { (it as? StepNode)?.action }

    private fun updateStep(id: String, transform: (StepNode) -> StepNode) {
        val node = RecordingSession.nodes.value.firstOrNull { it.id == id } as? StepNode ?: return
        RecordingSession.updateNode(transform(node))
    }

    private fun updateNode(id: String, transform: (ScriptNode) -> ScriptNode) {
        val node = RecordingSession.nodes.value.firstOrNull { it.id == id } ?: return
        RecordingSession.updateNode(transform(node))
    }

    private inline fun <reified T : Action> updateActionTyped(stepId: String, crossinline transform: (T) -> Action) {
        val node = RecordingSession.nodes.value.firstOrNull { it.id == stepId } as? StepNode ?: return
        val action = node.action
        if (action is T) RecordingSession.updateNode(node.copy(action = transform(action)))
    }

    // ---------------- 小控件 ----------------

    private fun label(text: String): TextView = TextView(appContext).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 12f
    }

    private fun fieldLabel(text: String): TextView = TextView(appContext).apply {
        this.text = text
        setTextColor(Color.parseColor("#BBBBBB"))
        textSize = 10f
        setPadding(dp(4), 0, dp(2), 0)
    }

    private fun hintText(text: String): TextView = TextView(appContext).apply {
        this.text = text
        setTextColor(Color.parseColor("#999999"))
        textSize = 11f
        setPadding(0, dp(6), 0, dp(6))
    }

    private fun labeledRow(labelText: String, field: View): View {
        val context = appContext ?: return field
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        row.addView(fieldLabel(labelText))
        row.addView(field)
        return row
    }

    private fun smallButton(textValue: String, onClick: () -> Unit): Button =
        Button(appContext).apply {
            text = textValue
            textSize = 11f
            isAllCaps = false
            minimumWidth = 0
            minimumHeight = 0
            setPadding(dp(8), dp(2), dp(8), dp(2))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }
            setOnClickListener { onClick() }
        }

    /** 数字输入框：**直接输入即生效**（不再用 延时- / 延时+ 按钮）。 */
    private fun numberField(value: Long, weight: Float, onValue: (Long) -> Unit): EditText =
        EditText(appContext).apply {
            setText(value.toString())
            textSize = 11f
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_NUMBER
            gravity = Gravity.CENTER_VERTICAL
            setSelectAllOnFocus(true)
            setPadding(dp(4), 0, dp(4), 0)
            setBackgroundColor(0x33FFFFFF)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
            addTextChangedListener(
                object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

                    override fun afterTextChanged(s: Editable?) {
                        val parsed = s?.toString()?.trim()?.toLongOrNull() ?: return
                        onValue(parsed)
                    }
                },
            )
        }

    private fun textField(value: String, weight: Float, onValue: (String) -> Unit): EditText =
        EditText(appContext).apply {
            setText(value)
            textSize = 11f
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_TEXT
            gravity = Gravity.CENTER_VERTICAL
            setSelectAllOnFocus(true)
            setPadding(dp(4), 0, dp(4), 0)
            setBackgroundColor(0x33FFFFFF)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
            addTextChangedListener(
                object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

                    override fun afterTextChanged(s: Editable?) {
                        onValue(s?.toString().orEmpty())
                    }
                },
            )
        }

    private fun dp(value: Int): Int = (value * density).roundToInt()
}
