package com.autoclicker.service.overlay

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import com.autoclicker.core.targets.TargetController
import com.autoclicker.domain.model.TargetSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.hypot

/** 点击目标直径 dp */
private const val TAP_SIZE_DP = 40

/** 滑动控制点直径 dp */
private const val SWIPE_POINT_SIZE_DP = 36

/** 目标拖动/点击判定阈值 dp */
private const val TARGET_TAP_SLOP_DP = 10

/** 触摸时长上限提示（毫秒，60 秒） */
private const val HOLD_LIMIT_HINT_MS = 60_000L

// ---------------------------------------------------------------------------
// 窗口基础
// ---------------------------------------------------------------------------

/**
 * 创建悬浮窗 LayoutParams：统一 TYPE_APPLICATION_OVERLAY + NOT_FOCUSABLE，
 * 重力 TOP|START，使 params.x/y 即窗口左上角的屏幕绝对坐标。
 */
fun overlayParams(w: Int, h: Int): WindowManager.LayoutParams =
    WindowManager.LayoutParams(
        w,
        h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
    }

/**
 * 悬浮窗口句柄：统一管理 add/remove/update，避免重复挂载崩溃。
 */
class WindowHandle(
    val view: View,
    val params: WindowManager.LayoutParams,
) {
    /** 是否已挂载到 WindowManager */
    var attached: Boolean = false
        private set

    /** 挂载 */
    fun attach(wm: WindowManager) {
        if (!attached) {
            wm.addView(view, params)
            attached = true
        }
    }

    /** 移除 */
    fun detach(wm: WindowManager) {
        if (attached) {
            wm.removeView(view)
            attached = false
        }
    }

    /** 更新位置（拖拽中使用，临时更新 LayoutParams 而非加 flag） */
    fun update(wm: WindowManager) {
        if (attached) {
            wm.updateViewLayout(view, params)
        }
    }
}

/**
 * 悬浮窗通用拖拽 / 点击 / 长按触摸处理。
 *
 * - 位移 <= slop：抬手判定点击（[onClick]）；
 * - 位移 > slop：进入拖拽，位置实时跟随手指并 [onDragging] 回传圆心绝对坐标；
 * - 按住不动超过 longPressTimeout：触发 [onLongPress]，此后移动/抬手不再产生其他动作。
 *
 * 全部位移基于 MotionEvent.rawX/rawY（屏幕绝对坐标）。
 */
abstract class DragTouchListener(
    private val wm: WindowManager,
    private val view: View,
    private val slopPx: Int,
) : View.OnTouchListener {

    private var downRawX = 0f
    private var downRawY = 0f
    private var startLPX = 0
    private var startLPY = 0
    private var dragging = false
    private var longPressFired = false

    private val triggerLongPress = Runnable {
        if (!dragging && view.isAttachedToWindow) {
            longPressFired = true
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onLongPress()
        }
    }

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        val lp = v.layoutParams as WindowManager.LayoutParams
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                startLPX = lp.x
                startLPY = lp.y
                dragging = false
                longPressFired = false
                v.postDelayed(
                    triggerLongPress,
                    android.view.ViewConfiguration.getLongPressTimeout().toLong(),
                )
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (longPressFired) return true
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging) {
                    if (hypot(dx, dy) <= slopPx) return true
                    dragging = true
                    v.removeCallbacks(triggerLongPress)
                    onDragStart()
                }
                val viewW = if (v.width > 0) v.width else lp.width
                val viewH = if (v.height > 0) v.height else lp.height
                val maxX = (ScreenMetrics.width(wm) - viewW).coerceAtLeast(0)
                val maxY = (ScreenMetrics.height(wm) - viewH).coerceAtLeast(0)
                lp.x = (startLPX + dx.toInt()).coerceIn(0, maxX)
                lp.y = (startLPY + dy.toInt()).coerceIn(0, maxY)
                wm.updateViewLayout(v, lp)
                onDragging(lp.x + viewW / 2, lp.y + viewH / 2)
                return true
            }

            MotionEvent.ACTION_UP -> {
                v.removeCallbacks(triggerLongPress)
                if (longPressFired) return true
                if (dragging) {
                    val viewW = if (v.width > 0) v.width else lp.width
                    val viewH = if (v.height > 0) v.height else lp.height
                    onDragEnd(lp.x + viewW / 2, lp.y + viewH / 2)
                } else {
                    v.performClick()
                    onClick()
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                v.removeCallbacks(triggerLongPress)
                dragging = false
                return true
            }
        }
        return false
    }

    /** 开始拖拽（位移首次超过阈值） */
    protected open fun onDragStart() {}

    /** 拖拽中：参数为控件圆心的屏幕绝对坐标 */
    protected open fun onDragging(centerX: Int, centerY: Int) {}

    /** 拖拽结束：参数为控件圆心的屏幕绝对坐标 */
    protected open fun onDragEnd(centerX: Int, centerY: Int) {}

    /** 单击 */
    protected open fun onClick() {}

    /** 长按 */
    protected open fun onLongPress() {}
}

// ---------------------------------------------------------------------------
// 目标控件视图
// ---------------------------------------------------------------------------

/**
 * 点击目标控件：圆形，白底 + 主色描边 + 中心圆点；选中时显示主色光晕并加粗描边。
 */
class TapTargetView(context: Context) : View(context) {

    /** 是否选中（命名避开 View.setSelected 的 JVM 签名冲突） */
    var chosen: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xF2FFFFFF.toInt()
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = COLOR_PRIMARY
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_PRIMARY
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x552962FF
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val strokeW = if (chosen) dp(3f) else dp(1.5f)
        strokePaint.strokeWidth = strokeW
        val r = width / 2f - strokeW / 2 - dp(1f)
        if (chosen) {
            canvas.drawCircle(cx, cy, r + dp(6f), haloPaint)
        }
        canvas.drawCircle(cx, cy, r, fillPaint)
        canvas.drawCircle(cx, cy, r, strokePaint)
        canvas.drawCircle(cx, cy, dp(4f), dotPaint)
    }
}

/**
 * 滑动控制点：主色实心圆 + 白色描边 + S1/S2 标注；选中时显示光晕。
 */
class SwipePointView(
    context: Context,
    private val label: String,
) : View(context) {

    /** 是否选中（命名避开 View.setSelected） */
    var chosen: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_PRIMARY
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFFFFFFF.toInt()
        strokeWidth = 0f // onDraw 中设置
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x552962FF
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        textSize = spToPx(context, 12)
        isFakeBoldText = true
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val strokeW = dp(2f)
        strokePaint.strokeWidth = strokeW
        val r = width / 2f - strokeW / 2 - dp(1f)
        if (chosen) {
            canvas.drawCircle(cx, cy, r + dp(6f), haloPaint)
        }
        canvas.drawCircle(cx, cy, r, fillPaint)
        canvas.drawCircle(cx, cy, r, strokePaint)
        // 文字垂直居中
        val fm = textPaint.fontMetrics
        val baseline = cy - (fm.ascent + fm.descent) / 2f
        canvas.drawText(label, cx, baseline, textPaint)
    }
}

/**
 * 全屏透明连线层：绘制全部滑动目标 S1→S2 的连线。
 *
 * 窗口带 FLAG_NOT_TOUCHABLE，完全不拦截触摸，因此不会污染任何手势。
 */
class SwipeLineLayer(context: Context) : View(context) {

    private data class Segment(val x1: Float, val y1: Float, val x2: Float, val y2: Float)

    private var segments: List<Segment> = emptyList()

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xB32962FF.toInt()
        strokeWidth = context.resources.displayMetrics.density * 3f
        strokeCap = Paint.Cap.ROUND
    }

    /** 提交目标列表（内部提取滑动目标）并刷新 */
    fun submit(targets: List<TargetSpec>) {
        segments = targets.filterIsInstance<TargetSpec.SwipeTarget>().map {
            Segment(it.x1.toFloat(), it.y1.toFloat(), it.x2.toFloat(), it.y2.toFloat())
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        segments.forEach {
            canvas.drawLine(it.x1, it.y1, it.x2, it.y2, linePaint)
        }
    }
}

// ---------------------------------------------------------------------------
// 目标窗口管理：观察 TargetController，增删目标窗口、处理隐藏/显示
// ---------------------------------------------------------------------------

/**
 * 目标悬浮窗口管理器：
 * - 观察 [TargetController.targets]：目标增删时同步挂载/移除悬浮视图（主线程 collect）；
 * - 观察 [TargetController.hidden]：隐藏时移除目标视图与连线（保留悬浮球），显示时重建；
 * - 长按目标弹出参数对话框（点击目标：间隔/触摸时长/重复次数；滑动：滑动时长）。
 *
 * @param onWindowsChanged 目标窗口集合变化后的回调（服务借此把悬浮球/面板重新置顶）
 */
class TargetOverlayManager(
    private val context: Context,
    private val wm: WindowManager,
    private val controller: TargetController,
    private val scope: CoroutineScope,
    private val onWindowsChanged: () -> Unit,
) {

    /** 一个目标对应一条 Entry（点击目标 1 个窗口，滑动目标 2 个窗口） */
    private class Entry(
        val id: String,
        val handles: List<WindowHandle>,
        val targetViews: List<View>,
        var selected: Boolean = false,
    )

    private val entries = LinkedHashMap<String, Entry>()
    private var currentTargets: List<TargetSpec> = emptyList()
    private var hidden: Boolean

    /** 当前打开的参数对话框（服务销毁时一并关闭） */
    private val dialogs = mutableListOf<AlertDialog>()

    /** 全屏连线层 */
    private val lineLayer = SwipeLineLayer(context)
    private val lineHandle: WindowHandle

    init {
        hidden = controller.hidden.value

        val params = WindowManager.LayoutParams(
            ScreenMetrics.width(wm),
            ScreenMetrics.height(wm),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        lineHandle = WindowHandle(lineLayer, params)
        if (!hidden) {
            lineHandle.attach(wm)
        }

        scope.launch { controller.targets.collect { sync(it) } }
        scope.launch { controller.hidden.collect { applyHidden(it) } }
    }

    /** 目标列表变化：连线实时更新；非隐藏时增删窗口 */
    private fun sync(list: List<TargetSpec>) {
        currentTargets = list
        lineLayer.submit(list)
        if (!hidden) {
            reconcile()
        }
    }

    /** 隐藏/显示切换 */
    private fun applyHidden(value: Boolean) {
        if (hidden == value) return
        hidden = value
        if (value) {
            removeAllEntries()
            lineHandle.detach(wm)
        } else {
            lineHandle.attach(wm)
            reconcile()
        }
        onWindowsChanged()
    }

    /** 按目标 ID 集合差异增删窗口（仅集合变化才触发重排，拖拽移动不会重排） */
    private fun reconcile() {
        val newIds = currentTargets.mapTo(LinkedHashSet()) { it.id }
        var changed = false

        val removedIds = entries.keys - newIds
        removedIds.forEach { id ->
            entries.remove(id)?.handles?.forEach { it.detach(wm) }
            changed = true
        }

        currentTargets.forEach { target ->
            if (target.id !in entries) {
                entries[target.id] = createEntry(target)
                changed = true
            }
        }

        if (changed) {
            onWindowsChanged()
        }
    }

    /** 为目标创建悬浮窗口 */
    private fun createEntry(target: TargetSpec): Entry = when (target) {
        is TargetSpec.TapTarget -> createTapEntry(target)
        is TargetSpec.SwipeTarget -> createSwipeEntry(target)
    }

    private fun createTapEntry(tap: TargetSpec.TapTarget): Entry {
        val size = dpToPx(context, TAP_SIZE_DP)
        val view = TapTargetView(context)
        val params = overlayParams(size, size).apply {
            x = tap.x - size / 2
            y = tap.y - size / 2
        }
        val handle = WindowHandle(view, params)
        handle.attach(wm)

        val slop = targetSlop()
        view.setOnTouchListener(object : DragTouchListener(wm, view, slop) {
            override fun onDragging(centerX: Int, centerY: Int) {
                commitMove(centerX, centerY)
            }

            override fun onDragEnd(centerX: Int, centerY: Int) {
                commitMove(centerX, centerY)
            }

            override fun onClick() {
                select(tap.id)
            }

            override fun onLongPress() {
                showTapDialog(tap.id)
            }

            private fun commitMove(cx: Int, cy: Int) {
                runCatching { controller.moveTap(tap.id, cx, cy) }
                    .onFailure { toast(it.message ?: "移动失败") }
            }
        })
        return Entry(tap.id, listOf(handle), listOf(view))
    }

    private fun createSwipeEntry(swipe: TargetSpec.SwipeTarget): Entry {
        val size = dpToPx(context, SWIPE_POINT_SIZE_DP)
        val handles = mutableListOf<WindowHandle>()
        val views = mutableListOf<View>()

        fun buildPoint(which: Int, label: String, px0: Int, py0: Int) {
            val view = SwipePointView(context, label)
            val params = overlayParams(size, size).apply {
                x = px0 - size / 2
                y = py0 - size / 2
            }
            val handle = WindowHandle(view, params)
            handle.attach(wm)
            view.setOnTouchListener(object : DragTouchListener(wm, view, targetSlop()) {
                override fun onDragging(centerX: Int, centerY: Int) {
                    commit(centerX, centerY)
                }

                override fun onDragEnd(centerX: Int, centerY: Int) {
                    commit(centerX, centerY)
                }

                override fun onClick() {
                    select(swipe.id)
                }

                override fun onLongPress() {
                    showSwipeDialog(swipe.id)
                }

                private fun commit(cx: Int, cy: Int) {
                    runCatching { controller.moveSwipePoint(swipe.id, which, cx, cy) }
                        .onFailure { toast(it.message ?: "移动失败") }
                }
            })
            handles += handle
            views += view
        }

        buildPoint(1, "S1", swipe.x1, swipe.y1)
        buildPoint(2, "S2", swipe.x2, swipe.y2)

        return Entry(swipe.id, handles, views)
    }

    /** 选中/取消选中目标（单选，再次点击同一目标取消） */
    private fun select(id: String) {
        val entry = entries[id] ?: return
        val turnOn = !entry.selected
        entries.values.forEach { applySelected(it, false) }
        applySelected(entry, turnOn)
    }

    private fun applySelected(entry: Entry, value: Boolean) {
        entry.selected = value
        entry.targetViews.forEach { v ->
            when (v) {
                is TapTargetView -> v.chosen = value
                is SwipePointView -> v.chosen = value
            }
        }
    }

    private fun targetSlop(): Int = maxOf(
        android.view.ViewConfiguration.get(context).scaledTouchSlop,
        dpToPx(context, TARGET_TAP_SLOP_DP),
    )

    // -- 参数对话框 ----------------------------------------------------------

    /** 点击目标参数对话框：间隔 ms、触摸时长 ms、重复次数 */
    private fun showTapDialog(id: String) {
        val tap = currentTargets.filterIsInstance<TargetSpec.TapTarget>()
            .firstOrNull { it.id == id } ?: return

        val container = dialogContainer()
        val intervalEdit = numberField("点击间隔 ms（两次点击之间）", tap.intervalMs)
        val holdEdit = numberField("触摸时长 ms（需 ≤ 间隔）", tap.holdMs)
        val repeatEdit = numberField("单点重复次数（≥1）", tap.repeats)
        container.addView(intervalEdit)
        container.addView(holdEdit)
        container.addView(repeatEdit)

        val dialog = AlertDialog.Builder(
            context,
            android.R.style.Theme_DeviceDefault_Light_Dialog_Alert,
        )
            .setTitle("点击目标参数")
            .setView(container)
            .setPositiveButton("保存", null)
            .setNeutralButton("取消", null)
            .setNegativeButton("删除目标", null)
            .create()

        showOverlayDialog(dialog)

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val interval = intervalEdit.text.toString().trim().toLongOrNull()
            val hold = holdEdit.text.toString().trim().toLongOrNull()
            val repeat = repeatEdit.text.toString().trim().toIntOrNull()

            val error = when {
                interval == null || hold == null || repeat == null ->
                    "请填写全部参数（整数）"

                interval < 0 ->
                    "点击间隔不能为负"

                hold !in 0..HOLD_LIMIT_HINT_MS ->
                    "触摸时长需在 0~${HOLD_LIMIT_HINT_MS}ms 之间（长按上限 60 秒）"

                hold > interval ->
                    "触摸时长不能大于点击间隔"

                repeat < 1 ->
                    "单点重复次数必须 ≥ 1"

                else -> null
            }
            if (error != null) {
                toast(error)
                return@setOnClickListener
            }
            val saved = runCatching {
                controller.updateTap(id, interval!!, hold!!, repeat!!)
            }
            saved.onSuccess { dialog.dismiss() }
                .onFailure { toast(it.message ?: "保存失败") }
        }

        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).apply {
            setTextColor(COLOR_DANGER)
            setOnClickListener {
                runCatching { controller.remove(id) }
                    .onSuccess { dialog.dismiss() }
                    .onFailure { toast(it.message ?: "删除失败") }
            }
        }
    }

    /** 滑动时长对话框：默认 300ms，建议 ≥300ms */
    private fun showSwipeDialog(id: String) {
        val swipe = currentTargets.filterIsInstance<TargetSpec.SwipeTarget>()
            .firstOrNull { it.id == id } ?: return

        val container = dialogContainer()
        val durationEdit = numberField("滑动时长 ms（建议 ≥300ms）", swipe.durationMs)
        container.addView(durationEdit)

        val dialog = AlertDialog.Builder(
            context,
            android.R.style.Theme_DeviceDefault_Light_Dialog_Alert,
        )
            .setTitle("滑动目标参数")
            .setView(container)
            .setPositiveButton("保存", null)
            .setNeutralButton("取消", null)
            .setNegativeButton("删除目标", null)
            .create()

        showOverlayDialog(dialog)

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val duration = durationEdit.text.toString().trim().toLongOrNull()
            when {
                duration == null -> toast("请填写滑动时长（整数）")
                duration < TargetSpec.MIN_SWIPE_DURATION_MS ->
                    toast("滑动时长建议不小于 ${TargetSpec.MIN_SWIPE_DURATION_MS}ms")

                else -> {
                    runCatching { controller.updateSwipeDuration(id, duration) }
                        .onSuccess { dialog.dismiss() }
                        .onFailure { toast(it.message ?: "保存失败") }
                }
            }
        }

        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).apply {
            setTextColor(COLOR_DANGER)
            setOnClickListener {
                runCatching { controller.remove(id) }
                    .onSuccess { dialog.dismiss() }
                    .onFailure { toast(it.message ?: "删除失败") }
            }
        }
    }

    private fun showOverlayDialog(dialog: AlertDialog) {
        dialogs += dialog
        dialog.window?.apply {
            // Service 上下文无 Activity token：对话框本身也走 overlay 窗口
            setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN,
            )
        }
        dialog.setOnDismissListener { dialogs.remove(dialog) }
        dialog.show()
    }

    private fun dialogContainer(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(
            dpToPx(context, 20),
            dpToPx(context, 8),
            dpToPx(context, 20),
            0,
        )
    }

    private fun numberField(hint: String, value: Any): EditText = EditText(context).apply {
        this.hint = hint
        setText(value.toString())
        inputType = InputType.TYPE_CLASS_NUMBER
        val padV = dpToPx(context, 8)
        val padH = dpToPx(context, 4)
        setPadding(padH, padV, padH, padV)
    }

    private fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    /** 服务销毁：关闭全部对话框并移除全部目标窗口与连线 */
    fun shutdown() {
        dialogs.toList().forEach { runCatching { it.dismiss() } }
        dialogs.clear()
        removeAllEntries()
        lineHandle.detach(wm)
    }

    private fun removeAllEntries() {
        entries.values.forEach { entry ->
            entry.handles.forEach { it.detach(wm) }
        }
        entries.clear()
    }
}
