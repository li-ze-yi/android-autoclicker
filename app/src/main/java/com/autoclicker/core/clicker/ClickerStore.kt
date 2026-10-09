package com.autoclicker.core.clicker

import android.content.Context
import kotlinx.serialization.json.Json

/**
 * 点击器配置的持久化：SharedPreferences + kotlinx JSON。
 * 所有读写均做异常兜底，失败时返回默认配置或静默忽略。
 */
internal class ClickerStore(context: Context) {

    private val appContext: Context = context.applicationContext
    private val prefs =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 读取配置，失败返回默认 [ClickerConfig]。 */
    fun load(): ClickerConfig {
        val raw = prefs.getString(KEY_CONFIG, null) ?: return ClickerConfig()
        return try {
            json.decodeFromString(ClickerConfig.serializer(), raw)
        } catch (e: Exception) {
            ClickerConfig()
        }
    }

    /** 写入配置，失败静默忽略。 */
    fun save(config: ClickerConfig) {
        try {
            prefs.edit()
                .putString(KEY_CONFIG, json.encodeToString(ClickerConfig.serializer(), config))
                .apply()
        } catch (e: Exception) {
            // 忽略写入异常
        }
    }

    /** 清除已保存的配置。 */
    fun clear() {
        try {
            prefs.edit().remove(KEY_CONFIG).apply()
        } catch (e: Exception) {
            // 忽略清除异常
        }
    }

    private companion object {
        const val PREFS_NAME = "autoclicker_clicker"
        const val KEY_CONFIG = "config"

        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}