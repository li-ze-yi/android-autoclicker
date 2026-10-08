package com.autoclicker.core.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import com.autoclicker.core.runner.RunnerState
import com.autoclicker.core.script.Script
import kotlin.math.roundToInt

/**
 * 悬浮窗的视图构建与状态渲染。仅负责纯代码构建 android.widget 视图，
 * 交互逻辑通过 [PanelCallbacks] 回调给 OverlayService。
 */
internal object OverlayUi {

    /** 面板按钮回调。 */
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

        val holder = PanelHolder(statusView, spinner, adapter)
        root.tag = holder
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                holder.selectedIndex = position
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

    /** 更新面板状态行文字。 */
    fun setStatus(panel: View, text: String) {
        (panel.tag as? PanelHolder)?.statusView?.text = text
    }

    /** 把执行状态、录制状态渲染为状态行文字。 */
    fun formatStatus(state: RunnerState, recording: Boolean, stepCount: Int): String {
        val base = when (state) {
            is RunnerState.Idle -> "就绪"
            is RunnerState.Running ->
                "运行中：${state.scriptName} (${state.stepIndex + 1}/${state.totalSteps})"
            is RunnerState.Paused ->
                "已暂停：${state.scriptName} (${state.stepIndex + 1}/${state.totalSteps})"
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
        val adapter: ArrayAdapter<String>
    ) {
        var scripts: List<Script> = emptyList()
        var selectedIndex: Int = -1
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