package com.autoclicker.di

import android.content.Context
import com.autoclicker.data.repository.FileFunctionPackageRepository
import com.autoclicker.data.repository.FileScriptRepository
import com.autoclicker.data.repository.FileTemplateRepository
import com.autoclicker.data.repository.FileTextGroupRepository
import com.autoclicker.data.repository.FunctionPackageRepository
import com.autoclicker.data.repository.ScriptRepository
import com.autoclicker.data.repository.TemplateRepository
import com.autoclicker.data.repository.TextGroupRepository
import com.autoclicker.data.settings.SettingsRepository
import com.autoclicker.platform.ColorFinder
import com.autoclicker.platform.GestureExecutor
import com.autoclicker.platform.GlobalActions
import com.autoclicker.platform.ImageFinder
import com.autoclicker.platform.NodeLocator
import com.autoclicker.platform.OcrEngine
import com.autoclicker.platform.OverlayController
import com.autoclicker.platform.PlaybackController
import com.autoclicker.platform.ScreenSource
import java.io.File

/**
 * 轻量手动依赖装配（不引入 Hilt，降低构建复杂度）。
 *
 * - 数据仓库与应用内单例在此提供；
 * - 运行期平台能力（无障碍手势、节点、截屏、悬浮窗、播放器、识别器）由各层在启动时注册，
 *   见 [registerPlatform] 等 setter。
 */
object ServiceLocator {

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
        NotificationChannels.ensure(appContext)
    }

    val context: Context get() = appContext

    private val dataDir: File get() = File(appContext.filesDir, "data")

    val scripts: ScriptRepository by lazy { FileScriptRepository(dataDir) }
    val packages: FunctionPackageRepository by lazy { FileFunctionPackageRepository(dataDir) }
    val templates: TemplateRepository by lazy { FileTemplateRepository(dataDir) }
    val textGroups: TextGroupRepository by lazy { FileTextGroupRepository(dataDir) }
    val settings: SettingsRepository by lazy { SettingsRepository(appContext) }

    // ---------------- 运行期平台能力（由各层注册） ----------------

    @Volatile
    var gestureExecutor: GestureExecutor? = null

    @Volatile
    var globalActions: GlobalActions? = null

    @Volatile
    var nodeLocator: NodeLocator? = null

    @Volatile
    var screenSource: ScreenSource? = null

    @Volatile
    var imageFinder: ImageFinder? = null

    @Volatile
    var colorFinder: ColorFinder? = null

    @Volatile
    var ocrEngine: OcrEngine? = null

    @Volatile
    var player: PlaybackController? = null

    @Volatile
    var overlay: OverlayController? = null
}