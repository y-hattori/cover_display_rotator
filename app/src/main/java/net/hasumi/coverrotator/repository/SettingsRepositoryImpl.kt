package net.hasumi.coverrotator.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepositoryImpl(
    private val context: Context
) : SettingsRepository {

    private object Keys {
        val AUTO_ROTATION_PREF = booleanPreferencesKey("auto_rotation_enabled")
        val ROTATION_INVERSION_PREF = booleanPreferencesKey("rotation_inversion_enabled")
        val DEADBAND_DEGREES_PREF = doublePreferencesKey("deadband_degrees")
        val DEBOUNCE_MS_PREF = longPreferencesKey("debounce_ms")
        val ADB_CONNECTION_PORT_PREF = intPreferencesKey("adb_connection_port")
        val ADB_HOST_PREF = stringPreferencesKey("adb_host")
        val STABILITY_MS_PREF = longPreferencesKey("stability_ms")
        val START_ON_BOOT_PREF = booleanPreferencesKey("start_on_boot")
    }

    override val autoRotationEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.AUTO_ROTATION_PREF] ?: false }
    override val rotationInversionEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.ROTATION_INVERSION_PREF] ?: false }
    override val deadbandDegrees: Flow<Double> =
        context.dataStore.data.map { it[Keys.DEADBAND_DEGREES_PREF] ?: 30.0 }
    override val debounceMs: Flow<Long> =
        context.dataStore.data.map { it[Keys.DEBOUNCE_MS_PREF] ?: 400L }
    override val adbConnectionPort: Flow<Int?> =
        context.dataStore.data.map { it[Keys.ADB_CONNECTION_PORT_PREF] }
    override val adbHost: Flow<String?> =
        context.dataStore.data.map { it[Keys.ADB_HOST_PREF] }
    override val stabilityMs: Flow<Long> =
        context.dataStore.data.map { it[Keys.STABILITY_MS_PREF] ?: 500L }
    override val startOnBoot: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.START_ON_BOOT_PREF] ?: true }

    override suspend fun setAutoRotationEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_ROTATION_PREF] = enabled }
    }

    override suspend fun setRotationInversionEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.ROTATION_INVERSION_PREF] = enabled }
    }

    override suspend fun setDeadbandDegrees(degrees: Double) {
        context.dataStore.edit { it[Keys.DEADBAND_DEGREES_PREF] = degrees }
    }

    override suspend fun setDebounceMs(ms: Long) {
        context.dataStore.edit { it[Keys.DEBOUNCE_MS_PREF] = ms }
    }

    override suspend fun setAdbConnectionPort(port: Int?) {
        context.dataStore.edit {
            if (port != null) {
                it[Keys.ADB_CONNECTION_PORT_PREF] = port
            } else {
                it.remove(Keys.ADB_CONNECTION_PORT_PREF)
            }
        }
    }

    override suspend fun setAdbHost(host: String?) {
        context.dataStore.edit {
            if (host != null) {
                it[Keys.ADB_HOST_PREF] = host
            } else {
                it.remove(Keys.ADB_HOST_PREF)
            }
        }
    }

    override suspend fun setStabilityMs(ms: Long) {
        context.dataStore.edit { it[Keys.STABILITY_MS_PREF] = ms }
    }
    
    override suspend fun setStartOnBoot(enabled: Boolean) {
        context.dataStore.edit { it[Keys.START_ON_BOOT_PREF] = enabled }
    }
}
