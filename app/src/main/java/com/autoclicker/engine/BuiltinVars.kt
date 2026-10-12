package com.autoclicker.engine

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import com.autoclicker.di.ServiceLocator
import java.util.Calendar

/**
 * 系统内置变量（见功能清单 §6.2）：屏幕宽/高 与 年/月/日/时/分/秒。
 *
 * - 中英文名都支持，例如 `screenWidth` 与 `屏幕宽` 等价；
 * - 每次读取时求值，时间类变量会随时间变化，不需要预先赋值；
 * - 用户变量同名时优先用用户变量（查找顺序见 [ExecutionContext.get]）。
 */
object BuiltinVars {

    /** 内置变量名（供界面提示）。 */
    val names: List<String> = listOf(
        "screenWidth", "screenHeight",
        "year", "month", "day", "hour", "minute", "second",
    )

    /** 解析内置变量；非内置名返回 null。 */
    fun resolve(name: String): String? {
        val now = Calendar.getInstance()
        return when (name.trim()) {
            "screenWidth", "屏幕宽" -> screenSize()?.first?.toString()
            "screenHeight", "屏幕高" -> screenSize()?.second?.toString()
            "year", "年" -> now.get(Calendar.YEAR).toString()
            "month", "月" -> (now.get(Calendar.MONTH) + 1).toString()
            "day", "日" -> now.get(Calendar.DAY_OF_MONTH).toString()
            "hour", "时" -> now.get(Calendar.HOUR_OF_DAY).toString()
            "minute", "分" -> now.get(Calendar.MINUTE).toString()
            "second", "秒" -> now.get(Calendar.SECOND).toString()
            else -> null
        }
    }

    /** 取当前屏幕像素尺寸；取不到返回 null。 */
    @Suppress("DEPRECATION")
    private fun screenSize(): Pair<Int, Int>? = runCatching {
        val wm = ServiceLocator.context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (wm == null) {
            val dm = ServiceLocator.context.resources.displayMetrics
            dm.widthPixels to dm.heightPixels
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val dm = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
    }.getOrNull()
}
