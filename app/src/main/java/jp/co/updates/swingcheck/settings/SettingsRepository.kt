package jp.co.updates.swingcheck.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * @param defaultLengthUnit lengthUnit が未設定のときの値（端末の地域から決める）
 */
class SettingsRepository(
    private val dataStore: DataStore<Preferences>,
    private val defaultLengthUnit: LengthUnit,
) {
    val settings: Flow<AppSettings> = dataStore.data.map { p ->
        AppSettings(
            fpsMode = p[FPS_MODE]?.let { name -> FpsMode.entries.firstOrNull { it.name == name } } ?: FpsMode.AUTO,
            defaultHeightCm = p[DEFAULT_HEIGHT_CM],
            lengthUnit = p[LENGTH_UNIT]?.let { name -> LengthUnit.entries.firstOrNull { it.name == name } }
                ?: defaultLengthUnit,
            practiceFilterEnabled = p[PRACTICE_FILTER_ENABLED] ?: true,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setFpsMode(mode: FpsMode) {
        dataStore.edit { it[FPS_MODE] = mode.name }
    }

    /** null で未設定に戻す。 */
    suspend fun setDefaultHeightCm(heightCm: Float?) {
        dataStore.edit { if (heightCm == null) it.remove(DEFAULT_HEIGHT_CM) else it[DEFAULT_HEIGHT_CM] = heightCm }
    }

    suspend fun setLengthUnit(unit: LengthUnit) {
        dataStore.edit { it[LENGTH_UNIT] = unit.name }
    }

    suspend fun setPracticeFilterEnabled(enabled: Boolean) {
        dataStore.edit { it[PRACTICE_FILTER_ENABLED] = enabled }
    }

    private companion object {
        val FPS_MODE = stringPreferencesKey("fpsMode")
        val DEFAULT_HEIGHT_CM = floatPreferencesKey("defaultHeightCm")
        val LENGTH_UNIT = stringPreferencesKey("lengthUnit")
        val PRACTICE_FILTER_ENABLED = booleanPreferencesKey("practiceFilterEnabled")
    }
}
