package com.demirarch.pacbench.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.demirarch.pacbench.model.MetricId
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

enum class GraphMode {
    LIVE,
    SESSION,
}

data class PacBenchSettings(
    val samplingIntervalMillis: Long = DEFAULT_SAMPLING_INTERVAL_MILLIS,
    val autoDetectionTimeoutMillis: Long = DEFAULT_AUTO_DETECTION_TIMEOUT_MILLIS,
    val enabledMetrics: Set<MetricId> = MetricId.entries.toSet(),
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val graphMode: GraphMode = GraphMode.LIVE,
    val retentionDays: Int = DEFAULT_RETENTION_DAYS,
    val databaseCapBytes: Long = DEFAULT_DATABASE_CAP_BYTES,
    val pingEndpoint: String = DEFAULT_PING_ENDPOINT,
) {
    init {
        require(samplingIntervalMillis in 100L..60_000L)
        require(autoDetectionTimeoutMillis in 1_000L..300_000L)
        require(retentionDays in 1..3_650)
        require(databaseCapBytes >= 10L * 1024 * 1024)
        require(pingEndpoint.isNotBlank())
    }

    companion object {
        const val DEFAULT_SAMPLING_INTERVAL_MILLIS = 1_000L
        const val DEFAULT_AUTO_DETECTION_TIMEOUT_MILLIS = 10_000L
        const val DEFAULT_RETENTION_DAYS = 30
        const val DEFAULT_DATABASE_CAP_BYTES = 512L * 1024 * 1024
        const val DEFAULT_PING_ENDPOINT = "1.1.1.1"
    }
}

interface SettingsRepository {
    val settings: Flow<PacBenchSettings>

    suspend fun setSamplingIntervalMillis(value: Long)

    suspend fun setAutoDetectionTimeoutMillis(value: Long)

    suspend fun setEnabledMetrics(value: Set<MetricId>)

    suspend fun setTheme(value: ThemeMode)

    suspend fun setGraphMode(value: GraphMode)

    suspend fun setRetentionDays(value: Int)

    suspend fun setDatabaseCapBytes(value: Long)

    suspend fun setPingEndpoint(value: String)

    suspend fun update(transform: (PacBenchSettings) -> PacBenchSettings)

    suspend fun reset()
}

val Context.pacBenchSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "pacbench_settings",
)

class DataStoreSettingsRepository(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {
    constructor(context: Context) : this(context.pacBenchSettingsDataStore)

    override val settings: Flow<PacBenchSettings> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map(::readSettings)

    override suspend fun setSamplingIntervalMillis(value: Long) {
        require(value in 100L..60_000L)
        dataStore.edit { it[Keys.samplingInterval] = value }
    }

    override suspend fun setAutoDetectionTimeoutMillis(value: Long) {
        require(value in 1_000L..300_000L)
        dataStore.edit { it[Keys.autoDetectionTimeout] = value }
    }

    override suspend fun setEnabledMetrics(value: Set<MetricId>) {
        dataStore.edit { it[Keys.enabledMetrics] = value.mapTo(mutableSetOf()) { metric -> metric.name } }
    }

    override suspend fun setTheme(value: ThemeMode) {
        dataStore.edit { it[Keys.theme] = value.name }
    }

    override suspend fun setGraphMode(value: GraphMode) {
        dataStore.edit { it[Keys.graphMode] = value.name }
    }

    override suspend fun setRetentionDays(value: Int) {
        require(value in 1..3_650)
        dataStore.edit { it[Keys.retentionDays] = value }
    }

    override suspend fun setDatabaseCapBytes(value: Long) {
        require(value >= 10L * 1024 * 1024)
        dataStore.edit { it[Keys.databaseCap] = value }
    }

    override suspend fun setPingEndpoint(value: String) {
        require(value.isNotBlank())
        dataStore.edit { it[Keys.pingEndpoint] = value.trim() }
    }

    override suspend fun update(transform: (PacBenchSettings) -> PacBenchSettings) {
        dataStore.edit { preferences ->
            writeSettings(preferences, transform(readSettings(preferences)))
        }
    }

    override suspend fun reset() {
        dataStore.edit { it.clear() }
    }

    private fun readSettings(preferences: Preferences): PacBenchSettings = PacBenchSettings(
        samplingIntervalMillis = preferences[Keys.samplingInterval]
            ?.takeIf { it in 100L..60_000L }
            ?: PacBenchSettings.DEFAULT_SAMPLING_INTERVAL_MILLIS,
        autoDetectionTimeoutMillis = preferences[Keys.autoDetectionTimeout]
            ?.takeIf { it in 1_000L..300_000L }
            ?: PacBenchSettings.DEFAULT_AUTO_DETECTION_TIMEOUT_MILLIS,
        enabledMetrics = preferences[Keys.enabledMetrics]
            ?.mapNotNullTo(mutableSetOf()) { name -> enumValueOrNull<MetricId>(name) }
            ?: MetricId.entries.toSet(),
        theme = enumValueOrNull(preferences[Keys.theme]) ?: ThemeMode.SYSTEM,
        graphMode = enumValueOrNull(preferences[Keys.graphMode]) ?: GraphMode.LIVE,
        retentionDays = preferences[Keys.retentionDays]
            ?.takeIf { it in 1..3_650 }
            ?: PacBenchSettings.DEFAULT_RETENTION_DAYS,
        databaseCapBytes = preferences[Keys.databaseCap]
            ?.takeIf { it >= 10L * 1024 * 1024 }
            ?: PacBenchSettings.DEFAULT_DATABASE_CAP_BYTES,
        pingEndpoint = preferences[Keys.pingEndpoint]
            ?.takeIf { it.isNotBlank() }
            ?: PacBenchSettings.DEFAULT_PING_ENDPOINT,
    )

    private fun writeSettings(
        preferences: MutablePreferences,
        settings: PacBenchSettings,
    ) {
        preferences[Keys.samplingInterval] = settings.samplingIntervalMillis
        preferences[Keys.autoDetectionTimeout] = settings.autoDetectionTimeoutMillis
        preferences[Keys.enabledMetrics] = settings.enabledMetrics.mapTo(mutableSetOf(), MetricId::name)
        preferences[Keys.theme] = settings.theme.name
        preferences[Keys.graphMode] = settings.graphMode.name
        preferences[Keys.retentionDays] = settings.retentionDays
        preferences[Keys.databaseCap] = settings.databaseCapBytes
        preferences[Keys.pingEndpoint] = settings.pingEndpoint
    }

    private object Keys {
        val samplingInterval = longPreferencesKey("sampling_interval_millis")
        val autoDetectionTimeout = longPreferencesKey("auto_detection_timeout_millis")
        val enabledMetrics = stringSetPreferencesKey("enabled_metrics")
        val theme = stringPreferencesKey("theme")
        val graphMode = stringPreferencesKey("graph_mode")
        val retentionDays = intPreferencesKey("retention_days")
        val databaseCap = longPreferencesKey("database_cap_bytes")
        val pingEndpoint = stringPreferencesKey("ping_endpoint")
    }
}

private inline fun <reified T : Enum<T>> enumValueOrNull(name: String?): T? =
    name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() }
