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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val templateTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

private fun formatTemplateTime(timestamp: Long): String =
    templateTimeFormat.format(Date(timestamp))

/**
 * ContentScale.Fit 在画布中实际绘制区域的几何信息。
 *
 * scale：位图到画布的缩放比；offsetX/offsetY：留白偏移（像素）；
 * imgLeft/imgTop/imgRight/imgBottom：图片实际绘制矩形（画布像素坐标）。
 */
private data class FitGeometry(
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
    val imgLeft: Float,
    val imgTop: Float,
    val imgRight: Float,
    val imgBottom: Float,
    val valid: Boolean
)

/**
 * 计算 ContentScale.Fit 下图片的真实绘制区域。
 *
 * scale = min(canvasW / bmpW, canvasH / bmpH)
 * offsetX = (canvasW - bmpW * scale) / 2，offsetY 同理，即上下/左右留白。
 */
private fun computeFitGeometry(canvas: IntSize, bitmap: Bitmap?): FitGeometry {
    if (bitmap == null || canvas.width <= 0 || canvas.height <= 0) {
        return FitGeometry(1f, 0f, 0f, 0f, 0f, 0f, 0f, false)
    }
    val bmpW = bitmap.width.toFloat()
    val bmpH = bitmap.height.toFloat()
    val scale = min(canvas.width / bmpW, canvas.height / bmpH)
    val drawW = bmpW * scale
    val drawH = bmpH * scale
    val offsetX = (canvas.width - drawW) / 2f
    val offsetY = (canvas.height - drawH) / 2f
    return FitGeometry(
        scale = scale,
        offsetX = offsetX,
        offsetY = offsetY,
        imgLeft = offsetX,
        imgTop = offsetY,
        imgRight = offsetX + drawW,
        imgBottom = offsetY + drawH,
        valid = true
    )
}

/** 把画布坐标限制到图片实际绘制区域内，避免拖拽到留白区。 */
private fun clampToImage(point: Offset, geometry: FitGeometry): Offset {
    if (!geometry.valid) return point
    return Offset(
        x = point.x.coerceIn(geometry.imgLeft, geometry.imgRight),
        y = point.y.coerceIn(geometry.imgTop, geometry.imgBottom)
    )
}

/** 画布像素坐标 → 位图像素坐标：(p - offset) / scale，并限制在位图范围内。 */
private fun canvasToBitmap(point: Offset, geometry: FitGeometry, bitmap: Bitmap): Offset {
    if (!geometry.valid || geometry.scale <= 0f) return Offset.Zero
    val bx = ((point.x - geometry.offsetX) / geometry.scale)
        .coerceIn(0f, bitmap.width.toFloat())
    val by = ((point.y - geometry.offsetY) / geometry.scale)
        .coerceIn(0f, bitmap.height.toFloat())
    return Offset(bx, by)
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

    val reload: () -> Unit = {
        templates = repository.list()
        captureReady = ScreenCaptureService.isReady
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
    var startOffset by remember { mutableStateOf<Offset?>(null) }
    var currentOffset by remember { mutableStateOf<Offset?>(null) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var nameInput by remember { mutableStateOf("模板") }
    var cropToSave by remember { mutableStateOf<Bitmap?>(null) }

    val geometry = remember(canvasSize, captured) { computeFitGeometry(canvasSize, captured) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Image(
            bitmap = captured.asImageBitmap(),
            contentDescription = "待框选截图",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { canvasSize = it }
                .pointerInput(captured, geometry) {
                    if (!geometry.valid) return@pointerInput
                    detectDragGestures(
                        onDragStart = { offset ->
                            val point = clampToImage(offset, geometry)
                            startOffset = point
                            currentOffset = point
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val base = currentOffset ?: startOffset ?: Offset.Zero
                            currentOffset = clampToImage(base + dragAmount, geometry)
                        },
                        onDragEnd = { },
                        onDragCancel = { }
                    )
                }
        ) {
            val start = startOffset
            val end = currentOffset
            if (start != null && end != null) {
                val topLeft = Offset(min(start.x, end.x), min(start.y, end.y))
                val rectSize = Size(abs(end.x - start.x), abs(end.y - start.y))
                drawRect(color = Color(0x3300BCD4), topLeft = topLeft, size = rectSize)
                drawRect(
                    color = Color(0xFF00BCD4),
                    topLeft = topLeft,
                    size = rectSize,
                    style = Stroke(width = 3f)
                )
            }
        }

        TextButton(
            onClick = {
                startOffset = null
                currentOffset = null
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
                    startOffset = null
                    currentOffset = null
                    onCancel()
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("取消")
            }
            Button(
                onClick = {
                    val start = startOffset
                    val end = currentOffset
                    if (start == null || end == null) {
                        onToast("请拖拽选择一个区域")
                    } else {
                        val b1 = canvasToBitmap(start, geometry, captured)
                        val b2 = canvasToBitmap(end, geometry, captured)
                        val left = min(b1.x, b2.x).toInt()
                        val top = min(b1.y, b2.y).toInt()
                        val right = max(b1.x, b2.x).toInt()
                        val bottom = max(b1.y, b2.y).toInt()
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