package com.autoclicker.core.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.autoclicker.core.runner.RunnerState
import com.autoclicker.core.script.Script
import com.autoclicker.core.script.Step
import com.autoclicker.core.script.describe
import com.autoclicker.core.script.typeLabel
import kotlin.math.roundToInt

/**
 * 悬浮窗的视图构建与状态渲染。仅负责纯代码构建 android.widget 视图，
 * 交互逻辑通过 [PanelCallbacks] 回调给 OverlayService。
 */
internal object OverlayUi {

    /** 步骤行普通背景色。 */
    private const val COLOR_ROW_NORMAL = 0x33FFFFFF

    /** 步骤行高亮（当前执行）背景色。 */
    private const val COLOR_ROW_HIGHLIGHT = 0x5534B0FF

    /** 面板按钮与步骤行回调。 */
    interface PanelCallbacks {
        fun onStartClick(script: Script?)
        fun onPauseClick()
        fun onResumeClick()
        fun onStopClick()
        fun onStartRecord()
        fun onStopRecord()
        fun onRefreshScripts()
        fun onOpenApp()
        fun onCloseOverlay()
        fun onScriptSelected(script: Script?)
        fun onEditStep(script: Script?, index: Int)
        fun onMoveStep(script: Script?, index: Int, delta: Int)
        fun onDeleteStep(script: Script?, index: Int)
        fun onDuplicateStep(script: Script?, index: Int)
        fun onCaptureTemplate()
    }

    /** 直径 48dp 的圆形悬浮球。 */
    fun createBall(context: Context): View {
        val size = dp(context, 48f)
        val ball = TextView(context)
        ball.text = "点"
        ball.setTextColor(Color.WHITE)
        ball.textSize = 16f
        ball.gravity = Gravity.CENTER
        ball.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xCC1E88E5.toInt())
            setStroke(dp(context, 1f), 0x8834B0FF.toInt())
        }
        ball.layoutParams = ViewGroup.LayoutParams(size, size)
        return ball
    }

    /** 竖直控制面板。 */
    fun createPanel(context: Context, callbacks: PanelCallbacks): View {
        val root = LinearLayout(context)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(context, 12f), dp(context, 12f), dp(context, 12f), dp(context, 12f))
        root.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, 12f).toFloat()
            setColor(0xE6222222.toInt())
            setStroke(dp(context, 1f), 0x66FFFFFF)
        }

        val title = TextView(context).apply {
            text = "自动点击助手"
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        root.addView(title, matchWrap())

        val statusView = TextView(context).apply {
            text = "就绪"
            setTextColor(0xFFB0BEC5.toInt())
            textSize = 12f
            setPadding(0, dp(context, 6f), 0, dp(context, 6f))
        }
        root.addView(statusView, matchWrap())

        val spinner = Spinner(context)
        val adapter = ScriptAdapter(context)
        spinner.adapter = adapter
        root.addView(spinner, matchWrap())

        val loopInfoView = infoLine(context)
        root.addView(loopInfoView, matchWrap())

        val jitterInfoView = infoLine(context)
        root.addView(jitterInfoView, matchWrap())

        val stepContainer = LinearLayout(context)
        stepContainer.orientation = LinearLayout.VERTICAL
        val stepScroll = ScrollView(context)
        stepScroll.addView(
            stepContainer,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        val scrollLp = matchWrap()
        scrollLp.height = dp(context, 180f)
        scrollLp.topMargin = dp(context, 6f)
        root.addView(stepScroll, scrollLp)

        val holder = PanelHolder(
            statusView, spinner, adapter, loopInfoView, jitterInfoView, stepContainer, callbacks
        )
        root.tag = holder

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                holder.selectedIndex = position
                callbacks.onScriptSelected(holder.selectedScript)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                holder.selectedIndex = -1
            }
        }

        val row1 = row(
            context,
            button(context, "开始") { callbacks.onStartClick(holder.selectedScript) },
            button(context, "暂停") { callbacks.onPauseClick() },
            button(context, "继续") { callbacks.onResumeClick() },
            button(context, "停止") { callbacks.onStopClick() }
        )
        addRow(context, root, row1)

        val row2 = row(
            context,
            button(context, "开始录制") { callbacks.onStartRecord() },
            button(context, "结束录制") { callbacks.onStopRecord() }
        )
        addRow(context, root, row2)

        val row3 = row(
            context,
            button(context, "刷新") { callbacks.onRefreshScripts() },
            button(context, "截屏取模板") { callbacks.onCaptureTemplate() },
            button(context, "打开应用") { callbacks.onOpenApp() },
            button(context, "关闭") { callbacks.onCloseOverlay() }
        )
        addRow(context, root, row3)

        setScripts(root, emptyList())
        return root
    }

    /** 全屏坐标拾取覆盖层。 */
    fun createPickLayer(context: Context): View {
        val root = FrameLayout(context)
        root.setBackgroundColor(0x66000000)
        root.isClickable = true
        val hint = TextView(context).apply {
            text = "点击要拾取的位置"
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
        }
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        )
        root.addView(hint, lp)
        return root
    }

    /** 更新面板中的脚本列表（保留原有选中项，若不存在则选中第一项）。 */
    fun setScripts(panel: View, scripts: List<Script>) {
        val holder = panel.tag as? PanelHolder ?: return
        val previousId = holder.selectedScript?.id
        holder.adapter.clear()
        holder.scripts = scripts
        if (scripts.isEmpty()) {
            holder.selectedIndex = -1
            holder.adapter.add("（无脚本）")
        } else {
            holder.adapter.addAll(scripts.map { it.name })
            val index = scripts.indexOfFirst { it.id == previousId }
            holder.selectedIndex = if (index >= 0) index else 0
            holder.spinner.setSelection(holder.selectedIndex)
        }
        holder.adapter.notifyDataSetChanged()
    }

    /** 返回面板当前选中的脚本。 */
    fun currentScript(panel: View): Script? = (panel.tag as? PanelHolder)?.selectedScript

    /**
     * 用最新的 [script] 对象替换内部脚本列表中同 id 的项，
     * 保证步骤行按钮回调拿到的脚本与当前展示一致。
     */
    fun syncSelectedScript(panel: View, script: Script) {
        val holder = panel.tag as? PanelHolder ?: return
        val index = holder.scripts.indexOfFirst { it.id == script.id }
        if (index < 0) return
        val updated = holder.scripts.toMutableList()
        updated[index] = script
        holder.scripts = updated
    }

    /** 重建步骤列表行。[steps] 的下标与行内按钮回调下标一致。 */
    fun setSteps(panel: View, steps: List<Step>) {
        val holder = panel.tag as? PanelHolder ?: return
        holder.stepContainer.removeAllViews()
        holder.stepRows.clear()
        val context = panel.context
        if (steps.isEmpty()) {
            val empty = TextView(context).apply {
                text = "（无步骤）"
                setTextColor(0xFF90A4AE.toInt())
                textSize = 12f
                setPadding(dp(context, 4f), dp(context, 8f), 0, dp(context, 8f))
            }
            holder.stepContainer.addView(empty, matchWrap())
            return
        }
        val script = holder.selectedScript
        steps.forEachIndexed { index, step ->
            val rowView = buildStepRow(holder, script, index, step)
            val lp = matchWrap()
            lp.topMargin = dp(context, 2f)
            holder.stepContainer.addView(rowView, lp)
            holder.stepRows.add(rowView)
        }
    }

    /** 高亮当前执行行；[index] 小于 0 时清除全部高亮。 */
    fun highlightStep(panel: View, index: Int) {
        val holder = panel.tag as? PanelHolder ?: return
        val context = panel.context
        holder.stepRows.forEachIndexed { i, row ->
            row.background = rowBackground(
                context,
                if (i == index) COLOR_ROW_HIGHLIGHT else COLOR_ROW_NORMAL
            )
        }
    }

    /** 更新面板状态行文字。 */
    fun setStatus(panel: View, text: String) {
        (panel.tag as? PanelHolder)?.statusView?.text = text
    }

    /** 渲染脚本的循环信息行与拟人化信息行。 */
    fun setScriptInfo(panel: View, script: Script?) {
        val holder = panel.tag as? PanelHolder ?: return
        if (script == null) {
            holder.loopInfoView.text = "循环：单次"
            holder.jitterInfoView.text = "拟人化：关闭"
            return
        }
        val loop = when {
            script.loopInfinite -> "循环：无限"
            script.loopCount <= 1 -> "循环：单次"
            else -> "循环：${script.loopCount} 次"
        }
        val interval = if (script.loopIntervalMs > 0) "　间隔 ${script.loopIntervalMs}ms" else ""
        holder.loopInfoView.text = loop + interval

        holder.jitterInfoView.text =
            if (script.jitterRadiusPx == 0 && script.jitterDelayPercent == 0) {
                "拟人化：关闭"
            } else {
                "拟人化：坐标±${script.jitterRadiusPx}px 延时±${script.jitterDelayPercent}%"
            }
    }

    /** 把执行状态、录制状态渲染为状态行文字。 */
    fun formatStatus(state: RunnerState, recording: Boolean, stepCount: Int): String {
        val base = when (state) {
            is RunnerState.Idle -> "就绪"
            is RunnerState.Running ->
                "运行中：" + progress(
                    state.scriptName, state.stepIndex, state.totalSteps,
                    state.loopIndex, state.totalLoops
                ) + "｜${state.stepText}"
            is RunnerState.Paused ->
                "已暂停：" + progress(
                    state.scriptName, state.stepIndex, state.totalSteps,
                    state.loopIndex, state.totalLoops
                ) + "｜${state.stepText}"
            is RunnerState.Finished -> {
                val result = if (state.success) "成功" else "失败"
                val detail = state.message?.let { "：$it" } ?: ""
                "结束：${state.scriptName}（$result$detail）"
            }
        }
        return if (recording) "$base｜录制中：$stepCount 步" else base
    }

    /** dp 转像素。 */
    fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density).roundToInt()

    // ---- 内部构建 ----

    private fun progress(
        name: String,
        stepIndex: Int,
        totalSteps: Int,
        loopIndex: Int,
        totalLoops: Int
    ): String = "$name 第${stepIndex + 1}/${totalSteps}步" + loopSegment(loopIndex, totalLoops)

    private fun loopSegment(loopIndex: Int, totalLoops: Int): String = when {
        totalLoops == -1 -> " 无限循环"
        totalLoops <= 1 -> ""
        else -> " 第${loopIndex + 1}/$totalLoops 轮"
    }

    private fun infoLine(context: Context): TextView = TextView(context).apply {
        setTextColor(0xFFB0BEC5.toInt())
        textSize = 12f
        setPadding(0, dp(context, 2f), 0, 0)
    }

    private fun buildStepRow(
        holder: PanelHolder,
        script: Script?,
        index: Int,
        step: Step
    ): View {
        val context = holder.statusView.context
        val rowView = LinearLayout(context)
        rowView.orientation = LinearLayout.HORIZONTAL
        rowView.setPadding(dp(context, 6f), dp(context, 4f), dp(context, 6f), dp(context, 4f))
        rowView.background = rowBackground(context, COLOR_ROW_NORMAL)

        val label = TextView(context).apply {
            text = "${index + 1}. ${step.typeLabel} ${step.describe()}"
            setTextColor(0xFFEEEEEE.toInt())
            textSize = 12f
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
        }
        rowView.addView(
            label,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )

        addStepButton(context, rowView) { holder.callbacks.onEditStep(script, index) }.text = "改"
        addStepButton(context, rowView) { holder.callbacks.onDuplicateStep(script, index) }.text = "复制"
        addStepButton(context, rowView) { holder.callbacks.onMoveStep(script, index, -1) }.text = "↑"
        addStepButton(context, rowView) { holder.callbacks.onMoveStep(script, index, 1) }.text = "↓"
        addStepButton(context, rowView) { holder.callbacks.onDeleteStep(script, index) }.text = "删"
        return rowView
    }

    private fun addStepButton(context: Context, parent: LinearLayout, onClick: () -> Unit): Button {
        val button = Button(context)
        button.textSize = 11f
        button.isAllCaps = false
        button.minWidth = 0
        button.minimumWidth = 0
        button.setPadding(dp(context, 2f), 0, dp(context, 2f), 0)
        button.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.marginStart = dp(context, 3f)
        parent.addView(button, lp)
        return button
    }

    private fun rowBackground(context: Context, color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, 6f).toFloat()
            setColor(color)
        }

    private fun matchWrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

    private fun addRow(context: Context, root: LinearLayout, row: View) {
        val lp = matchWrap()
        lp.topMargin = dp(context, 6f)
        root.addView(row, lp)
    }

    private fun row(context: Context, vararg views: View): LinearLayout {
        val container = LinearLayout(context)
        container.orientation = LinearLayout.HORIZONTAL
        views.forEachIndexed { index, view ->
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            if (index > 0) lp.marginStart = dp(context, 4f)
            container.addView(view, lp)
        }
        return container
    }

    private fun button(context: Context, text: String, onClick: () -> Unit): Button {
        val button = Button(context)
        button.text = text
        button.textSize = 12f
        button.isAllCaps = false
        button.minWidth = 0
        button.minimumWidth = 0
        button.setPadding(0, button.paddingTop, 0, button.paddingBottom)
        button.setOnClickListener { onClick() }
        return button
    }

    /** 面板内部状态容器，挂在面板 View 的 tag 上。 */
    private class PanelHolder(
        val statusView: TextView,
        val spinner: Spinner,
        val adapter: ArrayAdapter<String>,
        val loopInfoView: TextView,
        val jitterInfoView: TextView,
        val stepContainer: LinearLayout,
        val callbacks: PanelCallbacks
    ) {
        var scripts: List<Script> = emptyList()
        var selectedIndex: Int = -1
        val stepRows: MutableList<View> = mutableListOf()
        val selectedScript: Script? get() = scripts.getOrNull(selectedIndex)
    }

    /** 深色面板上用浅色文字渲染 Spinner 项。 */
    private class ScriptAdapter(context: Context) :
        ArrayAdapter<String>(context, android.R.layout.simple_spinner_item) {

        init {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            return colorize(super.getView(position, convertView, parent))
        }

        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
            return colorize(super.getDropDownView(position, convertView, parent))
        }

        private fun colorize(view: View): View {
            (view as? TextView)?.setTextColor(0xFFEEEEEE.toInt())
            return view
        }
    }
}