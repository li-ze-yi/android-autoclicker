package com.autoclicker.core.vision

import android.content.Context

/**
 * 识图的全局默认设置（SharedPreferences 持久化）。
 *
 * 目前仅包含新建识图步骤时使用的默认相似度阈值；读写全部 try/catch，失败时返回安全值。
 */
class VisionSettings private constructor(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "autoclicker_vision"
        private const val KEY_DEFAULT_THRESHOLD = "default_threshold"
        private const val DEFAULT_THRESHOLD = 85

        @Volatile
        private var INSTANCE: VisionSettings? = null

        fun get(context: Context): VisionSettings {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: VisionSettings(context).also { INSTANCE = it }
            }
        }
    }

    /** 默认匹配相似度阈值（0..100），新建识图步骤时使用。 */
    var defaultThresholdPercent: Int
        get() = try {
            prefs.getInt(KEY_DEFAULT_THRESHOLD, DEFAULT_THRESHOLD).coerceIn(0, 100)
        } catch (e: Exception) {
            DEFAULT_THRESHOLD
        }
        set(value) {
            try {
                prefs.edit().putInt(KEY_DEFAULT_THRESHOLD, value.coerceIn(0, 100)).apply()
            } catch (e: Exception) {
                // 忽略写入异常
            }
        }
}