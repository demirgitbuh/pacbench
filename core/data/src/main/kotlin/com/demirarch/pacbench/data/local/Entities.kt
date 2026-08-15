package com.demirarch.pacbench.data.local

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import com.demirarch.pacbench.model.AccessMode
import com.demirarch.pacbench.model.SampleData

@Entity(
    tableName = "games",
    indices = [Index(value = ["package_name"], unique = true)],
)
data class Game(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "package_name")
    val packageName: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "version_name")
    val versionName: String? = null,
    @ColumnInfo(name = "version_code")
    val versionCode: Long? = null,
    @ColumnInfo(name = "is_favorite")
    val isFavorite: Boolean = false,
    @ColumnInfo(name = "first_seen_at")
    val firstSeenAt: Long,
    @ColumnInfo(name = "last_seen_at")
    val lastSeenAt: Long,
    @ColumnInfo(name = "last_played_at")
    val lastPlayedAt: Long? = null,
)

enum class SessionStatus {
    RUNNING,
    COMPLETED,
    INTERRUPTED,
}

@Entity(
    tableName = "performance_sessions",
    foreignKeys = [
        ForeignKey(
            entity = Game::class,
            parentColumns = ["id"],
            childColumns = ["game_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["game_id"]),
        Index(value = ["started_at"]),
        Index(value = ["game_id", "started_at"]),
    ],
)
data class PerformanceSession(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "game_id")
    val gameId: Long,
    @ColumnInfo(name = "started_at")
    val startedAt: Long,
    @ColumnInfo(name = "ended_at")
    val endedAt: Long? = null,
    @ColumnInfo(name = "access_mode")
    val accessMode: AccessMode,
    val status: SessionStatus = SessionStatus.RUNNING,
    @ColumnInfo(name = "device_manufacturer")
    val deviceManufacturer: String,
    @ColumnInfo(name = "device_model")
    val deviceModel: String,
    @ColumnInfo(name = "android_version")
    val androidVersion: String,
    @ColumnInfo(name = "app_version")
    val appVersion: String,
    @ColumnInfo(name = "data_quality_summary")
    val dataQualitySummary: String = "",
    val notes: String? = null,
)

@Entity(
    tableName = "performance_samples",
    foreignKeys = [
        ForeignKey(
            entity = PerformanceSession::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["session_id"]),
        Index(value = ["timestamp"]),
        Index(value = ["session_id", "timestamp"]),
    ],
)
data class PerformanceSample(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "session_id")
    val sessionId: Long,
    val timestamp: Long,
    val fps: Double? = null,
    @ColumnInfo(name = "frame_time")
    val frameTime: Double? = null,
    @ColumnInfo(name = "cpu_usage")
    val cpuUsage: Double? = null,
    @ColumnInfo(name = "cpu_frequency")
    val cpuFrequency: Double? = null,
    @ColumnInfo(name = "cpu_temp")
    val cpuTemp: Double? = null,
    @ColumnInfo(name = "gpu_usage")
    val gpuUsage: Double? = null,
    @ColumnInfo(name = "gpu_frequency")
    val gpuFrequency: Double? = null,
    @ColumnInfo(name = "gpu_temp")
    val gpuTemp: Double? = null,
    @ColumnInfo(name = "ram_used")
    val ramUsed: Long? = null,
    @ColumnInfo(name = "ram_available")
    val ramAvailable: Long? = null,
    @ColumnInfo(name = "battery_level")
    val batteryLevel: Double? = null,
    @ColumnInfo(name = "battery_temp")
    val batteryTemp: Double? = null,
    val voltage: Double? = null,
    val current: Double? = null,
    @ColumnInfo(name = "power_watts")
    val powerWatts: Double? = null,
    @ColumnInfo(name = "download_rate")
    val downloadRate: Double? = null,
    @ColumnInfo(name = "upload_rate")
    val uploadRate: Double? = null,
    val ping: Double? = null,
    @ColumnInfo(name = "thermal_state")
    val thermalState: Int? = null,
)

@Entity(
    tableName = "hud_presets",
    indices = [Index(value = ["name"])],
)
data class HudPresetEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    @ColumnInfo(name = "preset_json")
    val presetJson: String,
    @ColumnInfo(name = "is_built_in")
    val isBuiltIn: Boolean,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

data class GameWithSessions(
    @Embedded
    val game: Game,
    @Relation(parentColumn = "id", entityColumn = "game_id")
    val sessions: List<PerformanceSession>,
)

data class SessionWithGameAndSamples(
    @Embedded
    val session: PerformanceSession,
    @Relation(parentColumn = "game_id", entityColumn = "id")
    val game: Game,
    @Relation(parentColumn = "id", entityColumn = "session_id")
    val samples: List<PerformanceSample>,
)

data class SessionListItem(
    @Embedded
    val session: PerformanceSession,
    @ColumnInfo(name = "game_package_name")
    val gamePackageName: String,
    @ColumnInfo(name = "game_display_name")
    val gameDisplayName: String,
    @ColumnInfo(name = "sample_count")
    val sampleCount: Long,
)

data class GameAggregateStats(
    val gameId: Long,
    val packageName: String,
    val displayName: String,
    val sessionCount: Long,
    val sampleCount: Long,
    val totalDurationMillis: Long,
    val averageFps: Double?,
    val minFps: Double?,
    val maxFps: Double?,
    val averageCpuUsage: Double?,
    val averageGpuUsage: Double?,
    val peakCpuTemp: Double?,
    val peakGpuTemp: Double?,
    val lastSessionAt: Long?,
)

data class SessionStartRequest(
    val packageName: String,
    val displayName: String,
    val versionName: String? = null,
    val versionCode: Long? = null,
    val startedAt: Long,
    val accessMode: AccessMode,
    val deviceManufacturer: String,
    val deviceModel: String,
    val androidVersion: String,
    val appVersion: String,
    val notes: String? = null,
)

fun PerformanceSample.toSampleData(): SampleData = SampleData(
    timestamp = timestamp,
    fps = fps,
    frameTime = frameTime,
    cpuUsage = cpuUsage,
    cpuFrequency = cpuFrequency,
    cpuTemp = cpuTemp,
    gpuUsage = gpuUsage,
    gpuFrequency = gpuFrequency,
    gpuTemp = gpuTemp,
    ramUsed = ramUsed,
    ramAvailable = ramAvailable,
    batteryLevel = batteryLevel,
    batteryTemp = batteryTemp,
    voltage = voltage,
    current = current,
    powerWatts = powerWatts,
    downloadRate = downloadRate,
    uploadRate = uploadRate,
    ping = ping,
    thermalState = thermalState,
)

fun SampleData.toEntity(sessionId: Long): PerformanceSample = PerformanceSample(
    sessionId = sessionId,
    timestamp = timestamp,
    fps = fps,
    frameTime = frameTime,
    cpuUsage = cpuUsage,
    cpuFrequency = cpuFrequency,
    cpuTemp = cpuTemp,
    gpuUsage = gpuUsage,
    gpuFrequency = gpuFrequency,
    gpuTemp = gpuTemp,
    ramUsed = ramUsed,
    ramAvailable = ramAvailable,
    batteryLevel = batteryLevel,
    batteryTemp = batteryTemp,
    voltage = voltage,
    current = current,
    powerWatts = powerWatts,
    downloadRate = downloadRate,
    uploadRate = uploadRate,
    ping = ping,
    thermalState = thermalState,
)

internal fun SampleData.hasOnlyFiniteMetricValues(): Boolean =
    fps.isNullOrFinite() &&
        frameTime.isNullOrFinite() &&
        cpuUsage.isNullOrFinite() &&
        cpuFrequency.isNullOrFinite() &&
        cpuTemp.isNullOrFinite() &&
        gpuUsage.isNullOrFinite() &&
        gpuFrequency.isNullOrFinite() &&
        gpuTemp.isNullOrFinite() &&
        batteryLevel.isNullOrFinite() &&
        batteryTemp.isNullOrFinite() &&
        voltage.isNullOrFinite() &&
        current.isNullOrFinite() &&
        powerWatts.isNullOrFinite() &&
        downloadRate.isNullOrFinite() &&
        uploadRate.isNullOrFinite() &&
        ping.isNullOrFinite()

private fun Double?.isNullOrFinite(): Boolean = this == null || isFinite()
