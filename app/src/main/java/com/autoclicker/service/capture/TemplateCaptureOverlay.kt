package com.autoclicker.service.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.autoclicker.core.bus.LogLevel
import com.autoclicker.core.bus.RuntimeBus
import com.autoclicker.core.permission.PermissionChecker
import com.autoclicker.di.ServiceLocator
import com.autoclicker.domain.model.ImageTemplate
import com.autoclicker.domain.model.PercentRect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 选区外遮罩色（半透明黑）。 */
private val MASK_COLOR = 0x88000000.toInt()

/** 模板区边框色（白）。 */
private val TEMPLATE_COLOR = Color.WHITE

/** 识别区域边框色（绿）。 */
private val REGION_COLOR = 0xFF69F0AE.toInt()

/** 位图坐标下的最小选区边长。 */
private const val MIN_CROP_BITMAP_PX = 8f

/** 隐藏悬浮球后到真正抓屏之间的等待，保证屏幕帧里不再有悬浮球（先缩小悬浮球，再截图出框）。 */
private const val CAPTURE_DELAY_MS = 500L

/**
 * 「截屏 + 可调裁剪 → 存为图像模板」悬浮层。
 *
 * 流程：先等待外部已收起的悬浮球完全消失 → 抓取当前屏幕 → 弹出全屏裁剪层 →
 * 「确定」后按选区裁剪并保存为图像模板。裁剪层支持：
 * - 框选框整体移动（框内拖动）、8 手柄缩放、框外拖动重新框选；
 * - 两个选区：模板区（白框，必裁）与识别区域（绿框，可选，默认全屏）；
 * - 模板命名输入。
 * 裁剪层为一次性窗口，出结果后立即移除并回收位图。
 */
object TemplateCaptureOverlay {

    @Volatile
    private var active = false

    /**
     * 抓取当前屏幕 → 弹出可调裁剪层 → 确定后裁剪并保存为图像模板。
     * @param onResult 保存完成回调：参数1 为新建的模板，参数2 为识别区域（null 表示全屏识别）；取消或失败两者皆为 null。可为 null。
     */
    fun start(context: Context, onResult: ((ImageTemplate?, PercentRect?) -> Unit)? = null) {
        if (active) {
            onResult?.invoke(null, null)
            return
        }
        if (!PermissionChecker.requireOverlay(context)) {
            onResult?.invoke(null, null)
            return
        }
        active = true
        val appContext = context.applicationContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope.launch {
            val source = ServiceLocator.screenSource
            if (source == null) {
                fail(appContext, scope, onResult, "无法截图：请先开启无障碍服务", "截图建模板失败：屏幕采集服务未就绪")
                return@launch
            }
            // 先等悬浮球消失、屏幕刷新稳定后再抓屏，避免把球截进模板图。
            delay(CAPTURE_DELAY_MS)
            val frame = try {
                source.capture()
            } catch (t: Throwable) {
                null
            }
            // 截图位图可能是硬件位图或已被回收，务必先做软拷贝再用。
            val src = copyToSoftware(frame?.bitmap)
            if (src == null) {
                fail(appContext, scope, onResult, "无法截图：请先开启无障碍服务", "截图建模板失败：截屏为空或位图拷贝失败")
                return@launch
            }
            try {
                showCropper(appContext, src, scope, onResult)
            } catch (t: Throwable) {
                recycle(src)
                fail(appContext, scope, onResult, "无法截图：请先开启无障碍服务", "裁剪层异常：${t.message}")
            }
        }
    }

    private fun copyToSoftware(bitmap: Bitmap?): Bitmap? {
        if (bitmap == null) return null
        return try {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } catch (t: Throwable) {
            null
        }
    }

    private fun fail(
        context: Context,
        scope: CoroutineScope,
        onResult: ((ImageTemplate?, PercentRect?) -> Unit)?,
        toastMessage: String,
        logMessage: String,
    ) {
        toast(context, toastMessage)
        RuntimeBus.log(LogLevel.WARN, logMessage)
        active = false
        scope.cancel()
        onResult?.invoke(null, null)
    }

    private fun showCropper(
        context: Context,
        src: Bitmap,
        scope: CoroutineScope,
        onResult: ((ImageTemplate?, PercentRect?) -> Unit)?,
    ) {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (windowManager == null) {
            recycle(src)
            fail(context, scope, onResult, "无法截图：请先开启无障碍服务", "裁剪层失败：WindowManager 缺失")
            return
        }

        val root = FrameLayout(context)
        root.isFocusableInTouchMode = true
        val imageView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            setImageBitmap(src)
        }
        root.addView(
            imageView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        val crop = CropView(context, src)
        root.addView(
            crop,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        var finished = false
        fun teardown(result: ImageTemplate?, region: PercentRect?) {
            if (finished) return
            finished = true
            // 收起可能弹出的输入法。
            try {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(root.windowToken, 0)
            } catch (t: Throwable) {
                // 忽略输入法异常。
            }
            try {
                windowManager.removeView(root)
            } catch (t: Throwable) {
                // 忽略移除异常，确保不残留窗口即返回。
            }
            imageView.setImageDrawable(null)
            recycle(src)
            active = false
            scope.cancel()
            onResult?.invoke(result, region)
        }

        // 返回键 = 取消。
        root.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                teardown(null, null)
                true
            } else {
                false
            }
        }

        var saving = false

        // ---------------- 底部控制栏：名称输入 + 选区切换 + 操作按钮 ----------------

        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            setBackgroundColor(0xCC000000.toInt())
            setPadding(dp(context, 8), dp(context, 8), dp(context, 8), dp(context, 8))
        }

        val nameField = EditText(context).apply {
            hint = "模板名称（留空自动生成）"
            setHintTextColor(0x88FFFFFF.toInt())
            setTextColor(Color.WHITE)
            textSize = 13f
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        bar.addView(
            nameField,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        val modeText = TextView(context).apply {
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 11f
            text = modeHint(crop.editingRegion)
        }

        fun refreshMode() {
            modeText.text = modeHint(crop.editingRegion)
        }

        val switchRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        switchRow.addView(barButton(context, "框选模板区") {
            crop.editingRegion = false
            refreshMode()
        })
        switchRow.addView(barButton(context, "框选识别区") {
            crop.editingRegion = true
            refreshMode()
        })
        switchRow.addView(barButton(context, "识别区=全屏") {
            crop.clearRegion()
            crop.editingRegion = false
            refreshMode()
        })
        switchRow.addView(barButton(context, "全选") { crop.selectAll() })
        bar.addView(switchRow)
        bar.addView(modeText)

        val opsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        opsRow.addView(barButton(context, "取消") { teardown(null, null) })
        opsRow.addView(barButton(context, "确定") {
            if (finished || saving) return@barButton
            val templateRect = crop.templateRectInBitmap()
            if (templateRect == null) {
                toast(context, "模板选区无效，请重新框选")
                return@barButton
            }
            val regionRect = crop.regionRectInBitmap()
            saving = true
            val name = nameField.text?.toString()?.trim().orEmpty()
                .ifBlank { "模板_" + System.currentTimeMillis() }
            scope.launch {
                var cropped: Bitmap? = null
                var template: ImageTemplate? = null
                try {
                    cropped = Bitmap.createBitmap(src, templateRect.left, templateRect.top, templateRect.width(), templateRect.height())
                    if (cropped != null) {
                        template = ServiceLocator.templates.save(name, cropped)
                    }
                } catch (t: Throwable) {
                    RuntimeBus.log(LogLevel.ERROR, "保存图像模板失败：${t.message}")
                }
                val created = cropped
                if (created != null && created !== src) {
                    try {
                        created.recycle()
                    } catch (t: Throwable) {
                        // 忽略回收异常。
                    }
                }
                val region = regionRect?.let {
                    PercentRect(
                        (it.left.toFloat() / src.width).coerceIn(0f, 1f),
                        (it.top.toFloat() / src.height).coerceIn(0f, 1f),
                        (it.right.toFloat() / src.width).coerceIn(0f, 1f),
                        (it.bottom.toFloat() / src.height).coerceIn(0f, 1f),
                    )
                }
                if (template != null) {
                    toast(context, "已添加模板「${template.name}」")
                    RuntimeBus.log(
                        "已添加图像模板：${template.name}（${template.width}x${template.height}）" +
                            (if (region != null) "，识别区域已限定" else "，识别区域=全屏"),
                    )
                } else {
                    toast(context, "保存模板失败")
                }
                teardown(template, region)
            }
        })
        bar.addView(opsRow)

        root.addView(
            bar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ),
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 可获焦点（要能弹输入法给模板命名）；返回键由 root 的 OnKeyListener 拦截为取消。
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        try {
            windowManager.addView(root, params)
        } catch (t: Throwable) {
            RuntimeBus.log(LogLevel.ERROR, "裁剪层创建失败：${t.message}")
            teardown(null, null)
            return
        }
        RuntimeBus.log("已进入截图裁剪模式：白框=模板图，绿框=识别区域；框内拖动=移动，手柄=缩放，框外拖动=重新框选")
    }

    private fun modeHint(editingRegion: Boolean): String =
        if (editingRegion) {
            "当前编辑：识别区域（绿框）。识别时只在该区域找图；点「识别区=全屏」恢复全屏识别"
        } else {
            "当前编辑：模板区（白框）。框内拖动=移动，拖手柄=缩放，框外拖动=重新框选"
        }

    private fun barButton(context: Context, label: String, onClick: () -> Unit): Button =
        Button(context).apply {
            text = label
            textSize = 13f
            isAllCaps = false
            minimumWidth = 0
            minimumHeight = 0
            setPadding(dp(context, 10), dp(context, 6), dp(context, 10), dp(context, 6))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                setMargins(dp(context, 4), dp(context, 2), dp(context, 4), dp(context, 2))
            }
            setOnClickListener { onClick() }
        }

    private fun recycle(bitmap: Bitmap?) {
        if (bitmap == null) return
        try {
            if (!bitmap.isRecycled) bitmap.recycle()
        } catch (t: Throwable) {
            // 忽略回收异常。
        }
    }

    private fun toast(context: Context, message: String) {
        try {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            // 忽略 Toast 异常。
        }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt()
}

/**
 * 全屏裁剪层：覆盖在截图 ImageView 之上，负责「模板区 + 识别区域」两个选区的绘制与触摸调整。
 *
 * 交互（对当前编辑的选区）：落点在 8 个手柄附近 → 调整对应边/角；落点在选区内 → 整体移动；
 * 落点在选区外 → 从该点重新框选。选区始终限制在视图内，最小边不小于 [MIN_CROP_BITMAP_PX]（位图坐标）。
 */
private class CropView(
    context: Context,
    private val src: Bitmap,
) : View(context) {

    /** 当前编辑目标：false=模板区（白框），true=识别区域（绿框）。 */
    var editingRegion = false
        set(value) {
            field = value
            if (value && regionSel == null) {
                // 首次切到识别区域时给一个居中的初始框，便于直接拖动。
                val w = width.toFloat()
                val h = height.toFloat()
                if (w > 0 && h > 0) {
                    val rw = w * 0.6f
                    val rh = h * 0.6f
                    regionSel = RectF((w - rw) / 2f, (h - rh) / 2f, (w + rw) / 2f, (h + rh) / 2f)
                }
            }
            invalidate()
        }

    private val templateSel = RectF()

    /** 识别区域选区；null 表示全屏识别。 */
    private var regionSel: RectF? = null

    private var initialized = false

    private val density = resources.displayMetrics.density
    private val handleSize = 10f * density
    private val handleHit = 28f * density
    private val borderWidth = 1.5f * density

    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MASK_COLOR }
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = android.graphics.PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val templateBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = TEMPLATE_COLOR
        strokeWidth = borderWidth
    }
    private val regionBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = REGION_COLOR
        strokeWidth = borderWidth
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val regionHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = REGION_COLOR
    }

    private var mode = MODE_NONE
    private var activeHandle = HANDLE_NONE
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!initialized && w > 0 && h > 0) {
            val sw = w * 0.4f
            val sh = h * 0.4f
            templateSel.set((w - sw) / 2f, (h - sh) / 2f, (w + sw) / 2f, (h + sh) / 2f)
            initialized = true
        }
    }

    /** 全选：当前编辑的选区覆盖整个视图。 */
    fun selectAll() {
        if (width <= 0 || height <= 0) return
        currentSel()?.set(0f, 0f, width.toFloat(), height.toFloat())
        invalidate()
    }

    /** 清除识别区域（恢复全屏识别）。 */
    fun clearRegion() {
        regionSel = null
        invalidate()
    }

    /** 当前编辑的选区；识别区域未设置时按需初始化。 */
    private fun currentSel(): RectF? {
        if (!editingRegion) return templateSel
        if (regionSel == null) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0 || h <= 0) return null
            regionSel = RectF(0f, 0f, w, h)
        }
        return regionSel
    }

    /** 把视图选区换算到位图坐标，并 clamp 到合法范围；无效返回 null。 */
    private fun toBitmapRect(sel: RectF?): Rect? {
        if (sel == null) return null
        if (src.width <= 0 || src.height <= 0 || width <= 0 || height <= 0) return null
        val sx = src.width.toFloat() / width.toFloat()
        val sy = src.height.toFloat() / height.toFloat()
        val left = (sel.left * sx).roundToInt().coerceIn(0, src.width)
        val top = (sel.top * sy).roundToInt().coerceIn(0, src.height)
        val right = (sel.right * sx).roundToInt().coerceIn(0, src.width)
        val bottom = (sel.bottom * sy).roundToInt().coerceIn(0, src.height)
        val safeWidth = min(right - left, src.width - left)
        val safeHeight = min(bottom - top, src.height - top)
        if (safeWidth < 1 || safeHeight < 1) return null
        return Rect(left, top, left + safeWidth, top + safeHeight)
    }

    /** 模板区（位图坐标）。 */
    fun templateRectInBitmap(): Rect? = toBitmapRect(templateSel)

    /** 识别区域（位图坐标）；未设置返回 null（=全屏）。 */
    fun regionRectInBitmap(): Rect? = toBitmapRect(regionSel)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        // 遮罩：先铺全屏半透明黑，再挖空模板区与识别区域两块。
        val saveCount = canvas.saveLayer(0f, 0f, viewWidth, viewHeight, null)
        canvas.drawRect(0f, 0f, viewWidth, viewHeight, maskPaint)
        canvas.drawRect(templateSel, clearPaint)
        regionSel?.let { canvas.drawRect(it, clearPaint) }
        canvas.restoreToCount(saveCount)
        // 两个选区边框：模板区白色、识别区域绿色。
        canvas.drawRect(templateSel, templateBorderPaint)
        regionSel?.let { canvas.drawRect(it, regionBorderPaint) }
        // 只为当前编辑的选区画手柄，避免误触。
        val sel = currentSel() ?: return
        val paint = if (editingRegion) regionHandlePaint else handlePaint
        val half = handleSize / 2f
        for (point in handlePoints(sel)) {
            canvas.drawRect(point[0] - half, point[1] - half, point[0] + half, point[1] + half, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val sel = currentSel() ?: return true
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                val hit = hitHandle(sel, event.x, event.y)
                if (hit != HANDLE_NONE) {
                    mode = MODE_HANDLE
                    activeHandle = hit
                } else if (sel.contains(event.x, event.y)) {
                    mode = MODE_MOVE
                    activeHandle = HANDLE_NONE
                } else {
                    mode = MODE_NEW
                    activeHandle = HANDLE_NONE
                    sel.set(event.x, event.y, event.x, event.y)
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val sel = currentSel() ?: return true
                val x = event.x
                val y = event.y
                when (mode) {
                    MODE_MOVE -> {
                        sel.offset(x - lastX, y - lastY)
                        clampSelection(sel)
                    }

                    MODE_HANDLE -> adjustHandle(sel, x - lastX, y - lastY)

                    MODE_NEW -> {
                        sel.set(
                            min(downX, x),
                            min(downY, y),
                            max(downX, x),
                            max(downY, y),
                        )
                        clampSelection(sel)
                    }
                }
                lastX = x
                lastY = y
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val sel = currentSel()
                if (mode == MODE_NEW && sel != null) clampSelection(sel)
                mode = MODE_NONE
                activeHandle = HANDLE_NONE
                invalidate()
                return true
            }
        }
        return true
    }

    private fun adjustHandle(sel: RectF, dx: Float, dy: Float) {
        val minWidth = minSelectWidth()
        val minHeight = minSelectHeight()
        val left = sel.left
        val top = sel.top
        val right = sel.right
        val bottom = sel.bottom
        var newLeft = left
        var newTop = top
        var newRight = right
        var newBottom = bottom
        when (activeHandle) {
            HANDLE_TOP_LEFT -> {
                newLeft = min(left + dx, right - minWidth)
                newTop = min(top + dy, bottom - minHeight)
            }

            HANDLE_TOP_RIGHT -> {
                newRight = max(right + dx, left + minWidth)
                newTop = min(top + dy, bottom - minHeight)
            }

            HANDLE_BOTTOM_LEFT -> {
                newLeft = min(left + dx, right - minWidth)
                newBottom = max(bottom + dy, top + minHeight)
            }

            HANDLE_BOTTOM_RIGHT -> {
                newRight = max(right + dx, left + minWidth)
                newBottom = max(bottom + dy, top + minHeight)
            }

            HANDLE_TOP -> newTop = min(top + dy, bottom - minHeight)

            HANDLE_BOTTOM -> newBottom = max(bottom + dy, top + minHeight)

            HANDLE_LEFT -> newLeft = min(left + dx, right - minWidth)

            HANDLE_RIGHT -> newRight = max(right + dx, left + minWidth)
        }
        sel.set(newLeft, newTop, newRight, newBottom)
        clampSelection(sel)
    }

    /** 保证最小边长，并把选区限制在视图范围内。 */
    private fun clampSelection(sel: RectF) {
        val minWidth = minSelectWidth()
        val minHeight = minSelectHeight()
        if (sel.width() < minWidth) sel.right = sel.left + minWidth
        if (sel.height() < minHeight) sel.bottom = sel.top + minHeight
        if (sel.left < 0f) sel.offset(-sel.left, 0f)
        if (sel.top < 0f) sel.offset(0f, -sel.top)
        if (sel.right > width) sel.offset(width - sel.right, 0f)
        if (sel.bottom > height) sel.offset(0f, height - sel.bottom)
        if (sel.left < 0f) sel.left = 0f
        if (sel.top < 0f) sel.top = 0f
        if (sel.right > width) sel.right = width.toFloat()
        if (sel.bottom > height) sel.bottom = height.toFloat()
    }

    private fun minSelectWidth(): Float =
        if (src.width > 0 && width > 0) MIN_CROP_BITMAP_PX * width / src.width else 1f

    private fun minSelectHeight(): Float =
        if (src.height > 0 && height > 0) MIN_CROP_BITMAP_PX * height / src.height else 1f

    private fun hitHandle(sel: RectF, x: Float, y: Float): Int {
        val points = handlePoints(sel)
        var best = HANDLE_NONE
        var bestDistance = Float.MAX_VALUE
        for (index in points.indices) {
            val dx = x - points[index][0]
            val dy = y - points[index][1]
            if (abs(dx) <= handleHit && abs(dy) <= handleHit) {
                val distance = dx * dx + dy * dy
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = index
                }
            }
        }
        return best
    }

    private fun handlePoints(s: RectF): Array<FloatArray> {
        val centerX = (s.left + s.right) / 2f
        val centerY = (s.top + s.bottom) / 2f
        return arrayOf(
            floatArrayOf(s.left, s.top),
            floatArrayOf(s.right, s.top),
            floatArrayOf(s.left, s.bottom),
            floatArrayOf(s.right, s.bottom),
            floatArrayOf(centerX, s.top),
            floatArrayOf(centerX, s.bottom),
            floatArrayOf(s.left, centerY),
            floatArrayOf(s.right, centerY),
        )
    }

    private companion object {
        const val MODE_NONE = 0
        const val MODE_MOVE = 1
        const val MODE_HANDLE = 2
        const val MODE_NEW = 3

        const val HANDLE_NONE = -1
        const val HANDLE_TOP_LEFT = 0
        const val HANDLE_TOP_RIGHT = 1
        const val HANDLE_BOTTOM_LEFT = 2
        const val HANDLE_BOTTOM_RIGHT = 3
        const val HANDLE_TOP = 4
        const val HANDLE_BOTTOM = 5
        const val HANDLE_LEFT = 6
        const val HANDLE_RIGHT = 7
    }
}
