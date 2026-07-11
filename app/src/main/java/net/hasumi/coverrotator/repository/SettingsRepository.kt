package net.hasumi.coverrotator.repository

import kotlinx.coroutines.flow.Flow

/**
 * Interface for managing application settings using DataStore.
 */
interface SettingsRepository {
    val autoRotationEnabled: Flow<Boolean>
    val rotationInversionEnabled: Flow<Boolean>
    val deadbandDegrees: Flow<Double>
    val debounceMs: Flow<Long>
    val adbConnectionPort: Flow<Int?>
    val adbHost: Flow<String?>
    val stabilityMs: Flow<Long>
    val startOnBoot: Flow<Boolean>

    suspend fun setAutoRotationEnabled(enabled: Boolean)
    suspend fun setRotationInversionEnabled(enabled: Boolean)
    suspend fun setDeadbandDegrees(degrees: Double)
    suspend fun setDebounceMs(ms: Long)
    suspend fun setAdbConnectionPort(port: Int?)
    suspend fun setAdbHost(host: String?)
    suspend fun setStabilityMs(ms: Long)
    suspend fun setStartOnBoot(enabled: Boolean)
}