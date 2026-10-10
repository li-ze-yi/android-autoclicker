package com.autoclicker.service.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import com.autoclicker.core.bus.LogLevel
import com.autoclicker.core.bus.RuntimeBus
import com.autoclicker.core.permission.PermissionChecker
import com.autoclicker.di.ServiceLocator
import com.autoclicker.domain.model.ImageTemplate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 选区外遮罩色（半透明黑）。 */
private val MASK_COLOR = 0x88000000.toInt()

/** 位图坐标下的最小选区边长。 */
private const val MIN_CROP_BITMAP_PX = 8f

/**
 * 「截屏 + 可调裁剪 → 存为图像模板」悬浮层。
 *
 * 流程：抓取当前屏幕 → 弹出全屏可调裁剪层（选区可移动/缩放/重新框选）→ 点「确定」后
 * 按选区裁剪并保存为图像模板。裁剪层为一次性窗口，出结果后立即移除并回收位图。
 */
object TemplateCaptureOverlay {

    @Volatile
    private var active = false

    /**
     * 抓取当前屏幕 → 弹出可调裁剪层 → 确定后裁剪并保存为图像模板。
     * @param onResult 保存完成回调，参数为新建的模板；取消或失败传 null。可为 null。
     */
    fun start(context: Context, onResult: ((ImageTemplate?) -> Unit)? = null) {
        if (active) {
            onResult?.invoke(null)
            return
        }
        if (!PermissionChecker.requireOverlay(context)) {
            onResult?.invoke(null)
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
        onResult: ((ImageTemplate?) -> Unit)?,
        toastMessage: String,
        logMessage: String,
    ) {
        toast(context, toastMessage)
        RuntimeBus.log(LogLevel.WARN, logMessage)
        active = false
        scope.cancel()
        onResult?.invoke(null)
    }

    private fun showCropper(
        context: Context,
        src: Bitmap,
        scope: CoroutineScope,
        onResult: ((ImageTemplate?) -> Unit)?,
    ) {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (windowManager == null) {
            recycle(src)
            fail(context, scope, onResult, "无法截图：请先开启无障碍服务", "裁剪层失败：WindowManager 缺失")
            return
        }

        val root = FrameLayout(context)
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
        fun teardown(result: ImageTemplate?) {
            if (finished) return
            finished = true
            try {
                windowManager.removeView(root)
            } catch (t: Throwable) {
                // 忽略移除异常，确保不残留窗口即返回。
            }
            imageView.setImageDrawable(null)
            recycle(src)
            active = false
            scope.cancel()
            onResult?.invoke(result)
        }

        var saving = false

        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            isClickable = true
            setBackgroundColor(0xCC000000.toInt())
            setPadding(dp(context, 8), dp(context, 8), dp(context, 8), dp(context, 8))
        }

        val selectAll = barButton(context, "全选") { crop.selectAll() }

        val cancel = barButton(context, "取消") { teardown(null) }

        val confirm = barButton(context, "确定") {
            if (finished || saving) return@barButton
            val rect = crop.selectionToBitmapRect()
            if (rect == null) {
                toast(context, "选区无效，请重新框选")
                return@barButton
            }
            saving = true
            scope.launch {
                var cropped: Bitmap? = null
                var template: ImageTemplate? = null
                try {
                    cropped = Bitmap.createBitmap(src, rect.left, rect.top, rect.width(), rect.height())
                    if (cropped != null) {
                        template = ServiceLocator.templates.save(
                            "模板_" + System.currentTimeMillis(),
                            cropped,
                        )
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
                if (template != null) {
                    toast(context, "已添加模板")
                    RuntimeBus.log("已添加图像模板：${template.name}（${template.width}x${template.height}）")
                } else {
                    toast(context, "保存模板失败")
                }
                teardown(template)
            }
        }

        bar.addView(selectAll)
        bar.addView(cancel)
        bar.addView(confirm)
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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        try {
            windowManager.addView(root, params)
        } catch (t: Throwable) {
            RuntimeBus.log(LogLevel.ERROR, "裁剪层创建失败：${t.message}")
            teardown(null)
            return
        }
        RuntimeBus.log("已进入截图裁剪模式：拖动调整选区后点「确定」")
    }

    private fun barButton(context: Context, label: String, onClick: () -> Unit): Button =
        Button(context).apply {
            text = label
            textSize = 14f
            isAllCaps = false
            minimumWidth = 0
            minimumHeight = 0
            setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                setMargins(dp(context, 6), 0, dp(context, 6), 0)
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
 * 全屏裁剪层：覆盖在截图 ImageView 之上，负责选区绘制与触摸调整。
 *
 * 交互：落点在 8 个手柄附近 → 调整对应边/角；落点在选区内 → 整体移动；
 * 落点在选区外 → 从该点重新框选。选区始终限制在视图内，最小边不小于 [MIN_CROP_BITMAP_PX]（位图坐标）。
 */
private class CropView(
    context: Context,
    private val src: Bitmap,
) : View(context) {

    private val selection = RectF()
    private var initialized = false

    private val density = resources.displayMetrics.density
    private val handleSize = 10f * density
    private val handleHit = 28f * density
    private val borderWidth = 1.5f * density

    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MASK_COLOR }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = borderWidth
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
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
            selection.set((w - sw) / 2f, (h - sh) / 2f, (w + sw) / 2f, (h + sh) / 2f)
            initialized = true
        }
    }

    /** 全选：选区覆盖整个视图。 */
    fun selectAll() {
        if (width <= 0 || height <= 0) return
        selection.set(0f, 0f, width.toFloat(), height.toFloat())
        invalidate()
    }

    /** 把当前视图选区换算到位图坐标，并 clamp 到合法范围；无效返回 null。 */
    fun selectionToBitmapRect(): Rect? {
        if (src.width <= 0 || src.height <= 0 || width <= 0 || height <= 0) return null
        val sx = src.width.toFloat() / width.toFloat()
        val sy = src.height.toFloat() / height.toFloat()
        val left = (selection.left * sx).roundToInt().coerceIn(0, src.width)
        val top = (selection.top * sy).roundToInt().coerceIn(0, src.height)
        val right = (selection.right * sx).roundToInt().coerceIn(0, src.width)
        val bottom = (selection.bottom * sy).roundToInt().coerceIn(0, src.height)
        val safeWidth = min(right - left, src.width - left)
        val safeHeight = min(bottom - top, src.height - top)
        if (safeWidth < 1 || safeHeight < 1) return null
        return Rect(left, top, left + safeWidth, top + safeHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = selection
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        // 选区外半透明遮罩：上/下/左/右四块。
        canvas.drawRect(0f, 0f, viewWidth, s.top, maskPaint)
        canvas.drawRect(0f, s.bottom, viewWidth, viewHeight, maskPaint)
        canvas.drawRect(0f, s.top, s.left, s.bottom, maskPaint)
        canvas.drawRect(s.right, s.top, viewWidth, s.bottom, maskPaint)
        // 细白色边框 + 8 个手柄方块。
        canvas.drawRect(s, borderPaint)
        val half = handleSize / 2f
        for (point in handlePoints()) {
            canvas.drawRect(point[0] - half, point[1] - half, point[0] + half, point[1] + half, handlePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                val hit = hitHandle(event.x, event.y)
                if (hit != HANDLE_NONE) {
                    mode = MODE_HANDLE
                    activeHandle = hit
                } else if (selection.contains(event.x, event.y)) {
                    mode = MODE_MOVE
                    activeHandle = HANDLE_NONE
                } else {
                    mode = MODE_NEW
                    activeHandle = HANDLE_NONE
                    selection.set(event.x, event.y, event.x, event.y)
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val x = event.x
                val y = event.y
                when (mode) {
                    MODE_MOVE -> {
                        selection.offset(x - lastX, y - lastY)
                        clampSelection()
                    }

                    MODE_HANDLE -> adjustHandle(x - lastX, y - lastY)

                    MODE_NEW -> {
                        selection.set(
                            min(downX, x),
                            min(downY, y),
                            max(downX, x),
                            max(downY, y),
                        )
                        clampSelection()
                    }
                }
                lastX = x
                lastY = y
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (mode == MODE_NEW) clampSelection()
                mode = MODE_NONE
                activeHandle = HANDLE_NONE
                invalidate()
                return true
            }
        }
        return true
    }

    private fun adjustHandle(dx: Float, dy: Float) {
        val minWidth = minSelectWidth()
        val minHeight = minSelectHeight()
        val left = selection.left
        val top = selection.top
        val right = selection.right
        val bottom = selection.bottom
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
        selection.set(newLeft, newTop, newRight, newBottom)
        clampSelection()
    }

    /** 保证最小边长，并把选区限制在视图范围内。 */
    private fun clampSelection() {
        val minWidth = minSelectWidth()
        val minHeight = minSelectHeight()
        if (selection.width() < minWidth) selection.right = selection.left + minWidth
        if (selection.height() < minHeight) selection.bottom = selection.top + minHeight
        if (selection.left < 0f) selection.offset(-selection.left, 0f)
        if (selection.top < 0f) selection.offset(0f, -selection.top)
        if (selection.right > width) selection.offset(width - selection.right, 0f)
        if (selection.bottom > height) selection.offset(0f, height - selection.bottom)
        if (selection.left < 0f) selection.left = 0f
        if (selection.top < 0f) selection.top = 0f
        if (selection.right > width) selection.right = width.toFloat()
        if (selection.bottom > height) selection.bottom = height.toFloat()
    }

    private fun minSelectWidth(): Float =
        if (src.width > 0 && width > 0) MIN_CROP_BITMAP_PX * width / src.width else 1f

    private fun minSelectHeight(): Float =
        if (src.height > 0 && height > 0) MIN_CROP_BITMAP_PX * height / src.height else 1f

    private fun hitHandle(x: Float, y: Float): Int {
        val points = handlePoints()
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

    private fun handlePoints(): Array<FloatArray> {
        val s = selection
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