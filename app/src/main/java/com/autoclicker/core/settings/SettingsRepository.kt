package com.autoclicker.core.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.autoclicker.core.bus.RecordMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 应用级设置 DataStore 委托（DataStore 最佳实践：文件级单例、只创建一次）。
 *
 * 传入名称 "autoclicker_settings"，框架自动补后缀，
 * 实际落盘文件为 filesDir/datastore/autoclicker_settings.preferences_pb。
 */
private val Context.autoclickerDataStore: DataStore<Preferences> by preferencesDataStore(
    name = DATASTORE_NAME
)

/**
 * 设置仓库：基于 DataStore Preferences 持久化用户设置（FR-5 等）。
 *
 * 写入统一使用挂起的 [edit]；读取提供冷 [Flow]（响应后续变更）与一次性挂起 get 两种方式。
 *
 * @param context 任意 Context，内部取 applicationContext
 */
class SettingsRepository(context: Context) {

    private val dataStore = context.applicationContext.autoclickerDataStore

    /**
     * 录制模式（普通 / 精确），默认 [RecordMode.Normal]。
     * 以枚举名字符串持久化（键 "record_mode"），非法值回退默认值。
     */
    val recordMode: Flow<RecordMode> = dataStore.data.map { preferences ->
        preferences[RECORD_MODE_KEY]?.toRecordMode() ?: RecordMode.Normal
    }

    /**
     * 自动记录间隔延时开关：录制时是否把两次操作的时间间隔自动记为延时，
     * 默认开启（FR-5）。
     */
    val autoRecordIntervalEnabled: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[AUTO_RECORD_INTERVAL_KEY] ?: true
    }

    /** 设置录制模式（挂起，内部以事务方式 edit） */
    suspend fun setRecordMode(mode: RecordMode) {
        dataStore.edit { preferences ->
            preferences[RECORD_MODE_KEY] = mode.name
        }
    }

    /** 设置"自动记录间隔延时"开关（挂起） */
    suspend fun setAutoRecordIntervalEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[AUTO_RECORD_INTERVAL_KEY] = enabled
        }
    }

    /** 一次性读取录制模式（挂起，无 Flow 收集需求时使用） */
    suspend fun getRecordMode(): RecordMode = recordMode.first()

    /** 一次性读取"自动记录间隔延时"开关（挂起） */
    suspend fun getAutoRecordIntervalEnabled(): Boolean = autoRecordIntervalEnabled.first()

    /** 持久化字符串 → [RecordMode]；无法识别（如未来版本变更）时回退普通模式 */
    private fun String.toRecordMode(): RecordMode =
        runCatching { RecordMode.valueOf(this) }.getOrDefault(RecordMode.Normal)

    private companion object {
        /** DataStore 名称（实际文件自动追加 .preferences_pb 后缀） */
        const val DATASTORE_NAME = "autoclicker_settings"

        /** 录制模式键（值：Normal / Precise） */
        val RECORD_MODE_KEY = stringPreferencesKey("record_mode")

        /** 自动记录间隔延时开关键 */
        val AUTO_RECORD_INTERVAL_KEY = booleanPreferencesKey("auto_record_interval")
    }
}
