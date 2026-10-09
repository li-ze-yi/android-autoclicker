package com.autoclicker.core.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.autoclicker.core.script.Step
import com.autoclicker.core.script.describe
import com.autoclicker.core.script.typeLabel

/**
 * 单步参数编辑悬浮窗。
 *
 * 这是一个**可获焦点**的独立窗口（不加 FLAG_NOT_FOCUSABLE），因此能弹出软键盘。
 * 由 [OverlayService] 在用户点击步骤行的「改」时调用。
 */
internal object OverlayStepEditor {

    private var editorView: View? = null

    /** 显示编辑窗口。[onConfirm] 在点击「确定」且解析成功后回调。 */
    fun show(
        context: Context,
        windowManager: WindowManager,
        step: Step,
        onConfirm: (Step) -> Unit
    ) {
        // 先移除可能存在的旧窗口，避免重复添加。
        hide(windowManager)

        val root = LinearLayout(context)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(OverlayUi.dp(context, 14f), OverlayUi.dp(context, 12f), OverlayUi.dp(context, 14f), OverlayUi.dp(context, 12f))
        root.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = OverlayUi.dp(context, 12f).toFloat()
            setColor(0xF0222222.toInt())
            setStroke(OverlayUi.dp(context, 1f), 0x66FFFFFF)
        }

        val title = TextView(context).apply {
            text = "编辑步骤：${step.typeLabel}"
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        root.addView(title, OverlayUi.matchWrap())

        val fields = LinearLayout(context)
        fields.orientation = LinearLayout.VERTICAL

        val inputs = LinkedHashMap<String, EditText>()

        fun f(key: String): Float? = inputs[key]?.text?.toString()?.trim()?.toFloatOrNull()
        fun l(key: String): Long? = inputs[key]?.text?.toString()?.trim()?.toLongOrNull()
        fun i(key: String): Int? = inputs[key]?.text?.toString()?.trim()?.toIntOrNull()
        fun s(key: String): String = inputs[key]?.text?.toString()?.trim() ?: ""
        fun opt(key: String): String? = s(key).ifBlank { null }

        // 通用延时解析：非数字或负数时提示并按 0 处理，避免解析失败导致整步丢失。
        fun parsedDelay(): Long {
            val raw = inputs["delay"]?.text?.toString()?.trim().orEmpty()
            val value = raw.toLongOrNull()
            if (value == null || value < 0L) {
                Toast.makeText(context, "延时输入无效，已按 0 处理", Toast.LENGTH_SHORT).show()
                return 0L
            }
            return value
        }

        fun hintLabel(hint: String) {
            val tv = TextView(context).apply {
                text = hint
                setTextColor(0xFF90A4AE.toInt())
                textSize = 13f
                setPadding(0, OverlayUi.dp(context, 6f), 0, 0)
            }
            fields.addView(tv, OverlayUi.matchWrap())
        }

        fun addField(key: String, label: String, initial: String, numeric: Boolean) {
            val edit = if (numeric) OverlayUi.numberField(context, initial) else OverlayUi.textField(context, initial)
            inputs[key] = edit
            val rowView = LinearLayout(context)
            rowView.orientation = LinearLayout.HORIZONTAL
            val tv = TextView(context).apply {
                text = label
                setTextColor(0xFFB0BEC5.toInt())
                textSize = 13f
                gravity = Gravity.CENTER_VERTICAL
                minWidth = OverlayUi.dp(context, 96f)
            }
            rowView.addView(tv, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            rowView.addView(edit, LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ))
            val lp = OverlayUi.matchWrap()
            lp.topMargin = OverlayUi.dp(context, 4f)
            fields.addView(rowView, lp)
        }

        // 按类型渲染字段，并返回读取器；解析失败返回 null。
        val readStep: () -> Step? = when (step) {
            is Step.Tap -> {
                addField("x", "X", step.x.toString(), true)
                addField("y", "Y", step.y.toString(), true)
                val reader: () -> Step? = {
                    val x = f("x")
                    val y = f("y")
                    if (x == null || y == null) null
                    else step.copy(x = x, y = y, note = s("note"), delayBeforeMs = parsedDelay())
                }
                reader
            }

            is Step.LongPress -> {
                addField("x", "X", step.x.toString(), true)
                addField("y", "Y", step.y.toString(), true)
                addField("duration", "时长(ms)", step.durationMs.toString(), true)
                val reader: () -> Step? = {
                    val x = f("x")
                    val y = f("y")
                    val d = l("duration")
                    if (x == null || y == null || d == null) null
                    else step.copy(x = x, y = y, durationMs = d, note = s("note"), delayBeforeMs = parsedDelay())
                }
                reader
            }

            is Step.Swipe -> {
                addField("x1", "X1", step.x1.toString(), true)
                addField("y1", "Y1", step.y1.toString(), true)
                addField("x2", "X2", step.x2.toString(), true)
                addField("y2", "Y2", step.y2.toString(), true)
                addField("duration", "时长(ms)", step.durationMs.toString(), true)
                val reader: () -> Step? = {
                    val x1 = f("x1")
                    val y1 = f("y1")
                    val x2 = f("x2")
                    val y2 = f("y2")
                    val d = l("duration")
                    if (x1 == null || y1 == null || x2 == null || y2 == null || d == null) null
                    else step.copy(x1 = x1, y1 = y1, x2 = x2, y2 = y2, durationMs = d, note = s("note"), delayBeforeMs = parsedDelay())
                }
                reader
            }

            is Step.Input -> {
                addField("text", "文本", step.text, false)
                val reader: () -> Step? = {
                    step.copy(text = s("text"), note = s("note"), delayBeforeMs = parsedDelay())
                }
                reader
            }

            is Step.Wait -> {
                addField("duration", "时长(ms)", step.durationMs.toString(), true)
                val reader: () -> Step? = {
                    val d = l("duration")
                    if (d == null) null else step.copy(durationMs = d, note = s("note"), delayBeforeMs = parsedDelay())
                }
                reader
            }

            is Step.LaunchApp -> {
                addField("pkg", "包名", step.packageName, false)
                val reader: () -> Step? = {
                    step.copy(packageName = s("pkg"), note = s("note"), delayBeforeMs = parsedDelay())
                }
                reader
            }

            is Step.WaitForElement -> {
                addField("text", "文字", step.text ?: "", false)
                addField("viewId", "资源ID", step.viewId ?: "", false)
                addField("contentDesc", "描述", step.contentDesc ?: "", false)
                addField("className", "类名", step.className ?: "", false)
                addField("timeout", "超时(ms)", step.timeoutMs.toString(), true)
                val reader: () -> Step? = {
                    val t = l("timeout")
                    if (t == null) null
                    else step.copy(
                        text = opt("text"),
                        viewId = opt("viewId"),
                        contentDesc = opt("contentDesc"),
                        className = opt("className"),
                        timeoutMs = t,
                        note = s("note"),
                        delayBeforeMs = parsedDelay()
                    )
                }
                reader
            }

            is Step.Back -> {
                hintLabel("返回键无需参数")
                val reader: () -> Step? = {
                    step.copy(note = s("note"), delayBeforeMs = parsedDelay())
                }
                reader
            }

            is Step.Home -> {
                hintLabel("主页键无需参数")
                val reader: () -> Step? = {
                    step.copy(note = s("note"), delayBeforeMs = parsedDelay())
                }
                reader
            }

            is Step.Burst -> {
                addField("x", "X", step.x.toString(), true)
                addField("y", "Y", step.y.toString(), true)
                addField("count", "次数", step.count.toString(), true)
                addField("interval", "间隔(ms)", step.intervalMs.toString(), true)
                addField("touch", "触摸时长(ms)", step.touchDurationMs.toString(), true)
                val reader: () -> Step? = {
                    val x = f("x")
                    val y = f("y")
                    val c = i("count")
                    val iv = l("interval")
                    val td = l("touch")
                    if (x == null || y == null || c == null || iv == null || td == null) null
                    else step.copy(
                        x = x, y = y, count = c, intervalMs = iv, touchDurationMs = td,
                        note = s("note"), delayBeforeMs = parsedDelay()
                    )
                }
                reader
            }

            is Step.TapElement -> {
                addField("text", "文字", step.text ?: "", false)
                addField("viewId", "资源ID", step.viewId ?: "", false)
                addField("contentDesc", "描述", step.contentDesc ?: "", false)
                addField("className", "类名", step.className ?: "", false)
                addField("index", "序号index", step.index.toString(), true)
                addField("timeout", "超时(ms)", step.timeoutMs.toString(), true)
                val reader: () -> Step? = {
                    val idx = i("index")
                    val t = l("timeout")
                    if (idx == null || t == null) null
                    else step.copy(
                        text = opt("text"),
                        viewId = opt("viewId"),
                        contentDesc = opt("contentDesc"),
                        className = opt("className"),
                        index = idx,
                        timeoutMs = t,
                        note = s("note"),
                        delayBeforeMs = parsedDelay()
                    )
                }
                reader
            }

            is Step.ImageTap -> {
                addField("templateId", "模板ID", step.templateId, false)
                addField("threshold", "阈值%", step.thresholdPercent.toString(), true)
                addField("regionLeft", "区域左(0=全屏)", step.regionLeft.toString(), true)
                addField("regionTop", "区域上", step.regionTop.toString(), true)
                addField("regionWidth", "区域宽(0=全屏)", step.regionWidth.toString(), true)
                addField("regionHeight", "区域高", step.regionHeight.toString(), true)
                addField("offsetX", "偏移X", step.offsetX.toString(), true)
                addField("offsetY", "偏移Y", step.offsetY.toString(), true)
                addField("timeout", "超时(ms)", step.timeoutMs.toString(), true)
                val reader: () -> Step? = {
                    val th = i("threshold")
                    val rl = i("regionLeft")
                    val rt = i("regionTop")
                    val rw = i("regionWidth")
                    val rh = i("regionHeight")
                    val ox = i("offsetX")
                    val oy = i("offsetY")
                    val t = l("timeout")
                    if (th == null || rl == null || rt == null || rw == null ||
                        rh == null || ox == null || oy == null || t == null
                    ) null
                    else step.copy(
                        templateId = s("templateId"),
                        thresholdPercent = th,
                        regionLeft = rl,
                        regionTop = rt,
                        regionWidth = rw,
                        regionHeight = rh,
                        offsetX = ox,
                        offsetY = oy,
                        timeoutMs = t,
                        note = s("note"),
                        delayBeforeMs = parsedDelay()
                    )
                }
                reader
            }

            is Step.ColorTap -> {
                addField("color", "颜色(-65536/0xFFFF0000)", colorText(step.color), false)
                addField("tolerance", "容差", step.tolerance.toString(), true)
                addField("regionLeft", "区域左(0=全屏)", step.regionLeft.toString(), true)
                addField("regionTop", "区域上", step.regionTop.toString(), true)
                addField("regionWidth", "区域宽(0=全屏)", step.regionWidth.toString(), true)
                addField("regionHeight", "区域高", step.regionHeight.toString(), true)
                addField("offsetX", "偏移X", step.offsetX.toString(), true)
                addField("offsetY", "偏移Y", step.offsetY.toString(), true)
                addField("timeout", "超时(ms)", step.timeoutMs.toString(), true)
                val reader: () -> Step? = {
                    val color = parseColor(s("color"))
                    val tol = i("tolerance")
                    val rl = i("regionLeft")
                    val rt = i("regionTop")
                    val rw = i("regionWidth")
                    val rh = i("regionHeight")
                    val ox = i("offsetX")
                    val oy = i("offsetY")
                    val t = l("timeout")
                    if (color == null || tol == null || rl == null || rt == null ||
                        rw == null || rh == null || ox == null || oy == null || t == null
                    ) null
                    else step.copy(
                        color = color,
                        tolerance = tol,
                        regionLeft = rl,
                        regionTop = rt,
                        regionWidth = rw,
                        regionHeight = rh,
                        offsetX = ox,
                        offsetY = oy,
                        timeoutMs = t,
                        note = s("note"),
                        delayBeforeMs = parsedDelay()
                    )
                }
                reader
            }

            is Step.MultiGesture -> {
                hintLabel(
                    "多指手势：${step.strokes.size} 指 / ${step.strokes.sumOf { it.size }} 点，" +
                        "轨迹不可在此编辑"
                )
                val reader: () -> Step? = {
                    step.copy(note = s("note"), delayBeforeMs = parsedDelay())
                }
                reader
            }

            is Step.SetVar -> {
                addField("name", "变量名", step.name, false)
                addField("value", "值", step.value, false)
                val reader: () -> Step? = {
                    step.copy(
                        name = s("name"),
                        value = s("value"),
                        note = s("note"),
                        delayBeforeMs = parsedDelay()
                    )
                }
                reader
            }

            is Step.Label -> {
                addField("name", "标签名", step.name, false)
                val reader: () -> Step? = {
                    step.copy(name = s("name"), note = s("note"), delayBeforeMs = parsedDelay())
                }
                reader
            }

            is Step.Jump -> {
                addField("label", "目标标签", step.label, false)
                addField("maxTimes", "最大次数(-1=不限)", step.maxTimes.toString(), true)
                val reader: () -> Step? = {
                    val m = i("maxTimes")
                    if (m == null) null
                    else step.copy(
                        label = s("label"),
                        maxTimes = m,
                        note = s("note"),
                        delayBeforeMs = parsedDelay()
                    )
                }
                reader
            }

            is Step.IfElse -> {
                hintLabel("条件：${step.condition.describe()}（改条件请到脚本编辑器页）")
                addField("thenLabel", "成立跳转标签", step.thenLabel ?: "", false)
                addField("elseLabel", "不成立跳转标签", step.elseLabel ?: "", false)
                val reader: () -> Step? = {
                    step.copy(
                        thenLabel = opt("thenLabel"),
                        elseLabel = opt("elseLabel"),
                        note = s("note"),
                        delayBeforeMs = parsedDelay()
                    )
                }
                reader
            }
        }

        addField("delay", "延时(ms)（执行本步前先等待）", step.delayBeforeMs.toString(), true)
        addField("note", "备注", step.note, false)

        val metrics = context.resources.displayMetrics
        val scroll = ScrollView(context)
        scroll.addView(
            fields,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        val capHeight = (metrics.heightPixels - OverlayUi.dp(context, 260f)).coerceAtLeast(OverlayUi.dp(context, 160f))
        val scrollLp = OverlayUi.matchWrap()
        scrollLp.height = capHeight
        scrollLp.topMargin = OverlayUi.dp(context, 8f)
        root.addView(scroll, scrollLp)

        val buttons = LinearLayout(context)
        buttons.orientation = LinearLayout.HORIZONTAL
        val cancel = Button(context).apply {
            text = "取消"
            textSize = 13f
            isAllCaps = false
            setOnClickListener { hide(windowManager) }
        }
        val confirm = Button(context).apply {
            text = "确定"
            textSize = 13f
            isAllCaps = false
            setOnClickListener {
                val updated = readStep()
                if (updated == null) {
                    Toast.makeText(context, "请输入有效的数值", Toast.LENGTH_SHORT).show()
                } else {
                    onConfirm(updated)
                    hide(windowManager)
                }
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
        params.y = OverlayUi.dp(context, 60f)

        try {
            windowManager.addView(root, params)
            editorView = root
        } catch (e: Exception) {
            editorView = null
        }
    }

    /** 隐藏并移除编辑窗口。 */
    fun hide(windowManager: WindowManager) {
        val view = editorView ?: return
        try {
            windowManager.removeView(view)
        } catch (e: Exception) {
            // 窗口失效时忽略
        }
        editorView = null
    }

    // ---- 字段构建与解析 ----

    /** 颜色的可读回填文本，便于与十进制/十六进制两种写法互转。 */
    private fun colorText(color: Int): String = color.toString()

    /**
     * 解析颜色文本，支持十进制（含负数）与 `0x`/`#` 前缀十六进制。
     * 失败返回 null。`Int` 溢出用 [Long.toInt] 截断处理。
     */
    private fun parseColor(text: String): Int? {
        val t = text.trim()
        if (t.isEmpty()) return null
        return when {
            t.startsWith("0x", ignoreCase = true) -> t.substring(2).toLongOrNull(16)?.toInt()
            t.startsWith("#") -> t.substring(1).toLongOrNull(16)?.toInt()
            else -> t.toLongOrNull()?.toInt() ?: t.toLongOrNull(16)?.toInt()
        }
    }
}