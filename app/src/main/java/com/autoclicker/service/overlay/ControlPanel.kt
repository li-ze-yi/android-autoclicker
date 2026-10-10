package com.autoclicker.service.overlay

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.autoclicker.core.bus.EngineState

/** 面板按钮尺寸 dp */
private const val BUTTON_SIZE_DP = 44

/** 面板图标尺寸 dp */
private const val ICON_SIZE_DP = 24

/**
 * 展开后的横向控制面板。
 *
 * 按钮：播放/暂停（同一按钮随引擎状态切换图标与语义）、停止；
 * multi 模式额外显示：+点击、+滑动、小眼睛（图标随隐藏状态切换）。
 */
class ControlPanel(context: Context) : LinearLayout(context) {

    private val iconPx = dpToPx(context, ICON_SIZE_DP)

    /** multi 模式专属控件（含分隔线），single 模式整体隐藏 */
    private val multiViews = mutableListOf<View>()

    private lateinit var playImage: ImageView

    private var engineState: EngineState = EngineState.Idle
    private var hiddenState: Boolean = false
    private var built: Boolean = false

    init {
        orientation = HORIZONTAL
        background = roundRectBackground(COLOR_PANEL_BG, dpToPxF(context, 22))
        val pad = dpToPx(context, 6)
        setPadding(pad, pad, pad, pad)
        elevation = dpToPxF(context, 10)
    }

    /**
     * 装配按钮与回调（仅一次）。
     *
     * @param onPlayPause 点击播放/暂停按钮（运行中=暂停，其余=播放/继续）
     */
    fun bind(
        onPlayPause: () -> Unit,
        onStop: () -> Unit,
        onAddTap: () -> Unit,
        onAddSwipe: () -> Unit,
        onToggleHidden: () -> Unit,
    ) {
        if (built) return
        built = true

        // 播放/暂停
        playImage = ImageView(context)
        addView(
            makeButton(playImage, onPlayPause),
            buttonLayoutParams(),
        )

        // 停止
        val stopImage = ImageView(context).apply {
            setImageDrawable(GlyphDrawable(Glyph.STOP, COLOR_DANGER, iconPx))
            contentDescription = "停止"
        }
        addView(makeButton(stopImage, onStop), buttonLayoutParams())

        // ---- multi 模式专属分组 ----
        addView(makeDivider())

        val tapImage = ImageView(context).apply {
            setImageDrawable(GlyphDrawable(Glyph.ADD, COLOR_PRIMARY, iconPx))
            contentDescription = "添加点击目标"
        }
        val tapButton = makeButton(tapImage, onAddTap)
        addView(tapButton, buttonLayoutParams())
        multiViews += tapButton

        val swipeImage = ImageView(context).apply {
            setImageDrawable(GlyphDrawable(Glyph.SWIPE, COLOR_PRIMARY, iconPx))
            contentDescription = "添加滑动目标"
        }
        val swipeButton = makeButton(swipeImage, onAddSwipe)
        addView(swipeButton, buttonLayoutParams())
        multiViews += swipeButton

        val eyeImage = ImageView(context)
        val eyeButton = makeButton(eyeImage, onToggleHidden)
        addView(eyeButton, buttonLayoutParams())
        multiViews += eyeButton
        eyeTag = eyeImage

        setEngineState(EngineState.Idle)
        setHiddenState(false)
    }

    private var eyeTag: ImageView? = null

    /** 设置模式：multi 显示添加/小眼睛分组，single 仅保留播放/暂停、停止 */
    fun setMultiMode(multi: Boolean) {
        val visibility = if (multi) View.VISIBLE else View.GONE
        multiViews.forEach { it.visibility = visibility }
    }

    /** 根据引擎状态切换播放/暂停图标与语义 */
    fun setEngineState(state: EngineState) {
        engineState = state
        val glyph: Glyph
        val desc: String
        when (state) {
            EngineState.Running -> {
                glyph = Glyph.PAUSE
                desc = "暂停"
            }

            EngineState.Paused -> {
                glyph = Glyph.PLAY
                desc = "继续"
            }

            else -> {
                glyph = Glyph.PLAY
                desc = "播放"
            }
        }
        if (::playImage.isInitialized) {
            playImage.setImageDrawable(GlyphDrawable(glyph, COLOR_PRIMARY, iconPx))
            playImage.contentDescription = desc
        }
    }

    /** 根据隐藏状态切换小眼睛图标 */
    fun setHiddenState(hidden: Boolean) {
        hiddenState = hidden
        val image = eyeTag ?: return
        val glyph = if (hidden) Glyph.EYE_CLOSED else Glyph.EYE_OPEN
        image.setImageDrawable(GlyphDrawable(glyph, COLOR_ICON_GRAY, iconPx))
        image.contentDescription = if (hidden) "显示目标控件" else "隐藏目标控件"
    }

    private fun buttonLayoutParams(): LayoutParams =
        LayoutParams(dpToPx(context, BUTTON_SIZE_DP), dpToPx(context, BUTTON_SIZE_DP))

    /** 生成带水波纹的图标按钮 */
    private fun makeButton(image: ImageView, onClick: () -> Unit): FrameLayout {
        val size = dpToPx(context, BUTTON_SIZE_DP)
        return FrameLayout(context).apply {
            addView(
                image,
                FrameLayout.LayoutParams(iconPx, iconPx).apply { gravity = Gravity.CENTER },
            )
            resolveRipple()?.let { background = it }
            isClickable = true
            setOnClickListener { onClick() }
            layoutParams = LayoutParams(size, size)
        }
    }

    /** 生成分隔竖线，并记入 multi 专属控件 */
    private fun makeDivider(): View {
        val width = dpToPx(context, 1)
        val height = dpToPx(context, 24)
        return View(context).apply {
            setBackgroundColor(0x22000000)
            val top = dpToPx(context, 10)
            layoutParams = LayoutParams(width, height).apply {
                gravity = Gravity.CENTER_VERTICAL
                topMargin = top
                bottomMargin = top
                marginStart = dpToPx(context, 2)
                marginEnd = dpToPx(context, 2)
            }
            multiViews += this
        }
    }

    /** 解析系统水波纹背景 */
    private fun resolveRipple(): Drawable? {
        val outValue = TypedValue()
        val resolved = context.theme.resolveAttribute(
            android.R.attr.selectableItemBackgroundBorderless,
            outValue,
            true,
        )
        return if (resolved) {
            @Suppress("DEPRECATION")
            context.resources.getDrawable(outValue.resourceId, context.theme)
        } else {
            null
        }
    }
}
