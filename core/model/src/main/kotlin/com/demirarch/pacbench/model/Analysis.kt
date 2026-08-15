package com.demirarch.pacbench.model

import kotlinx.serialization.Serializable

@Serializable
enum class AnalysisType {
    THERMAL_THROTTLING,
    CPU_BOTTLENECK,
    GPU_BOTTLENECK,
    MEMORY_PRESSURE,
    SUSTAINED_FPS_DEGRADATION,
    FRAME_PACING,
    FPS_DROP,
    HIGH_TEMPERATURE,
    HIGH_POWER,
    NETWORK_LATENCY_SPIKE,
}

@Serializable
enum class Confidence { LOW, MEDIUM, HIGH }

@Serializable
data class AnalysisFinding(
    val type: AnalysisType,
    val startTimestamp: Long,
    val endTimestamp: Long,
    val metrics: List<MetricId>,
    val reason: String,
    val confidence: Confidence,
)

object RuleBasedAnalyzer {
    fun analyze(samples: List<SampleData>): List<AnalysisFinding> {
        if (samples.size < 3) return emptyList()
        val sorted = samples.sortedBy { it.timestamp }
        val findings = mutableListOf<AnalysisFinding>()
        windows(sorted, 5).forEach { window ->
            val start = window.first().timestamp
            val end = window.last().timestamp
            val thermal = window.mapNotNull { it.thermalState }
            if (thermal.size == window.size && thermal.count { it >= 3 } >= 3) {
                findings += AnalysisFinding(AnalysisType.THERMAL_THROTTLING, start, end, listOf(MetricId.THERMAL_STATUS), "Thermal status remained severe or higher.", Confidence.HIGH)
            }
            val cpu = window.mapNotNull { it.cpuUsage }
            val gpu = window.mapNotNull { it.gpuUsage }
            val fps = window.mapNotNull { it.fps }
            if (cpu.size == window.size && gpu.size == window.size && fps.size == window.size && cpu.average() >= 85 && gpu.average() < 75) {
                findings += AnalysisFinding(AnalysisType.CPU_BOTTLENECK, start, end, listOf(MetricId.CPU_USAGE, MetricId.GPU_USAGE, MetricId.FPS), "CPU load was high while GPU load had headroom.", Confidence.MEDIUM)
            }
            if (cpu.size == window.size && gpu.size == window.size && fps.size == window.size && gpu.average() >= 95 && cpu.average() < 90) {
                findings += AnalysisFinding(AnalysisType.GPU_BOTTLENECK, start, end, listOf(MetricId.GPU_USAGE, MetricId.CPU_USAGE, MetricId.FPS), "GPU load was saturated while CPU load had headroom.", Confidence.MEDIUM)
            }
            val availableRam = window.mapNotNull { it.ramAvailable }
            if (availableRam.size == window.size && availableRam.average() < 512.0 * 1024 * 1024) {
                findings += AnalysisFinding(AnalysisType.MEMORY_PRESSURE, start, end, listOf(MetricId.RAM_AVAILABLE), "Available RAM stayed below 512 MiB.", Confidence.HIGH)
            }
            val frameTimes = window.mapNotNull { it.frameTime }
            if (frameTimes.size == window.size && MetricCalculations.frametimeStatistics(frameTimes)?.standardDeviationMs?.let { it > 8 } == true) {
                findings += AnalysisFinding(AnalysisType.FRAME_PACING, start, end, listOf(MetricId.FRAME_TIME), "Frametime standard deviation exceeded 8 ms.", Confidence.HIGH)
            }
            val temperatures = window.flatMap { listOfNotNull(it.cpuTemp, it.gpuTemp, it.batteryTemp) }
            if (temperatures.isNotEmpty() && temperatures.max() >= 80) {
                findings += AnalysisFinding(AnalysisType.HIGH_TEMPERATURE, start, end, listOf(MetricId.CPU_TEMPERATURE, MetricId.GPU_TEMPERATURE, MetricId.BATTERY_TEMPERATURE), "A reported temperature reached 80°C.", Confidence.HIGH)
            }
            val ping = window.mapNotNull { it.ping }
            if (ping.size == window.size && ping.max() >= 150 && ping.max() >= ping.average() * 1.5) {
                findings += AnalysisFinding(AnalysisType.NETWORK_LATENCY_SPIKE, start, end, listOf(MetricId.PING), "Latency reached 150 ms and exceeded the local mean by 50%.", Confidence.HIGH)
            }
        }
        val validFps = sorted.mapNotNull { it.fps }
        if (validFps.size >= 10) {
            val segment = validFps.size / 3
            val first = validFps.take(segment).average()
            val last = validFps.takeLast(segment).average()
            if (first > 0 && last < first * 0.85) {
                findings += AnalysisFinding(AnalysisType.SUSTAINED_FPS_DEGRADATION, sorted[sorted.size - segment].timestamp, sorted.last().timestamp, listOf(MetricId.FPS), "Final FPS average was at least 15% below the opening segment.", Confidence.HIGH)
            }
        }
        return findings.distinctBy { it.type to it.startTimestamp }
    }

    private fun windows(samples: List<SampleData>, size: Int): List<List<SampleData>> =
        if (samples.size < size) emptyList() else samples.windowed(size = size, step = size, partialWindows = false)
}
