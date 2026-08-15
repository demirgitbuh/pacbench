package com.demirarch.pacbench.model

import kotlin.math.ceil
import kotlin.math.sqrt

data class FrametimeStatistics(
    val averageMs: Double,
    val medianMs: Double,
    val percentile95Ms: Double,
    val percentile99Ms: Double,
    val standardDeviationMs: Double,
    val jankCount: Int,
    val spikeCount: Int,
)

data class SessionSummary(
    val durationMillis: Long,
    val averageFps: Double?,
    val medianFps: Double?,
    val minFps: Double?,
    val maxFps: Double?,
    val onePercentLow: Double?,
    val pointOnePercentLow: Double?,
    val fpsStability: Double?,
    val averageCpu: Double?,
    val peakCpu: Double?,
    val averageGpu: Double?,
    val peakGpu: Double?,
    val maxCpuTemp: Double?,
    val maxGpuTemp: Double?,
    val maxBatteryTemp: Double?,
    val averagePower: Double?,
    val peakPower: Double?,
    val batteryConsumed: Double?,
    val thermalEventCount: Int,
    val frametime: FrametimeStatistics?,
)

object MetricCalculations {
    fun average(values: List<Double>): Double? = values.takeIf { it.isNotEmpty() }?.average()

    fun median(values: List<Double>): Double? = percentile(values, 50.0)

    fun percentile(values: List<Double>, percentile: Double): Double? {
        if (values.isEmpty()) return null
        require(percentile in 0.0..100.0)
        val sorted = values.sorted()
        if (sorted.size == 1) return sorted.first()
        val rank = (percentile / 100.0) * (sorted.lastIndex)
        val lower = rank.toInt()
        val upper = ceil(rank).toInt()
        val fraction = rank - lower
        return sorted[lower] + (sorted[upper] - sorted[lower]) * fraction
    }

    fun lowAverage(fpsValues: List<Double>, fraction: Double): Double? {
        val valid = fpsValues.filter { it.isFinite() && it >= 0.0 }.sorted()
        if (valid.isEmpty()) return null
        require(fraction > 0.0 && fraction <= 1.0)
        val count = ceil(valid.size * fraction).toInt().coerceAtLeast(1)
        return valid.take(count).average()
    }

    fun onePercentLow(fpsValues: List<Double>): Double? = lowAverage(fpsValues, 0.01)

    fun pointOnePercentLow(fpsValues: List<Double>): Double? = lowAverage(fpsValues, 0.001)

    fun batteryPowerWatts(voltageMillivolts: Int, currentMicroamps: Int): Double =
        voltageMillivolts.toDouble() * currentMicroamps.toDouble() / 1_000_000_000.0

    fun dischargePowerWatts(voltageMillivolts: Int, currentMicroamps: Int): Double =
        (-batteryPowerWatts(voltageMillivolts, currentMicroamps)).coerceAtLeast(0.0)

    fun frametimeStatistics(values: List<Double>, displayPeriodMs: Double = 16.6667): FrametimeStatistics? {
        val valid = values.filter { it.isFinite() && it >= 0.0 }
        if (valid.isEmpty()) return null
        val average = valid.average()
        val variance = valid.sumOf { (it - average) * (it - average) } / valid.size
        return FrametimeStatistics(
            averageMs = average,
            medianMs = median(valid)!!,
            percentile95Ms = percentile(valid, 95.0)!!,
            percentile99Ms = percentile(valid, 99.0)!!,
            standardDeviationMs = sqrt(variance),
            jankCount = valid.count { it >= displayPeriodMs * 1.5 },
            spikeCount = valid.count { it >= displayPeriodMs * 2.0 },
        )
    }

    fun sessionSummary(samples: List<SampleData>): SessionSummary {
        val sorted = samples.sortedBy { it.timestamp }
        val fps = sorted.mapNotNull { it.fps }
        val frameTimes = sorted.mapNotNull { it.frameTime }
        val averageFps = average(fps)
        val fpsDeviation = averageFps?.let { mean ->
            sqrt(fps.sumOf { (it - mean) * (it - mean) } / fps.size.coerceAtLeast(1))
        }
        val thermalStates = sorted.mapNotNull { it.thermalState }
        return SessionSummary(
            durationMillis = (sorted.lastOrNull()?.timestamp ?: 0L) - (sorted.firstOrNull()?.timestamp ?: 0L),
            averageFps = averageFps,
            medianFps = median(fps),
            minFps = fps.minOrNull(),
            maxFps = fps.maxOrNull(),
            onePercentLow = onePercentLow(fps),
            pointOnePercentLow = pointOnePercentLow(fps),
            fpsStability = averageFps?.takeIf { it > 0 }?.let { (100.0 - (fpsDeviation!! / it * 100.0)).coerceIn(0.0, 100.0) },
            averageCpu = average(sorted.mapNotNull { it.cpuUsage }),
            peakCpu = sorted.mapNotNull { it.cpuUsage }.maxOrNull(),
            averageGpu = average(sorted.mapNotNull { it.gpuUsage }),
            peakGpu = sorted.mapNotNull { it.gpuUsage }.maxOrNull(),
            maxCpuTemp = sorted.mapNotNull { it.cpuTemp }.maxOrNull(),
            maxGpuTemp = sorted.mapNotNull { it.gpuTemp }.maxOrNull(),
            maxBatteryTemp = sorted.mapNotNull { it.batteryTemp }.maxOrNull(),
            averagePower = average(sorted.mapNotNull { it.powerWatts }),
            peakPower = sorted.mapNotNull { it.powerWatts }.maxOrNull(),
            batteryConsumed = sorted.firstNotNullOfOrNull { it.batteryLevel }?.let { start ->
                sorted.asReversed().firstNotNullOfOrNull { it.batteryLevel }?.let { end -> (start - end).coerceAtLeast(0.0) }
            },
            thermalEventCount = thermalStates.zipWithNext().count { (previous, current) -> current >= 2 && previous < 2 },
            frametime = frametimeStatistics(frameTimes),
        )
    }
}
