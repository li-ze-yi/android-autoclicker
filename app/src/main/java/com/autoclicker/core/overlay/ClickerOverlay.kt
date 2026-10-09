package com.autoclicker.core.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.autoclicker.core.clicker.ClickerConfig
import com.autoclicker.core.clicker.ClickerEngine
import com.autoclicker.core.clicker.ClickerPoint
import com.autoclicker.core.clicker.ClickerState
import kotlin.math.hypot

/**
 * 点击器模式的悬浮窗视图构建与状态渲染。仅负责纯代码 android.widget 视图，
 * 交互逻辑通过 [Callbacks] 回调给 OverlayService。
 */
internal object ClickerOverlay {

    /** 拖动判定阈值（像素），小于该位移视为点击。 */
    private const val DRAG_SLOP = 10f

    /** 标记（点击点）圆形直径（dp）。 */
    private const val MARKER_SIZE_DP = 44f

    /** 标记层编辑态的极淡提示色：非常低的不透明度，避免运行/预览时遮挡画面。 */
    private const val COLOR_MARKER_HINT = 0x1100B0FF

    /** 回调接口，供 OverlayService 实现。 */
    interface Callbacks {
        fun onAddPoint(x: Float, y: Float)
        fun onMovePoint(id: String, x: Float, y: Float)
        fun onEditPoint(point: ClickerPoint)
        fun onStartClicker()
        fun onStopClicker()
        fun onClearPoints()
        fun onLoopSettings(config: ClickerConfig)
        fun onExportScript()
        fun onExitClicker()
    }

    // ---- 可获焦小窗引用（各自独立，随 hideEditors 一并移除） ----

    private var pointEditorView: View? = null
    private var loopSettingsView: View? = null

    /** 全屏标记层：可触摸，点击空白处添加点，标记可拖动、点击可编辑。 */
    fun createMarkerLayer(context: Context, callbacks: Callbacks): View {
        val root = FrameLayout(context)
        root.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        // 极淡提示色（编辑态），不透明度极低以免遮挡底层画面。
        root.setBackgroundColor(COLOR_MARKER_HINT)
        root.isClickable = true

        // 标记容器：setPoints 时只清空该容器，标记层本体（背景）保持不动。
        val container = FrameLayout(context)
        root.addView(
            container,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        root.tag = MarkerHolder(container, callbacks)

        var downRawX = 0f
        var downRawY = 0f
        root.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    true
                }

                MotionEvent.ACTION_UP -> {
                    // 触摸未落在任何标记上（标记会消费事件，故此处必为空白处），
                    // 且移动距离小于阈值时才添加点击点。
                    val moved = hypot(event.rawX - downRawX, event.rawY - downRawY)
                    if (moved < DRAG_SLOP) {
                        callbacks.onAddPoint(event.rawX, event.rawY)
                    }
                    true
                }

                else -> true
            }
        }
        return root
    }

    /** 底部控制条：状态行 + 若干按钮。 */
    fun createControlBar(context: Context, callbacks: Callbacks): View {
        // 外层容器带来左右 8dp 间距（窗口宽度为 MATCH_PARENT，内层即 MATCH_PARENT - 2*8dp）。
        val root = FrameLayout(context)
        val sideGap = OverlayUi.dp(context, 8f)
        root.setPadding(sideGap, 0, sideGap, 0)

        val bar = LinearLayout(context)
        bar.orientation = LinearLayout.VERTICAL
        bar.setPadding(
            OverlayUi.dp(context, 12f), OverlayUi.dp(context, 12f),
            OverlayUi.dp(context, 12f), OverlayUi.dp(context, 12f)
        )
        bar.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = OverlayUi.dp(context, 12f).toFloat()
            setColor(0xE6222222.toInt())
            setStroke(OverlayUi.dp(context, 1f), 0x66FFFFFF)
        }
        root.addView(
            bar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val title = TextView(context).apply {
            text = "点击器模式"
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        bar.addView(title, OverlayUi.matchWrap())

        val statusView = TextView(context).apply {
            text = "就绪"
            setTextColor(0xFFB0BEC5.toInt())
            textSize = 12f
            setPadding(0, OverlayUi.dp(context, 6f), 0, OverlayUi.dp(context, 6f))
        }
        bar.addView(statusView, OverlayUi.matchWrap())

        val startButton = OverlayUi.button(context, "开始") { callbacks.onStartClicker() }
        val stopButton = OverlayUi.button(context, "停止") { callbacks.onStopClicker() }
        stopButton.isEnabled = false
        val clearButton = OverlayUi.button(context, "清空") { callbacks.onClearPoints() }

        val row1 = OverlayUi.row(context, startButton, stopButton, clearButton)
        OverlayUi.addRow(context, bar, row1)

        val row2 = OverlayUi.row(
            context,
            OverlayUi.button(context, "循环设置") { callbacks.onLoopSettings(currentConfig(root)) },
            OverlayUi.button(context, "导出脚本") { callbacks.onExportScript() },
            OverlayUi.button(context, "退出") { callbacks.onExitClicker() }
        )
        OverlayUi.addRow(context, bar, row2)

        root.tag = ControlHolder(statusView, startButton, stopButton)
        return root
    }

    /** 重建标记视图（点数或位置变化时调用）。 */
    fun setPoints(markerLayer: View, points: List<ClickerPoint>) {
        val holder = markerLayer.tag as? MarkerHolder ?: return
        holder.points = points
        holder.container.removeAllViews()
        points.forEachIndexed { index, point ->
            holder.container.addView(buildMarker(markerLayer.context, index + 1, point, holder))
        }
    }

    /** 更新控制条上的状态文字。 */
    fun setStatus(controlBar: View, text: String) {
        (controlBar.tag as? ControlHolder)?.statusView?.text = text
    }

    /** 更新控制条持有的配置引用，并同步刷新状态与循环信息。 */
    fun setConfig(controlBar: View, config: ClickerConfig) {
        val holder = controlBar.tag as? ControlHolder ?: return
        holder.config = config
        setStatus(
            controlBar,
            formatStatus(ClickerEngine.state.value, config.points.size, config)
        )
    }

    /** 运行中禁用「开始」、启用「停止」等按钮态。 */
    fun setRunning(controlBar: View, running: Boolean) {
        val holder = controlBar.tag as? ControlHolder ?: return
        holder.startButton.isEnabled = !running
        holder.stopButton.isEnabled = running
    }

    /** 把 ClickerState 渲染为状态文字。 */
    fun formatStatus(state: ClickerState, pointCount: Int, config: ClickerConfig): String {
        if (pointCount <= 0) {
            return "未添加点击点，点屏幕上任意位置添加"
        }
        val loopText = when {
            config.loopInfinite -> "无限"
            config.loopCount <= 1 -> "单次"
            else -> "${config.loopCount}次"
        }
        return when (state) {
            is ClickerState.Idle -> "就绪 ｜ $pointCount 个点 ｜ 循环：$loopText"
            is ClickerState.Running -> {
                val total = if (state.totalLoops == -1) "∞" else state.totalLoops.toString()
                "运行中 ｜ 第${state.loopIndex + 1}/${total}轮 ｜ " +
                    "第${state.pointIndex + 1}/${state.totalPoints}点 ｜ " +
                    "$pointCount 个点 ｜ 循环：$loopText"
            }
            is ClickerState.Finished -> "结束：${state.message ?: "完成"}"
        }
    }

    /** 可获焦小窗：编辑单个点击点（间隔/触摸时长，支持删除）。 */
    fun showPointEditor(
        context: Context,
        windowManager: WindowManager,
        point: ClickerPoint,
        onConfirm: (ClickerPoint) -> Unit,
        onDelete: () -> Unit
    ) {
        hideEditors(windowManager)

        val metrics = context.resources.displayMetrics
        val root = LinearLayout(context)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(
            OverlayUi.dp(context, 14f), OverlayUi.dp(context, 12f),
            OverlayUi.dp(context, 14f), OverlayUi.dp(context, 12f)
        )
        root.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = OverlayUi.dp(context, 12f).toFloat()
            setColor(0xF0222222.toInt())
            setStroke(OverlayUi.dp(context, 1f), 0x66FFFFFF)
        }

        val title = TextView(context).apply {
            text = "编辑点击点"
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        root.addView(title, OverlayUi.matchWrap())

        val delayEdit = OverlayUi.numberField(context, point.delayBeforeMs.toString())
        val durationEdit = OverlayUi.numberField(context, point.touchDurationMs.toString())
        addField(context, root, "间隔(ms)", delayEdit)
        addField(context, root, "触摸时长(ms)", durationEdit)

        val buttons = LinearLayout(context)
        buttons.orientation = LinearLayout.HORIZONTAL
        val delete = Button(context).apply {
            text = "删除"
            textSize = 13f
            isAllCaps = false
            setOnClickListener {
                onDelete()
                hideEditors(windowManager)
            }
        }
        val cancel = Button(context).apply {
            text = "取消"
            textSize = 13f
            isAllCaps = false
            setOnClickListener { hideEditors(windowManager) }
        }
        val confirm = Button(context).apply {
            text = "确定"
            textSize = 13f
            isAllCaps = false
            setOnClickListener {
                val delay = delayEdit.text.toString().trim().toLongOrNull()
                if (delay == null) {
                    Toast.makeText(context, "间隔输入无效，已按原值处理", Toast.LENGTH_SHORT).show()
                }
                val duration = durationEdit.text.toString().trim().toLongOrNull()
                if (duration == null) {
                    Toast.makeText(context, "触摸时长输入无效，已按原值处理", Toast.LENGTH_SHORT).show()
                }
                onConfirm(
                    point.copy(
                        delayBeforeMs = delay ?: point.delayBeforeMs,
                        touchDurationMs = duration ?: point.touchDurationMs
                    )
                )
                hideEditors(windowManager)
            }
        }
        buttons.addView(delete, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val cancelLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        cancelLp.marginStart = OverlayUi.dp(context, 8f)
        buttons.addView(cancel, cancelLp)
        val confirmLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        confirmLp.marginStart = OverlayUi.dp(context, 8f)
        buttons.addView(confirm, confirmLp)
        val buttonsLp = OverlayUi.matchWrap()
        buttonsLp.topMargin = OverlayUi.dp(context, 8f)
        root.addView(buttons, buttonsLp)

        val width = OverlayUi.dp(context, 300f)
            .coerceAtMost((metrics.widthPixels - OverlayUi.dp(context, 32f)).coerceAtLeast(OverlayUi.dp(context, 200f)))
        val params = focusableParams(width, metrics, OverlayUi.dp(context, 60f))
        try {
            windowManager.addView(root, params)
            pointEditorView = root
        } catch (e: Exception) {
            pointEditorView = null
        }
    }

    /** 可获焦小窗：循环设置（无限/次数/循环间隔）。 */
    fun showLoopSettings(
        context: Context,
        windowManager: WindowManager,
        config: ClickerConfig,
        onConfirm: (loopInfinite: Boolean, loopCount: Int, loopIntervalMs: Long) -> Unit
    ) {
        hideEditors(windowManager)

        val metrics = context.resources.displayMetrics
        val root = LinearLayout(context)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(
            OverlayUi.dp(context, 14f), OverlayUi.dp(context, 12f),
            OverlayUi.dp(context, 14f), OverlayUi.dp(context, 12f)
        )
        root.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = OverlayUi.dp(context, 12f).toFloat()
            setColor(0xF0222222.toInt())
            setStroke(OverlayUi.dp(context, 1f), 0x66FFFFFF)
        }

        val title = TextView(context).apply {
            text = "循环设置"
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        root.addView(title, OverlayUi.matchWrap())

        val infiniteBox = CheckBox(context).apply {
            text = "无限循环"
            setTextColor(0xFFB0BEC5.toInt())
            textSize = 13f
            isChecked = config.loopInfinite
        }
        val boxLp = OverlayUi.matchWrap()
        boxLp.topMargin = OverlayUi.dp(context, 4f)
        root.addView(infiniteBox, boxLp)

        val countEdit = OverlayUi.numberField(context, config.loopCount.toString())
        val intervalEdit = OverlayUi.numberField(context, config.loopIntervalMs.toString())
        addField(context, root, "循环次数", countEdit)
        addField(context, root, "循环间隔(ms)", intervalEdit)

        val buttons = LinearLayout(context)
        buttons.orientation = LinearLayout.HORIZONTAL
        val cancel = Button(context).apply {
            text = "取消"
            textSize = 13f
            isAllCaps = false
            setOnClickListener { hideEditors(windowManager) }
        }
        val confirm = Button(context).apply {
            text = "确定"
            textSize = 13f
            isAllCaps = false
            setOnClickListener {
                val count = countEdit.text.toString().trim().toIntOrNull() ?: config.loopCount
                val interval = intervalEdit.text.toString().trim().toLongOrNull() ?: config.loopIntervalMs
                onConfirm(
                    infiniteBox.isChecked,
                    count.coerceAtLeast(1),
                    interval.coerceAtLeast(0L)
                )
                hideEditors(windowManager)
            }
        }
        val cancelLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        buttons.addView(cancel, cancelLp)
        val confirmLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        confirmLp.marginStart = OverlayUi.dp(context, 8f)
        buttons.addView(confirm, confirmLp)
        val buttonsLp = OverlayUi.matchWrap()
        buttonsLp.topMargin = OverlayUi.dp(context, 8f)
        root.addView(buttons, buttonsLp)

        val width = OverlayUi.dp(context, 300f)
            .coerceAtMost((metrics.widthPixels - OverlayUi.dp(context, 32f)).coerceAtLeast(OverlayUi.dp(context, 200f)))
        val params = focusableParams(width, metrics, OverlayUi.dp(context, 60f))
        try {
            windowManager.addView(root, params)
            loopSettingsView = root
        } catch (e: Exception) {
            loopSettingsView = null
        }
    }

    /** 隐藏并移除两个编辑小窗。 */
    fun hideEditors(windowManager: WindowManager) {
        pointEditorView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                // 窗口失效时忽略
            }
        }
        pointEditorView = null
        loopSettingsView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                // 窗口失效时忽略
            }
        }
        loopSettingsView = null
    }

    // ---- 内部构建 ----

    /** 构建单个标记（圆形序号），绝对定位并绑定拖动 / 点击编辑手势。 */
    private fun buildMarker(
        context: Context,
        number: Int,
        point: ClickerPoint,
        holder: MarkerHolder
    ): View {
        val size = OverlayUi.dp(context, MARKER_SIZE_DP)
        val radius = size / 2f
        val marker = TextView(context)
        marker.text = number.toString()
        marker.setTextColor(Color.WHITE)
        marker.textSize = 14f
        marker.gravity = Gravity.CENTER
        marker.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xCC1E88E5.toInt())
            setStroke(OverlayUi.dp(context, 2f), Color.WHITE)
        }
        marker.isClickable = true
        val lp = FrameLayout.LayoutParams(size, size)
        lp.leftMargin = (point.x - radius).toInt().coerceAtLeast(0)
        lp.topMargin = (point.y - radius).toInt().coerceAtLeast(0)
        marker.layoutParams = lp

        var downRawX = 0f
        var downRawY = 0f
        var startLeft = 0
        var startTop = 0
        var dragging = false
        marker.setOnTouchListener { v, event ->
            val params = v.layoutParams as? FrameLayout.LayoutParams
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startLeft = params?.leftMargin ?: 0
                    startTop = params?.topMargin ?: 0
                    dragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (hypot(dx, dy) > DRAG_SLOP) {
                        dragging = true
                    }
                    // 拖动期间只移动自己的视图，绝不回写引擎：回写会更新 config，
                    // 触发 setPoints 全量重建并销毁本视图，从而丢失正在进行的触摸手势。
                    if (dragging && params != null) {
                        params.leftMargin = (startLeft + dx).toInt().coerceAtLeast(0)
                        params.topMargin = (startTop + dy).toInt().coerceAtLeast(0)
                        v.layoutParams = params
                    }
                    // 消费事件，避免冒泡到标记层导致误加点。
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        // 松手时一次性回写引擎。坐标要用「标记圆心 + 位移」，
                        // 不能用 rawX/rawY：手指可能抓在圆边而非圆心，用 raw 会让标记跳到手指下。
                        val endDx = event.rawX - downRawX
                        val endDy = event.rawY - downRawY
                        holder.callbacks.onMovePoint(
                            point.id,
                            (point.x + endDx).coerceAtLeast(0f),
                            (point.y + endDy).coerceAtLeast(0f)
                        )
                    } else {
                        // 未拖动视为点击，进入点编辑。
                        holder.callbacks.onEditPoint(point)
                    }
                    dragging = false
                    // 消费事件，避免冒泡到标记层导致误加点。
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    // 手势被取消：回退到起始位置，不回写引擎，保持与引擎数据一致。
                    if (dragging && params != null) {
                        params.leftMargin = startLeft
                        params.topMargin = startTop
                        v.layoutParams = params
                    }
                    dragging = false
                    // 消费事件，避免冒泡到标记层导致误加点。
                    true
                }

                else -> true
            }
        }
        return marker
    }

    /** 读取控制条当前持有的配置（供「循环设置」按钮回传）。 */
    private fun currentConfig(controlBar: View): ClickerConfig =
        (controlBar.tag as? ControlHolder)?.config ?: ClickerConfig()

    /** 可获焦小窗统一的窗口参数：仅 NOT_TOUCH_MODAL，绝不加 NOT_FOCUSABLE。 */
    private fun focusableParams(
        width: Int,
        metrics: android.util.DisplayMetrics,
        y: Int
    ): WindowManager.LayoutParams {
        val params = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 仅 NOT_TOUCH_MODAL，绝不加 NOT_FOCUSABLE，否则无法输入。
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
        params.x = ((metrics.widthPixels - width) / 2).coerceAtLeast(0)
        params.y = y
        return params
    }

    private fun addField(context: Context, parent: LinearLayout, label: String, edit: EditText) {
        val rowView = LinearLayout(context)
        rowView.orientation = LinearLayout.HORIZONTAL
        val tv = TextView(context).apply {
            text = label
            setTextColor(0xFFB0BEC5.toInt())
            textSize = 13f
            gravity = Gravity.CENTER_VERTICAL
            minWidth = OverlayUi.dp(context, 96f)
        }
        rowView.addView(
            tv,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        rowView.addView(
            edit,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        val lp = OverlayUi.matchWrap()
        lp.topMargin = OverlayUi.dp(context, 4f)
        parent.addView(rowView, lp)
    }

    /** 标记层内部状态容器，挂在标记层 View 的 tag 上。 */
    private class MarkerHolder(val container: FrameLayout, val callbacks: Callbacks) {
        var points: List<ClickerPoint> = emptyList()
    }

    /** 控制条内部状态容器，挂在控制条 View 的 tag 上。 */
    private class ControlHolder(
        val statusView: TextView,
        val startButton: Button,
        val stopButton: Button
    ) {
        var config: ClickerConfig = ClickerConfig()
    }
}