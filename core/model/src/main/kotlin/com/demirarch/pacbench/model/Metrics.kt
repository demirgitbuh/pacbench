package com.demirarch.pacbench.model

import kotlinx.serialization.Serializable

@Serializable
enum class AccessMode { NORMAL, SHIZUKU, ROOT }

@Serializable
enum class MetricSource { ANDROID_API, PROCFS, SYSFS, SHIZUKU_SHELL, ROOT_SHELL, SURFACE_FLINGER, GFXINFO }

@Serializable
enum class MetricStatus {
    AVAILABLE,
    UNSUPPORTED_API,
    PERMISSION_DENIED,
    SOURCE_ABSENT,
    SCHEMA_MISMATCH,
    STALE,
    COUNTER_RESET,
    INVALID_VALUE,
    TARGET_AMBIGUOUS,
}

@Serializable
enum class MetricId(val defaultUnit: String) {
    FPS("fps"),
    FRAME_TIME("ms"),
    CPU_USAGE("%"),
    CPU_FREQUENCY("MHz"),
    CPU_TEMPERATURE("°C"),
    GPU_USAGE("%"),
    GPU_FREQUENCY("MHz"),
    GPU_TEMPERATURE("°C"),
    RAM_USED("GB"),
    RAM_AVAILABLE("GB"),
    BATTERY_LEVEL("%"),
    BATTERY_TEMPERATURE("°C"),
    VOLTAGE("V"),
    CURRENT("A"),
    POWER("W"),
    DOWNLOAD_RATE("Mbps"),
    UPLOAD_RATE("Mbps"),
    PING("ms"),
    THERMAL_STATUS(""),
}

@Serializable
data class MetricCapability(
    val metric: MetricId,
    val mode: AccessMode,
    val status: MetricStatus,
    val source: MetricSource? = null,
    val reason: String? = null,
) {
    val available: Boolean get() = status == MetricStatus.AVAILABLE
}

@Serializable
data class MetricReading(
    val metric: MetricId,
    val value: Double? = null,
    val status: MetricStatus,
    val source: MetricSource? = null,
    val reason: String? = null,
    val sourceIdentity: String? = null,
) {
    init {
        require(status != MetricStatus.AVAILABLE || value?.isFinite() == true) {
            "An available metric must have a finite value"
        }
    }

    companion object {
        fun available(
            metric: MetricId,
            value: Double,
            source: MetricSource,
            sourceIdentity: String? = null,
        ) = MetricReading(metric, value, MetricStatus.AVAILABLE, source, sourceIdentity = sourceIdentity)

        fun unavailable(
            metric: MetricId,
            status: MetricStatus,
            reason: String,
            source: MetricSource? = null,
        ) = MetricReading(metric, status = status, source = source, reason = reason)
    }
}

@Serializable
data class MetricSnapshot(
    val timestampMillis: Long,
    val elapsedRealtimeNanos: Long,
    val accessMode: AccessMode,
    val readings: List<MetricReading>,
) {
    operator fun get(metric: MetricId): MetricReading? = readings.firstOrNull { it.metric == metric }
}

@Serializable
data class SampleData(
    val timestamp: Long,
    val fps: Double? = null,
    val frameTime: Double? = null,
    val cpuUsage: Double? = null,
    val cpuFrequency: Double? = null,
    val cpuTemp: Double? = null,
    val gpuUsage: Double? = null,
    val gpuFrequency: Double? = null,
    val gpuTemp: Double? = null,
    val ramUsed: Long? = null,
    val ramAvailable: Long? = null,
    val batteryLevel: Double? = null,
    val batteryTemp: Double? = null,
    val voltage: Double? = null,
    val current: Double? = null,
    val powerWatts: Double? = null,
    val downloadRate: Double? = null,
    val uploadRate: Double? = null,
    val ping: Double? = null,
    val thermalState: Int? = null,
)

fun MetricSnapshot.toSampleData(): SampleData = SampleData(
    timestamp = timestampMillis,
    fps = this[MetricId.FPS]?.value,
    frameTime = this[MetricId.FRAME_TIME]?.value,
    cpuUsage = this[MetricId.CPU_USAGE]?.value,
    cpuFrequency = this[MetricId.CPU_FREQUENCY]?.value,
    cpuTemp = this[MetricId.CPU_TEMPERATURE]?.value,
    gpuUsage = this[MetricId.GPU_USAGE]?.value,
    gpuFrequency = this[MetricId.GPU_FREQUENCY]?.value,
    gpuTemp = this[MetricId.GPU_TEMPERATURE]?.value,
    ramUsed = this[MetricId.RAM_USED]?.value?.times(BYTES_PER_DECIMAL_GB)?.toLong(),
    ramAvailable = this[MetricId.RAM_AVAILABLE]?.value?.times(BYTES_PER_DECIMAL_GB)?.toLong(),
    batteryLevel = this[MetricId.BATTERY_LEVEL]?.value,
    batteryTemp = this[MetricId.BATTERY_TEMPERATURE]?.value,
    voltage = this[MetricId.VOLTAGE]?.value,
    current = this[MetricId.CURRENT]?.value,
    powerWatts = this[MetricId.POWER]?.value,
    downloadRate = this[MetricId.DOWNLOAD_RATE]?.value,
    uploadRate = this[MetricId.UPLOAD_RATE]?.value,
    ping = this[MetricId.PING]?.value,
    thermalState = this[MetricId.THERMAL_STATUS]?.value?.toInt(),
)

private const val BYTES_PER_DECIMAL_GB = 1_000_000_000.0
