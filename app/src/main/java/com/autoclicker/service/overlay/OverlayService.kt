package com.autoclicker.service.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import com.autoclicker.MyApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

/**
 * 悬浮窗服务：悬浮球 + 控制面板 + 屏幕目标控件。
 *
 * - 非前台媒体服务：本任务不实现通知与 startForeground 逻辑；play 时可由外部
 *   （Task 7/引擎）调用 startForeground。
 * - 悬浮层全部使用传统 Android View + WindowManager（不使用 Compose），
 *   窗口类型统一 TYPE_APPLICATION_OVERLAY，所有 WindowManager 操作都在主线程。
 * - 本服务不铺设精确录制触摸层（Task 8）；悬浮控件触摸事件不会写入录制数据。
 */
class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var app: MyApplication
    private lateinit var scope: CoroutineScope
    private lateinit var controller: OverlayController
    private lateinit var manager: TargetOverlayManager
    private lateinit var ball: FloatingBall

    /** 控制面板根视图及其窗口句柄 */
    private lateinit var panel: ControlPanel
    private var panelHandle: WindowHandle? = null
    private var panelShown: Boolean = false

    /** 当前模式：single / multi */
    private var mode: String = MODE_MULTI

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        app = application as MyApplication

        // 主线程作用域：onDestroy 统一取消
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        controller = app.overlayController

        // 目标控件管理器（先创建，连线层位于悬浮球之下）
        manager = TargetOverlayManager(this, wm, app.targetController, scope) {
            reorderChrome()
        }

        // 悬浮球
        ball = FloatingBall(
            context = this,
            wm = wm,
            onClick = { togglePanel() },
            onDragStart = { collapsePanel() },
        )
        ball.attach()

        // 控制面板（初始不挂载，展开时才挂载）
        panel = ControlPanel(this)
        panel.bind(
            onPlayPause = {
                if (app.bus.engineState.value == com.autoclicker.core.bus.EngineState.Running) {
                    controller.onPause()
                } else {
                    controller.onPlay()
                }
            },
            onStop = { controller.onStop() },
            onAddTap = { addTapAtCenter() },
            onAddSwipe = { addDefaultSwipe() },
            onToggleHidden = {
                val hidden = controller.onToggleHidden()
                toast(if (hidden) "目标控件已隐藏" else "目标控件已显示")
            },
        )

        // 引擎状态 → 播放/暂停图标同步
        scope.launch {
            app.bus.engineState.collect { state -> panel.setEngineState(state) }
        }
        // 隐藏状态 → 小眼睛图标同步
        scope.launch {
            app.targetController.hidden.collect { hidden -> panel.setHiddenState(hidden) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val extra = intent?.getStringExtra(EXTRA_MODE)
        mode = if (extra == MODE_SINGLE) MODE_SINGLE else MODE_MULTI
        panel.setMultiMode(mode == MODE_MULTI)

        if (mode == MODE_SINGLE) {
            // 单目标模式：无目标时在屏幕中部准备一个点击目标
            if (app.targetController.targets.value.isEmpty()) {
                addTapAtCenter()
            }
        }
        // 不使用 sticky：系统重启服务时不自动恢复，由 UI 层显式启动，避免状态不自洽
        return START_NOT_STICKY
    }

    // -- 面板展开/收起 -------------------------------------------------------

    /** 切换面板展开状态 */
    private fun togglePanel() {
        if (panelShown) collapsePanel() else expandPanel()
    }

    /** 展开面板：停靠在悬浮球旁边（靠右优先，空间不足则靠左） */
    private fun expandPanel() {
        if (panelShown) return

        // 先测量得到面板尺寸
        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        panel.measure(unspecified, unspecified)
        val pw = panel.measuredWidth
        val ph = panel.measuredHeight

        val ballP = ball.handle.params
        val ballSize = ball.handle.view.width.takeIf { it > 0 } ?: ballP.width
        val gap = dpToPx(this, 8)

        var px = ballP.x + ballSize + gap
        if (px + pw > ScreenMetrics.width(wm)) {
            // 右侧放不下，放到悬浮球左侧
            px = ballP.x - gap - pw
        }
        px = px.coerceIn(0, (ScreenMetrics.width(wm) - pw).coerceAtLeast(0))

        var py = ballP.y + ballSize / 2 - ph / 2
        py = py.coerceIn(0, (ScreenMetrics.height(wm) - ph).coerceAtLeast(0))

        val params = overlayParams(pw, ph).apply {
            x = px
            y = py
        }
        val handle = WindowHandle(panel, params)
        handle.attach(wm)
        panelHandle = handle
        panelShown = true
    }

    /** 收起面板 */
    private fun collapsePanel() {
        if (!panelShown) return
        panelHandle?.detach(wm)
        panelHandle = null
        panelShown = false
    }

    /**
     * 目标窗口增删后重排层级：悬浮球与面板重新挂载到最顶层，避免被目标视图遮挡。
     */
    private fun reorderChrome() {
        ball.reorder()
        if (panelShown) {
            val handle = panelHandle ?: return
            if (handle.attached) {
                handle.detach(wm)
                handle.attach(wm)
            }
        }
    }

    // -- 添加目标 ------------------------------------------------------------

    /** 在屏幕中部添加点击目标 */
    private fun addTapAtCenter() {
        val cx = ScreenMetrics.width(wm) / 2
        val cy = ScreenMetrics.height(wm) / 2
        runCatching { controller.onAddTap(cx, cy) }
            .onFailure { toast(it.message ?: "添加失败") }
    }

    /** 添加默认滑动目标：S1/S2 在屏幕中部纵向排列（间距约 90dp） */
    private fun addDefaultSwipe() {
        val cx = ScreenMetrics.width(wm) / 2
        val cy = ScreenMetrics.height(wm) / 2
        val offset = dpToPx(this, 90)
        val y1 = (cy - offset).coerceAtLeast(0)
        val y2 = (cy + offset).coerceAtMost(ScreenMetrics.height(wm))
        runCatching { controller.onAddSwipe(cx, y1, cx, y2) }
            .onFailure { toast(it.message ?: "添加失败") }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        collapsePanel()
        manager.shutdown()
        ball.detach()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** Intent extra：模式，取值 "single" / "multi" */
        const val EXTRA_MODE: String = "com.autoclicker.overlay.MODE"

        /** 单目标模式 */
        const val MODE_SINGLE: String = "single"

        /** 多目标模式（默认） */
        const val MODE_MULTI: String = "multi"

        /**
         * 启动悬浮窗服务。
         * @param mode [MODE_SINGLE] 或 [MODE_MULTI]，默认 multi
         */
        fun start(context: Context, mode: String = MODE_MULTI) {
            val intent = Intent(context, OverlayService::class.java).apply {
                putExtra(EXTRA_MODE, mode)
            }
            context.startService(intent)
        }

        /** 停止悬浮窗服务 */
        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }
}
