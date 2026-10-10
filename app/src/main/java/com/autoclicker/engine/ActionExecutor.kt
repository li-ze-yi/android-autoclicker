package com.autoclicker.engine

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.DisplayMetrics
import android.view.WindowManager
import android.widget.Toast
import com.autoclicker.core.bus.LogLevel
import com.autoclicker.core.bus.RuntimeBus
import com.autoclicker.di.ServiceLocator
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
import com.autoclicker.domain.model.ExtractSource
import com.autoclicker.domain.model.GestureAction
import com.autoclicker.domain.model.GlobalKeyAction
import com.autoclicker.domain.model.GlobalKeyName
import com.autoclicker.domain.model.InputTextAction
import com.autoclicker.domain.model.JumpAction
import com.autoclicker.domain.model.JumpMode
import com.autoclicker.domain.model.LongPressAction
import com.autoclicker.domain.model.NodeSelector
import com.autoclicker.domain.model.NumberGenMode
import com.autoclicker.domain.model.OpenAppAction
import com.autoclicker.domain.model.PercentPoint
import com.autoclicker.domain.model.PickMode
import com.autoclicker.domain.model.PixelPoint
import com.autoclicker.domain.model.PixelRect
import com.autoclicker.domain.model.PopupAction
import com.autoclicker.domain.model.RepeatClickAction
import com.autoclicker.domain.model.SpeakAction
import com.autoclicker.domain.model.StepNode
import com.autoclicker.domain.model.SwipeAction
import com.autoclicker.domain.model.TextGroup
import com.autoclicker.domain.model.TextMode
import com.autoclicker.domain.model.TextSource
import com.autoclicker.domain.model.ToastAction
import com.autoclicker.domain.model.VarOp
import com.autoclicker.domain.model.VariableOpAction
import com.autoclicker.domain.rule.CoordinateMapper
import com.autoclicker.platform.PixelStroke
import com.autoclicker.platform.ScreenFrame
import kotlin.math.abs
import kotlin.random.Random

/**
 * 动作执行器：执行 [StepNode] 中的 [com.autoclicker.domain.model.Action]，返回 [StepOutcome] 供分支跳转。
 *
 * 坐标系：屏幕尺寸优先取最近一次截屏帧，其次 WindowManager/DisplayMetrics 兜底。
 * 平台能力（手势/全局/节点/截屏/识别）可能未注册，缺失时记日志并安全返回。
 */
class ActionExecutor(
    private val appContext: Context,
    private val gate: PauseGate,
    private val conditionEvaluator: ConditionEvaluator,
    private val invokeFunction: suspend (CallFunctionAction, ExecutionContext) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val templates by lazy { ServiceLocator.templates }
    private val textGroups by lazy { ServiceLocator.textGroups }
    private val groupCursor = HashMap<String, Int>()
    private val numberCursor = HashMap<String, Long>()
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    /** 上一次图片识别点击的落点与「连续点同一处」次数，用于诊断原地死循环。 */
    private var lastImageClickPoint: PixelPoint? = null
    private var samePlaceRepeats = 0

    /** 最近若干次图片命中的位置，用于识别「绕了一圈又回到原点」的原地循环。 */
    private val recentImageHits = ArrayDeque<PixelPoint>()

    @Volatile
    private var lastScreen: Pair<Int, Int>? = null

    suspend fun execute(step: StepNode, ctx: ExecutionContext): StepOutcome {
        if (!step.enabled) {
            RuntimeBus.log(LogLevel.WARN, "跳过已禁用步骤：${step.id}")
            return StepOutcome()
        }
        val action = step.action
        return when (action) {
            is ClickAction -> {
                clickPercent(action.point, action.durationMs)
                StepOutcome(success = true)
            }

            is LongPressAction -> {
                clickPercent(action.point, action.durationMs)
                StepOutcome(success = true)
            }

            is RepeatClickAction -> {
                val total = action.count.coerceAtLeast(1)
                var i = 0
                while (i < total) {
                    clickPercent(action.point, action.durationMs)
                    i++
                    if (i < total) gate.delay(action.intervalMs.coerceAtLeast(0))
                }
                StepOutcome(success = true)
            }

            is AreaRandomClickAction -> {
                val currentMapper = mapper()
                val point = currentMapper.randomPointIn(currentMapper.toPixel(action.rect))
                clickPixel(point.x, point.y, action.durationMs)
                StepOutcome(success = true)
            }

            is GestureAction -> {
                val currentMapper = mapper()
                val strokes = action.strokes.map { stroke ->
                    PixelStroke(
                        points = stroke.points.map { currentMapper.toPixel(it) },
                        startTimeMs = stroke.startTimeMs,
                        durationMs = stroke.durationMs,
                    )
                }
                val executor = Platform.gesture()
                if (executor == null || !executor.isReady()) {
                    RuntimeBus.log(LogLevel.WARN, "手势能力不可用，无法执行手势")
                    return StepOutcome(success = false)
                }
                executor.perform(strokes)
                StepOutcome(success = true)
            }

            is SwipeAction -> {
                val executor = Platform.gesture()
                if (executor == null || !executor.isReady()) {
                    RuntimeBus.log(LogLevel.WARN, "手势能力不可用，无法滑动")
                    return StepOutcome(success = false)
                }
                val currentMapper = mapper()
                val from = currentMapper.toPixel(action.from)
                val to = currentMapper.toPixel(action.to)
                executor.swipe(from.x, from.y, to.x, to.y, action.durationMs)
                StepOutcome(success = true)
            }

            is ClickImageAction -> clickImage(action)

            is ClickColorAction -> clickColor(action)

            is ClickTextAction -> clickText(action)

            is ClickNodeAction -> clickNode(action)

            is GlobalKeyAction -> {
                execGlobalKey(action)
                StepOutcome(success = true)
            }

            is OpenAppAction -> {
                val global = Platform.global()
                if (global == null) {
                    RuntimeBus.log(LogLevel.WARN, "全局操作不可用，无法打开应用：${action.packageName}")
                    return StepOutcome(success = false)
                }
                global.openApp(action.packageName, action.activity)
                StepOutcome(success = true)
            }

            is CloseAppAction -> {
                val global = Platform.global()
                if (global == null) {
                    RuntimeBus.log(LogLevel.WARN, "全局操作不可用，无法关闭应用：${action.packageName}")
                    return StepOutcome(success = false)
                }
                global.closeApp(action.packageName)
                StepOutcome(success = true)
            }

            is InputTextAction -> {
                inputText(action, ctx)
                StepOutcome(success = true)
            }

            is ExtractContentAction -> {
                extractContent(action, ctx)
                StepOutcome(success = true)
            }

            is VariableOpAction -> {
                applyVariableOp(action, ctx)
                StepOutcome(success = true)
            }

            is ConditionAction -> {
                val ok = conditionEvaluator.evaluate(action, ctx) { mapper() }
                StepOutcome(success = ok)
            }

            is JumpAction -> when (action.mode) {
                JumpMode.END_TASK -> StepOutcome(endTask = true)
                JumpMode.STEP -> StepOutcome(jumpTargetStepId = action.targetStepId)
            }

            is CallFunctionAction -> {
                invokeFunction(action, ctx)
                StepOutcome(success = true)
            }

            is DelayAction -> {
                gate.delay(action.ms + randomExtra(action.randomMs))
                StepOutcome(success = true)
            }

            is EmptyAction -> StepOutcome(success = true)

            is ToastAction -> {
                showToast(action.message)
                StepOutcome(success = true)
            }

            is PopupAction -> {
                RuntimeBus.log(LogLevel.WARN, "弹窗以 Toast 兜底：${action.title}")
                showToast("${action.title}：${action.message}")
                StepOutcome(success = true)
            }

            is SpeakAction -> {
                speak(action.message)
                StepOutcome(success = true)
            }

            else -> {
                RuntimeBus.log(LogLevel.WARN, "暂不支持的动类型：${action::class.simpleName}")
                StepOutcome(success = false)
            }
        }
    }

    /** 释放资源（语音引擎）。 */
    fun release() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Throwable) {
            // 忽略释放异常
        }
        tts = null
        ttsReady = false
    }

    // ---------------- 识别点击 ----------------

    private suspend fun clickImage(action: ClickImageAction): StepOutcome {
        val hit = retryDetect(action.detectCount, action.detectIntervalMs) {
            val frame = captureFrameWithRetry() ?: return@retryDetect null
            val finder = Platform.images() ?: return@retryDetect null
            val frameMapper = CoordinateMapper(frame.width, frame.height)
            val region = action.region?.let { frameMapper.toPixel(it) }
            finder.find(action.templateId, action.similarity, region, frame)
        }
        if (hit == null) {
            RuntimeBus.log(LogLevel.WARN, "未找到图片模板：${templateName(action.templateId)}")
            return StepOutcome(success = false)
        }
        val point = mapper().jitter(hit, action.randomOffset)
        // 命中位置绕一圈又回到刚点过的地方，说明这一屏已经没有新目标了（剩下的都是点过的），
        // 此时若继续「成功→本步骤」就会原地打转、永远走不到失败跳转。
        // 这里主动判定为失败，让「失败→下一步滑动」能正常接管。
        val repeated = isSamePlace(hit)
        rememberHit(hit)
        samePlaceRepeats = if (repeated) samePlaceRepeats + 1 else 0
        RuntimeBus.log(
            "识别到模板「${templateName(action.templateId)}」，点击 (${point.x}, ${point.y})" +
                if (samePlaceRepeats > 0) "（同一批位置第 ${samePlaceRepeats + 1} 次）" else "",
        )
        if (samePlaceRepeats >= SAME_PLACE_FAIL_TIMES) {
            RuntimeBus.log(
                LogLevel.WARN,
                "已连续 $SAME_PLACE_FAIL_TIMES 次在相同位置识别到目标，判定为没有新目标，" +
                    "本步骤按失败处理，转走「失败跳转」",
            )
            forgetHits()
            samePlaceRepeats = 0
            return StepOutcome(success = false)
        }
        return StepOutcome(success = clickPixel(point.x, point.y, 60))
    }

    /** 本次命中的位置是否落在最近几次命中过的地方。 */
    private fun isSamePlace(hit: PixelPoint): Boolean = recentImageHits.any { last ->
        abs(last.x - hit.x) <= SAME_PLACE_TOLERANCE_PX && abs(last.y - hit.y) <= SAME_PLACE_TOLERANCE_PX
    }

    private fun rememberHit(hit: PixelPoint) {
        recentImageHits.addLast(hit)
        while (recentImageHits.size > SAME_PLACE_WINDOW) recentImageHits.removeFirst()
    }

    private fun forgetHits() {
        recentImageHits.clear()
        lastImageClickPoint = null
    }

    private suspend fun clickColor(action: ClickColorAction): StepOutcome {
        val hit = retryDetect(action.detectCount, action.detectIntervalMs) {
            val frame = captureFrameWithRetry() ?: return@retryDetect null
            val finder = Platform.colors() ?: return@retryDetect null
            val frameMapper = CoordinateMapper(frame.width, frame.height)
            finder.find(action.color, action.tolerance, frameMapper.toPixel(action.region), frame)
        }
        if (hit == null) {
            RuntimeBus.log(LogLevel.WARN, "未找到目标颜色：${Integer.toHexString(action.color)}")
            return StepOutcome(success = false)
        }
        val point = mapper().jitter(hit, action.randomOffset)
        return StepOutcome(success = clickPixel(point.x, point.y, 60))
    }

    private suspend fun clickText(action: ClickTextAction): StepOutcome {
        val hit = retryDetect(action.detectCount, action.detectIntervalMs) {
            val locator = Platform.nodes() ?: return@retryDetect null
            val nodes = locator.readAllText()
            val currentMapper = mapper()
            val region = action.region?.let { currentMapper.toPixel(it) }
            var found: PixelPoint? = null
            for (info in nodes) {
                val text = info.text ?: continue
                val matched = if (action.useRegex) {
                    runCatching { Regex(action.text).containsMatchIn(text) }.getOrDefault(false)
                } else {
                    text.contains(action.text)
                }
                if (matched && (region == null || inRegion(region, info.bounds))) {
                    found = PixelPoint(info.bounds.centerX, info.bounds.centerY)
                    break
                }
            }
            found
        }
        if (hit == null) {
            RuntimeBus.log(LogLevel.WARN, "未找到文字：${action.text}")
            return StepOutcome(success = false)
        }
        return StepOutcome(success = clickPixel(hit.x, hit.y, 60))
    }

    private suspend fun clickNode(action: ClickNodeAction): StepOutcome {
        val hit = retryDetect(action.detectCount, action.detectIntervalMs) {
            val locator = Platform.nodes() ?: return@retryDetect null
            val list = locator.findNodes(action.selector)
            val info = list.getOrNull(action.selector.index.coerceAtLeast(0)) ?: return@retryDetect null
            PixelPoint(info.bounds.centerX, info.bounds.centerY)
        }
        if (hit == null) {
            RuntimeBus.log(LogLevel.WARN, "未找到目标节点：${action.selector}")
            return StepOutcome(success = false)
        }
        return StepOutcome(success = clickPixel(hit.x, hit.y, 60))
    }

    // ---------------- 内容处理 ----------------

    private suspend fun inputText(action: InputTextAction, ctx: ExecutionContext) {
        val text = resolveTextSource(action.source, ctx)
        val locator = Platform.nodes()
        if (locator == null) {
            RuntimeBus.log(LogLevel.WARN, "无障碍节点能力不可用，无法注入文本：\"$text\"")
            return
        }
        val editors = locator.findNodes(NodeSelector(className = "android.widget.EditText"))
        if (editors.isEmpty()) {
            RuntimeBus.log(LogLevel.WARN, "未找到可输入控件，跳过文本注入：\"$text\"")
        } else {
            // 当前 NodeLocator 契约未暴露 ACTION_SET_TEXT 接口，P0 仅记录待注入文本。
            RuntimeBus.log(LogLevel.WARN, "文本注入需无障碍 ACTION_SET_TEXT 接口，当前契约未提供，已跳过：\"$text\"")
        }
    }

    private suspend fun extractContent(action: ExtractContentAction, ctx: ExecutionContext) {
        var text = ""
        when (action.source) {
            ExtractSource.NODE -> {
                val locator = Platform.nodes()
                if (locator == null) {
                    RuntimeBus.log(LogLevel.WARN, "无障碍节点能力不可用，无法提取内容")
                } else {
                    val selector = action.nodeSelector
                    val node = if (selector != null) {
                        locator.findNodes(selector).firstOrNull()
                    } else {
                        locator.readAllText().firstOrNull()
                    }
                    text = node?.text ?: ""
                }
            }

            ExtractSource.OCR -> {
                val frame = captureFrame()
                val ocr = Platform.ocr()
                if (frame != null && ocr != null) {
                    val region = action.region?.let { CoordinateMapper(frame.width, frame.height).toPixel(it) }
                    text = ocr.recognize(frame, region).joinToString(separator = "") { it.text }
                } else {
                    RuntimeBus.log(LogLevel.WARN, "OCR 能力不可用，无法提取内容")
                }
            }
        }
        val regex = action.regex?.let { runCatching { Regex(it) }.getOrNull() }
        val replacement = action.replaceWith
        if (regex != null) {
            text = if (replacement != null) {
                regex.replace(text, replacement)
            } else {
                regex.find(text)?.value ?: ""
            }
        }
        ctx.set(action.targetVar, text)
        RuntimeBus.log("提取内容 → 变量 ${action.targetVar} = \"$text\"")
    }

    private fun applyVariableOp(action: VariableOpAction, ctx: ExecutionContext) {
        val operand = resolveValueExpr(action.operand, ctx)
        val current = ctx.get(action.varName)
        val result = when (action.op) {
            VarOp.ASSIGN -> operand
            VarOp.APPEND -> (current ?: "") + operand
            VarOp.ADD -> (current.toLongOrZero() + operand.toLongOrZero()).toString()
            VarOp.SUB -> (current.toLongOrZero() - operand.toLongOrZero()).toString()
            VarOp.MUL -> (current.toLongOrZero() * operand.toLongOrZero()).toString()
            VarOp.DIV -> {
                val divisor = operand.toLongOrZero()
                if (divisor == 0L) current ?: "" else (current.toLongOrZero() / divisor).toString()
            }
        }
        ctx.set(action.varName, result)
        RuntimeBus.log("变量 ${action.varName} = \"$result\"")
    }

    private suspend fun resolveTextSource(source: TextSource, ctx: ExecutionContext): String = when (source.mode) {
        TextMode.LITERAL -> source.literal
        TextMode.TEXT_GROUP -> pickFromGroup(source)
        TextMode.NUMBER_GENERATOR -> nextNumber(source)
        TextMode.VARIABLE -> ctx.get(source.variableName) ?: ""
    }

    private suspend fun pickFromGroup(source: TextSource): String {
        val group: TextGroup? = textGroups.get(source.groupId)
        val lines = group?.lines ?: emptyList()
        if (lines.isEmpty()) {
            RuntimeBus.log(LogLevel.WARN, "文本组为空：${source.groupId}")
            return ""
        }
        return when (source.pickMode) {
            PickMode.SEQUENCE -> {
                val cursor = (groupCursor[source.groupId] ?: 0) % lines.size
                groupCursor[source.groupId] = cursor + 1
                lines[cursor]
            }

            PickMode.RANDOM -> lines[Random.nextInt(lines.size)]
        }
    }

    private fun nextNumber(source: TextSource): String {
        val key = "${source.groupId}|${source.numberFrom}|${source.numberTo}|${source.numberStep}|${source.numberMode}"
        val value = when (source.numberMode) {
            NumberGenMode.INCREMENT -> {
                val step = source.numberStep
                val prev = numberCursor[key] ?: (source.numberFrom - step)
                var next = prev + step
                if (step >= 0 && next > source.numberTo) next = source.numberFrom
                if (step < 0 && next < source.numberTo) next = source.numberFrom
                numberCursor[key] = next
                next
            }

            NumberGenMode.RANDOM -> {
                val from = minOf(source.numberFrom, source.numberTo)
                val to = maxOf(source.numberFrom, source.numberTo)
                if (to <= from) from else Random.nextLong(from, to + 1)
            }
        }
        val text = value.toString()
        return if (source.numberLength > 0 && text.length < source.numberLength) {
            text.padStart(source.numberLength, '0')
        } else {
            text
        }
    }

    // ---------------- 全局键 / 提示 ----------------

    private fun execGlobalKey(action: GlobalKeyAction) {
        val global = Platform.global()
        if (global == null) {
            RuntimeBus.log(LogLevel.WARN, "全局操作不可用，无法执行 ${action.key}")
            return
        }
        when (action.key) {
            GlobalKeyName.BACK -> global.back()
            GlobalKeyName.HOME -> global.home()
            GlobalKeyName.RECENTS -> global.recents()
        }
    }

    private fun showToast(message: String) {
        mainHandler.post {
            try {
                Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                RuntimeBus.log(LogLevel.WARN, "Toast 显示失败：${t.message}")
            }
        }
    }

    private fun speak(message: String) {
        val engine = tts ?: TextToSpeech(appContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
        }.also { tts = it }
        if (ttsReady) {
            try {
                engine.speak(message, TextToSpeech.QUEUE_FLUSH, null, "ac_${System.currentTimeMillis()}")
            } catch (t: Throwable) {
                RuntimeBus.log(LogLevel.WARN, "语音播报失败：${t.message}")
            }
        } else {
            RuntimeBus.log(LogLevel.WARN, "语音引擎未就绪，跳过播报：$message")
        }
    }

    // ---------------- 底层工具 ----------------

    private fun mapper(): CoordinateMapper {
        val size = lastScreen ?: windowSize()
        return CoordinateMapper(size.first, size.second)
    }

    @Suppress("DEPRECATION")
    private fun windowSize(): Pair<Int, Int> {
        return try {
            val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (wm == null) {
                fallbackSize()
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = wm.currentWindowMetrics.bounds
                bounds.width() to bounds.height()
            } else {
                val dm = DisplayMetrics()
                wm.defaultDisplay.getRealMetrics(dm)
                dm.widthPixels to dm.heightPixels
            }
        } catch (t: Throwable) {
            fallbackSize()
        }
    }

    @Suppress("DEPRECATION")
    private fun fallbackSize(): Pair<Int, Int> {
        val dm = appContext.resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }

    /**
     * 带退避重试的截屏。
     *
     * 无障碍截屏会被系统按调用间隔节流，一次失败就直接判定「没识别到」会误触发失败跳转；
     * 这里在采集能力可用时最多重试几次，间隔 [CAPTURE_RETRY_GAP_MS]。
     */
    private suspend fun captureFrameWithRetry(): ScreenFrame? {
        val attempts = if (Platform.screen()?.isReady() == true) CAPTURE_RETRY_TIMES else 1
        var i = 0
        while (i < attempts) {
            captureFrame()?.let { return it }
            i++
            if (i < attempts) gate.delay(CAPTURE_RETRY_GAP_MS)
        }
        if (attempts > 1) {
            RuntimeBus.log(LogLevel.ERROR, "连续 $attempts 次截屏失败，本步骤无法识别")
        }
        return null
    }

    private suspend fun captureFrame(): ScreenFrame? {
        val source = Platform.screen()
        if (source == null) {
            RuntimeBus.log(LogLevel.WARN, "屏幕采集能力不可用")
            return null
        }
        if (!source.isReady()) {
            RuntimeBus.log(LogLevel.WARN, "屏幕采集未就绪")
            return null
        }
        val frame = try {
            source.capture()
        } catch (t: Throwable) {
            RuntimeBus.log(LogLevel.ERROR, "截屏失败：${t.message}")
            null
        }
        if (frame != null) lastScreen = frame.width to frame.height
        return frame
    }

    private suspend fun clickPercent(point: PercentPoint, durationMs: Long): Boolean {
        val pixel = mapper().toPixel(point)
        return clickPixel(pixel.x, pixel.y, durationMs)
    }

    private suspend fun clickPixel(x: Int, y: Int, durationMs: Long): Boolean {
        val executor = Platform.gesture()
        if (executor == null || !executor.isReady()) {
            RuntimeBus.log(LogLevel.WARN, "手势能力不可用，无法点击 ($x, $y)")
            return false
        }
        return try {
            executor.click(x, y, durationMs)
        } catch (t: Throwable) {
            RuntimeBus.log(LogLevel.ERROR, "点击失败：${t.message}")
            false
        }
    }

    private suspend fun <T> retryDetect(count: Int, intervalMs: Long, block: suspend () -> T?): T? {
        val total = count.coerceAtLeast(1)
        var i = 0
        while (i < total) {
            // 首次识别前先缓冲一下：上一步的点击/滑动生效后屏幕需要时间刷新，
            // 立即截屏会拿到旧帧，表现为「明明在可识别区域却识别不上」。
            gate.delay(if (i == 0) SETTLE_BEFORE_DETECT_MS else intervalMs.coerceAtLeast(0))
            val result = block()
            if (result != null) return result
            i++
        }
        return null
    }

    private suspend fun templateName(id: String): String = templates.get(id)?.name ?: id

    private fun inRegion(rect: PixelRect, bounds: PixelRect): Boolean {
        val cx = bounds.centerX
        val cy = bounds.centerY
        return cx >= rect.l && cx <= rect.r && cy >= rect.t && cy <= rect.b
    }

    private fun randomExtra(range: Long): Long = if (range <= 0) 0L else Random.nextLong(0, range + 1)

    companion object {
        /** 识别类动作首次截屏前的缓冲时长，等屏幕刷新，避免拿到旧帧。 */
        private const val SETTLE_BEFORE_DETECT_MS = 300L

        /** 截屏失败时的退避重试次数与间隔。 */
        private const val CAPTURE_RETRY_TIMES = 3
        private const val CAPTURE_RETRY_GAP_MS = 250L

        /**
         * 判定「又点回了刚刚点过的目标」的位置容差（像素）。模板中心在不同帧之间会有
         * 小幅抖动，且同一目标被反复命中时相邻两次的间距可达 200+ 像素，容差取小了会漏判。
         */
        private const val SAME_PLACE_TOLERANCE_PX = 300

        /** 参与「原地循环」比对的历史命中数量上限。 */
        private const val SAME_PLACE_WINDOW = 8

        /** 连续多次命中已点过的位置后，判定为没有新目标，本步骤按失败处理。 */
        private const val SAME_PLACE_FAIL_TIMES = 5
    }
}