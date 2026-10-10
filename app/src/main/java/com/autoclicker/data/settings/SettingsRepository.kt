package com.autoclicker.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** 应用设置。 */
data class AppSettings(
    val floatingBallEnabled: Boolean = true,
    val defaultStepDelayMs: Long = 0L,
    val overlayOpacity: Float = 0.9f,
    val logPanelMaxLines: Int = 200,
    val autoRecordDelay: Boolean = true,
)

/** 设置仓库（DataStore）。 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val floatingBall = booleanPreferencesKey("floating_ball")
        val defaultStepDelay = longPreferencesKey("default_step_delay")
        val overlayOpacity = floatPreferencesKey("overlay_opacity")
        val logPanelMaxLines = intPreferencesKey("log_panel_max_lines")
        val autoRecordDelay = booleanPreferencesKey("auto_record_delay")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { p ->
        AppSettings(
            floatingBallEnabled = p[Keys.floatingBall] ?: true,
            defaultStepDelayMs = p[Keys.defaultStepDelay] ?: 0L,
            overlayOpacity = p[Keys.overlayOpacity] ?: 0.9f,
            logPanelMaxLines = p[Keys.logPanelMaxLines] ?: 200,
            autoRecordDelay = p[Keys.autoRecordDelay] ?: true,
        )
    }

    suspend fun setFloatingBallEnabled(enabled: Boolean) = context.settingsDataStore.edit {
        it[Keys.floatingBall] = enabled
    }

    suspend fun setDefaultStepDelay(ms: Long) = context.settingsDataStore.edit {
        it[Keys.defaultStepDelay] = ms
    }

    suspend fun setOverlayOpacity(opacity: Float) = context.settingsDataStore.edit {
        it[Keys.overlayOpacity] = opacity
    }

    suspend fun setLogPanelMaxLines(lines: Int) = context.settingsDataStore.edit {
        it[Keys.logPanelMaxLines] = lines
    }

    suspend fun setAutoRecordDelay(enabled: Boolean) = context.settingsDataStore.edit {
        it[Keys.autoRecordDelay] = enabled
    }
}