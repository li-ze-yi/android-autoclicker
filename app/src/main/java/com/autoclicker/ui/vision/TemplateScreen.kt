package com.autoclicker.ui.vision

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.autoclicker.core.vision.CapturePermissionActivity
import com.autoclicker.core.vision.ImageTemplate
import com.autoclicker.core.vision.ImageTemplateRepository
import com.autoclicker.core.vision.ScreenCaptureService
import com.autoclicker.core.vision.VisionBridge
import com.autoclicker.core.vision.VisionSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val templateTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

private fun formatTemplateTime(timestamp: Long): String =
    templateTimeFormat.format(Date(timestamp))

/** 用户缩放上限（1x 表示适配屏幕）。 */
private const val MAX_USER_SCALE = 4f

/**
 * 画布视图变换：ContentScale.Fit 基准缩放 + 用户缩放/平移。
 *
 * 设 fitScale = min(canvasW / bmpW, canvasH / bmpH)，图片以画布中心为锚点、
 * 按 userScale 放大后再平移 userOffset，则图片在画布上的实际显示区域为：
 *   dispW = bmpW * fitScale * userScale
 *   dispH = bmpH * fitScale * userScale
 *   dispLeft = (canvasW - dispW) / 2 + userOffset.x
 *   dispTop  = (canvasH - dispH) / 2 + userOffset.y
 *
 * [Image] 用 graphicsLayer(scaleX/scaleY = userScale, translationX/Y = userOffset) 实现同一变换，
 * 因此这里换算出的显示区域与 Image 实际绘制区域一致：绘制高亮框与裁剪共用同一套坐标换算。
 */
private data class ViewTransform(
    val canvas: IntSize,
    val bmpW: Float,
    val bmpH: Float,
    val fitScale: Float,
    val userScale: Float,
    val offset: Offset,
    val valid: Boolean
)

private fun computeViewTransform(
    canvas: IntSize,
    bitmap: Bitmap?,
    userScale: Float,
    userOffset: Offset
): ViewTransform {
    if (bitmap == null || canvas.width <= 0 || canvas.height <= 0) {
        return ViewTransform(canvas, 0f, 0f, 1f, 1f, Offset.Zero, false)
    }
    val bmpW = bitmap.width.toFloat()
    val bmpH = bitmap.height.toFloat()
    val fitScale = min(canvas.width / bmpW, canvas.height / bmpH)
    return ViewTransform(
        canvas = canvas,
        bmpW = bmpW,
        bmpH = bmpH,
        fitScale = fitScale,
        userScale = userScale.coerceIn(1f, MAX_USER_SCALE),
        offset = userOffset,
        valid = true
    )
}

/** 位图坐标 → 画布坐标的当前总缩放比。 */
private fun ViewTransform.totalScale(): Float = fitScale * userScale

/** 图片在画布上的显示宽度（像素）。 */
private fun ViewTransform.displayWidth(): Float = bmpW * totalScale()

/** 图片在画布上的显示高度（像素）。 */
private fun ViewTransform.displayHeight(): Float = bmpH * totalScale()

/** 图片显示区域左边界（画布坐标）。 */
private fun ViewTransform.displayLeft(): Float =
    (canvas.width - displayWidth()) / 2f + offset.x

/** 图片显示区域上边界（画布坐标）。 */
private fun ViewTransform.displayTop(): Float =
    (canvas.height - displayHeight()) / 2f + offset.y

/** 把画布坐标夹取到图片显示区域内，避免拖拽到留白区。 */
private fun clampCanvasToImage(point: Offset, t: ViewTransform): Offset {
    if (!t.valid) return point
    val left = t.displayLeft()
    val top = t.displayTop()
    return Offset(
        x = point.x.coerceIn(left, left + t.displayWidth()),
        y = point.y.coerceIn(top, top + t.displayHeight())
    )
}

/**
 * 画布像素坐标 → 位图像素坐标：
 *   bx = (px - dispLeft) / (fitScale * userScale)
 *   by = (py - dispTop) / (fitScale * userScale)
 * 并把结果夹取到 [0, bmpW] / [0, bmpH]。
 */
private fun canvasToBitmap(point: Offset, t: ViewTransform, bitmap: Bitmap): Offset {
    if (!t.valid || t.totalScale() <= 0f) return Offset.Zero
    val s = t.totalScale()
    val bx = ((point.x - t.displayLeft()) / s).coerceIn(0f, bitmap.width.toFloat())
    val by = ((point.y - t.displayTop()) / s).coerceIn(0f, bitmap.height.toFloat())
    return Offset(bx, by)
}

/** 位图像素坐标 → 画布像素坐标（绘制高亮框用，与裁剪共用同一变换）。 */
private fun bitmapToCanvas(x: Float, y: Float, t: ViewTransform): Offset {
    if (!t.valid || t.totalScale() <= 0f) return Offset.Zero
    val s = t.totalScale()
    return Offset(t.displayLeft() + x * s, t.displayTop() + y * s)
}

/**
 * 识图模板管理页：截屏授权、截取当前屏幕、框选保存与模板增删改。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplateScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { ImageTemplateRepository.get(context) }
    val scope = rememberCoroutineScope()

    var templates by remember { mutableStateOf<List<ImageTemplate>>(emptyList()) }
    var captureReady by remember { mutableStateOf(ScreenCaptureService.isReady) }
    var captured by remember { mutableStateOf<Bitmap?>(null) }
    var renameTarget by remember { mutableStateOf<ImageTemplate?>(null) }
    var deleteTarget by remember { mutableStateOf<ImageTemplate?>(null) }

    val pendingCapture by VisionBridge.pendingCapture.collectAsState()

    // 模板列表为文件 IO，放到 IO 线程，回到主线程再写入状态。
    val reload: () -> Unit = {
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { repository.list() }
            templates = loaded
            captureReady = ScreenCaptureService.isReady
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        reload()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) reload()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 进入页面或悬浮窗新发布截图时，取出待框选位图。
    LaunchedEffect(pendingCapture) {
        if (pendingCapture != null) {
            VisionBridge.consumeCapture()?.let { captured = it }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("识图模板") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 截屏授权状态条
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("截屏授权", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = if (captureReady) "已授权" else "未授权",
                            color = if (captureReady) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            }
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { CapturePermissionActivity.request(context) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("授权截屏")
                            }
                            Button(
                                onClick = {
                                    if (!captureReady) {
                                        Toast.makeText(
                                            context,
                                            "请先授权截屏",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        scope.launch {
                                            val bitmap = withContext(Dispatchers.IO) {
                                                ScreenCaptureService.capture(0)
                                            }
                                            captureReady = ScreenCaptureService.isReady
                                            if (bitmap != null) {
                                                VisionBridge.publishCapture(bitmap)
                                                Toast.makeText(
                                                    context,
                                                    "已截屏，请在下方框选区域",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            } else {
                                                Toast.makeText(
                                                    context,
                                                    "截屏失败，请重新授权",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("截取当前屏幕")
                            }
                        }
                    }
                }

                // 识图默认阈值设置
                var defaultThreshold by remember {
                    mutableStateOf(VisionSettings.get(context).defaultThresholdPercent)
                }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("识图默认阈值", style = MaterialTheme.typography.titleMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Slider(
                                value = defaultThreshold.toFloat(),
                                onValueChange = {
                                    val v = it.roundToInt().coerceIn(0, 100)
                                    defaultThreshold = v
                                    VisionSettings.get(context).defaultThresholdPercent = v
                                },
                                valueRange = 0f..100f,
                                steps = 19,
                                modifier = Modifier.weight(1f)
                            )
                            Text("${defaultThreshold}%")
                        }
                        Text(
                            "新建识图步骤时的默认相似度，越高越严格",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                // 模板列表
                if (templates.isEmpty()) {
                    Text(
                        text = "暂无模板，点上方『截取当前屏幕』创建",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(templates, key = { it.id }) { template ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = template.name,
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                        Text(
                                            text = "${template.width}×${template.height} · " +
                                                formatTemplateTime(template.createdAt),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    TextButton(onClick = { renameTarget = template }) {
                                        Text("重命名")
                                    }
                                    TextButton(onClick = { deleteTarget = template }) {
                                        Text("删除")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        val frameBitmap = captured
        if (frameBitmap != null) {
            FramingOverlay(
                captured = frameBitmap,
                repository = repository,
                onToast = { message ->
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                },
                onCancel = { captured = null },
                onSaved = {
                    captured = null
                    reload()
                }
            )
        }
    }

    renameTarget?.let { target ->
        var newName by remember(target.id) { mutableStateOf(target.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("模板名称") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    repository.rename(target.id, newName)
                    renameTarget = null
                    reload()
                }) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("取消") }
            }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除模板") },
            text = { Text("确定要删除「${target.name}」吗？该操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    repository.delete(target.id)
                    deleteTarget = null
                    reload()
                }) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            }
        )
    }
}

/**
 * 全屏框选覆盖层：展示截图并允许拖拽绘制矩形，裁剪后保存为模板。
 *
 * 注意：原截图在显示期间不回收；仅裁剪出的副本在保存后回收。
 */
@Composable
private fun FramingOverlay(
    captured: Bitmap,
    repository: ImageTemplateRepository,
    onToast: (String) -> Unit,
    onCancel: () -> Unit,
    onSaved: () -> Unit
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var startBitmap by remember { mutableStateOf<Offset?>(null) }
    var currentBitmap by remember { mutableStateOf<Offset?>(null) }
    var userScale by remember { mutableStateOf(1f) }
    var userOffset by remember { mutableStateOf(Offset.Zero) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var nameInput by remember { mutableStateOf("模板") }
    var cropToSave by remember { mutableStateOf<Bitmap?>(null) }

    // 该位图由本组件独占。离开组合（取消 / 保存 / 被新截图替换）时回收，避免泄漏；
    // 放在 onDispose 而非替换处回收，可保证回收发生在停止绘制之后。
    DisposableEffect(captured) {
        onDispose {
            if (!captured.isRecycled) {
                captured.recycle()
            }
        }
    }

    // 选区以位图坐标保存；缩放/平移变化后选区仍对准同一块位图内容。
    val transform = remember(canvasSize, captured, userScale, userOffset) {
        computeViewTransform(canvasSize, captured, userScale, userOffset)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Image(
            bitmap = captured.asImageBitmap(),
            contentDescription = "待框选截图",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = userScale,
                    scaleY = userScale,
                    translationX = userOffset.x,
                    translationY = userOffset.y
                )
        )

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { canvasSize = it }
                .pointerInput(captured, transform) {
                    if (!transform.valid) return@pointerInput
                    detectDragGestures(
                        onDragStart = { offset ->
                            val b = canvasToBitmap(
                                clampCanvasToImage(offset, transform),
                                transform,
                                captured
                            )
                            startBitmap = b
                            currentBitmap = b
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            currentBitmap = canvasToBitmap(
                                clampCanvasToImage(change.position, transform),
                                transform,
                                captured
                            )
                        },
                        onDragEnd = { },
                        onDragCancel = { }
                    )
                }
        ) {
            // 选区存的是位图坐标，绘制时用同一变换反算回画布坐标。
            val start = startBitmap
            val end = currentBitmap
            if (start != null && end != null) {
                val p1 = bitmapToCanvas(start.x, start.y, transform)
                val p2 = bitmapToCanvas(end.x, end.y, transform)
                val topLeft = Offset(min(p1.x, p2.x), min(p1.y, p2.y))
                val rectSize = Size(abs(p2.x - p1.x), abs(p2.y - p1.y))
                drawRect(color = Color(0x3300BCD4), topLeft = topLeft, size = rectSize)
                drawRect(
                    color = Color(0xFF00BCD4),
                    topLeft = topLeft,
                    size = rectSize,
                    style = Stroke(width = 3f)
                )
            }
        }

        // 缩放控制按钮（不与单指框选冲突）；缩放锚点为画面中心。
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = { userScale = (userScale + 0.5f).coerceIn(1f, MAX_USER_SCALE) }
            ) {
                Text("＋")
            }
            OutlinedButton(
                onClick = { userScale = (userScale - 0.5f).coerceIn(1f, MAX_USER_SCALE) }
            ) {
                Text("－")
            }
            OutlinedButton(
                onClick = {
                    userScale = 1f
                    userOffset = Offset.Zero
                }
            ) {
                Text("重置")
            }
            Text("${(userScale * 100).roundToInt()}%", color = Color.White)
        }

        TextButton(
            onClick = {
                startBitmap = null
                currentBitmap = null
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
        ) {
            Text("清空选区", color = Color.White)
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = {
                    cropToSave?.recycle()
                    cropToSave = null
                    startBitmap = null
                    currentBitmap = null
                    userScale = 1f
                    userOffset = Offset.Zero
                    onCancel()
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("取消")
            }
            Button(
                onClick = {
                    val start = startBitmap
                    val end = currentBitmap
                    if (start == null || end == null) {
                        onToast("请拖拽选择一个区域")
                    } else {
                        // 选区已是位图坐标，直接换算裁剪矩形。
                        val left = min(start.x, end.x).toInt()
                        val top = min(start.y, end.y).toInt()
                        val right = max(start.x, end.x).toInt()
                        val bottom = max(start.y, end.y).toInt()
                        val width = right - left
                        val height = bottom - top
                        if (width < 8 || height < 8) {
                            onToast("请拖拽选择一个更大的区域")
                        } else {
                            val crop = try {
                                Bitmap.createBitmap(captured, left, top, width, height)
                            } catch (e: Exception) {
                                null
                            }
                            if (crop == null) {
                                onToast("裁剪失败，请重试")
                            } else {
                                cropToSave = crop
                                nameInput = "模板"
                                showSaveDialog = true
                            }
                        }
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("保存模板")
            }
        }
    }

    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = {
                cropToSave?.recycle()
                cropToSave = null
                showSaveDialog = false
            },
            title = { Text("保存模板") },
            text = {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text("模板名称") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val crop = cropToSave
                    val finalName = nameInput.trim().ifBlank { "模板" }
                    val saved = if (crop != null) repository.save(finalName, crop) else null
                    crop?.recycle()
                    cropToSave = null
                    showSaveDialog = false
                    if (saved != null) {
                        onToast("已保存模板：${saved.name}")
                        onSaved()
                    } else {
                        onToast("保存失败")
                    }
                }) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    cropToSave?.recycle()
                    cropToSave = null
                    showSaveDialog = false
                }) {
                    Text("取消")
                }
            }
        )
    }
}