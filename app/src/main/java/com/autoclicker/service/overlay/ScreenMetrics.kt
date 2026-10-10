package com.autoclicker.service.overlay

import android.content.Context
import android.graphics.Point
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * 屏幕尺寸与坐标工具。
 *
 * 统一以 WindowManager.maximumWindowMetrics 获取整块屏幕尺寸（含系统栏/导航栏区域），
 * 它与 MotionEvent.rawX/rawY 处于同一屏幕绝对坐标空间；所有悬浮控件定位只允许使用
 * rawX/rawY 绝对坐标，严禁基于窗口相对坐标计算。
 */
object ScreenMetrics {

    /** 屏幕宽（像素） */
    fun width(wm: WindowManager): Int = size(wm).x

    /** 屏幕高（像素） */
    fun height(wm: WindowManager): Int = size(wm).y

    private fun size(wm: WindowManager): Point {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // API 30+：官方推荐写法
            val bounds = wm.maximumWindowMetrics.bounds
            return Point(bounds.width(), bounds.height())
        }
        // API 26~29 兜底：maximumWindowMetrics 自 API 30 才提供
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        return Point(metrics.widthPixels, metrics.heightPixels)
    }
}

/** dp 转 px（返回 Int） */
fun dpToPx(context: Context, dp: Int): Int = dpToPxF(context, dp).toInt()

/** dp 转 px（返回 Float，用于圆角/描边等） */
fun dpToPxF(context: Context, dp: Int): Float =
    dp * context.resources.displayMetrics.density

/** sp 转 px（用于目标控件上的文字） */
fun spToPx(context: Context, sp: Int): Float =
    sp * context.resources.displayMetrics.scaledDensity
