package com.demirarch.pacbench.metrics

import com.demirarch.pacbench.model.AccessMode
import com.demirarch.pacbench.model.MetricCapability
import com.demirarch.pacbench.model.MetricId
import com.demirarch.pacbench.model.MetricReading
import com.demirarch.pacbench.model.MetricSource
import com.demirarch.pacbench.model.MetricStatus

internal class SystemMetricSampler(
    private val access: SystemFileAccess,
    private val mode: AccessMode,
    private val procSource: MetricSource,
    private val sysfsSource: MetricSource,
) {
    private val cpuUsage = CpuUsageCalculator()
    private val gpu = GpuSysfsProbe(access)

    suspend fun probe(metric: MetricId): MetricCapability {
        val result = when (metric) {
            MetricId.CPU_USAGE -> {
                val file = access.readText("/proc/stat")
                when {
                    !file.available -> Probe(file.status, procSource, file.reason)
                    ProcStatParser.parse(file.value.orEmpty()) == null -> Probe(
                        MetricStatus.SCHEMA_MISMATCH,
                        procSource,
                        "Aggregate CPU line is not parseable",
                    )
                    else -> Probe(MetricStatus.AVAILABLE, procSource)
                }
            }
            MetricId.CPU_FREQUENCY -> probeCpuFrequency()
            MetricId.CPU_TEMPERATURE -> probeCpuTemperature()
            MetricId.GPU_USAGE, MetricId.GPU_FREQUENCY, MetricId.GPU_TEMPERATURE -> {
                val result = gpu.discover(metric)
                if (result.reading == null) Probe(result.failure.status, sysfsSource, result.failure.reason)
                else Probe(MetricStatus.AVAILABLE, sysfsSource)
            }
            else -> Probe(MetricStatus.SOURCE_ABSENT, null, "$metric is not a procfs/sysfs metric")
        }
        return MetricCapability(metric, mode, result.status, result.source, result.reason)
    }

    suspend fun read(metric: MetricId): MetricReading = when (metric) {
        MetricId.CPU_USAGE -> readCpuUsage()
        MetricId.CPU_FREQUENCY -> readCpuFrequency()
        MetricId.CPU_TEMPERATURE -> readCpuTemperature()
        MetricId.GPU_USAGE, MetricId.GPU_FREQUENCY, MetricId.GPU_TEMPERATURE -> readGpu(metric)
        else -> MetricReading.unavailable(
            metric,
            MetricStatus.SOURCE_ABSENT,
            "$metric is not a procfs/sysfs metric",
        )
    }

    private suspend fun readCpuUsage(): MetricReading {
        val file = access.readText("/proc/stat")
        if (!file.available) return file.toReading(MetricId.CPU_USAGE, procSource)
        val ticks = ProcStatParser.parse(file.value.orEmpty()) ?: return MetricReading.unavailable(
            MetricId.CPU_USAGE,
            MetricStatus.SCHEMA_MISMATCH,
            "Aggregate CPU line is not parseable",
            procSource,
        )
        return when (val result = cpuUsage.add(ticks)) {
            is CpuUsageResult.Value -> MetricReading.available(MetricId.CPU_USAGE, result.percent, procSource, "/proc/stat")
            CpuUsageResult.FirstSample -> MetricReading.unavailable(
                MetricId.CPU_USAGE,
                MetricStatus.STALE,
                "A second /proc/stat sample is required",
                procSource,
            )
            CpuUsageResult.CounterReset -> MetricReading.unavailable(
                MetricId.CPU_USAGE,
                MetricStatus.COUNTER_RESET,
                "/proc/stat counters decreased",
                procSource,
            )
            CpuUsageResult.Invalid -> MetricReading.unavailable(
                MetricId.CPU_USAGE,
                MetricStatus.INVALID_VALUE,
                "/proc/stat delta is invalid",
                procSource,
            )
        }
    }

    private suspend fun probeCpuFrequency(): Probe {
        val result = cpuFrequencyDiscovery()
        return if (result.value.isEmpty()) {
            Probe(result.failure.status, sysfsSource, result.failure.reason)
        } else {
            Probe(MetricStatus.AVAILABLE, sysfsSource)
        }
    }

    private suspend fun readCpuFrequency(): MetricReading {
        val result = cpuFrequencyDiscovery()
        val values = result.value
        if (values.isEmpty()) return MetricReading.unavailable(
            MetricId.CPU_FREQUENCY,
            result.failure.status,
            result.failure.reason,
            sysfsSource,
        )
        return MetricReading.available(
            MetricId.CPU_FREQUENCY,
            values.map { it.second }.average(),
            sysfsSource,
            values.joinToString(",") { it.first },
        )
    }

    private suspend fun cpuFrequencyDiscovery(): Discovery<List<Pair<String, Double>>> {
        val probing = ProbingSystemFileAccess(access)
        val values = cpuFrequencyValues(probing)
        return Discovery(values, probing.failure("No readable cpufreq current-frequency files"))
    }

    private suspend fun cpuFrequencyValues(source: SystemFileAccess): List<Pair<String, Double>> {
        val cpus = source.listPaths("/sys/devices/system/cpu").value.orEmpty()
            .filter { it.substringAfterLast('/').matches(Regex("cpu\\d+")) }
        val perCpu = cpus.mapNotNull { cpu ->
            listOf(
                "$cpu/cpufreq/scaling_cur_freq",
                "$cpu/cpufreq/cpuinfo_cur_freq",
            ).firstNotNullOfOrNull { path ->
                val value = source.readText(path).value?.let(SysfsValueParser::frequencyMhzFromKilohertz)
                    ?: return@firstNotNullOfOrNull null
                path to value
            }
        }
        if (perCpu.isNotEmpty()) return perCpu

        return source.listPaths("/sys/devices/system/cpu/cpufreq").value.orEmpty()
            .filter { it.substringAfterLast('/').matches(Regex("policy\\d+")) }
            .mapNotNull { policy ->
                listOf(
                    "$policy/scaling_cur_freq",
                    "$policy/cpuinfo_cur_freq",
                ).firstNotNullOfOrNull { path ->
                    val value = source.readText(path).value?.let(SysfsValueParser::frequencyMhzFromKilohertz)
                        ?: return@firstNotNullOfOrNull null
                    path to value
                }
            }
    }

    private suspend fun probeCpuTemperature(): Probe {
        val result = cpuTemperatureDiscovery()
        return if (result.value == null) {
            Probe(result.failure.status, sysfsSource, result.failure.reason)
        } else {
            Probe(MetricStatus.AVAILABLE, sysfsSource)
        }
    }

    private suspend fun readCpuTemperature(): MetricReading {
        val result = cpuTemperatureDiscovery()
        val value = result.value ?: return MetricReading.unavailable(
            MetricId.CPU_TEMPERATURE,
            result.failure.status,
            result.failure.reason,
            sysfsSource,
        )
        return MetricReading.available(MetricId.CPU_TEMPERATURE, value.second, sysfsSource, value.first)
    }

    private suspend fun cpuTemperatureDiscovery(): Discovery<Pair<String, Double>?> {
        val probing = ProbingSystemFileAccess(access)
        val value = cpuTemperatureValue(probing)
        return Discovery(value, probing.failure("No readable CPU thermal zone"))
    }

    private suspend fun cpuTemperatureValue(source: SystemFileAccess): Pair<String, Double>? {
        val zones = source.listPaths("/sys/class/thermal").value.orEmpty()
            .filter { it.substringAfterLast('/').startsWith("thermal_zone") }
        for (zone in zones) {
            val type = source.readText("$zone/type").value?.trim()?.lowercase() ?: continue
            if (CPU_THERMAL_IDENTIFIERS.none(type::contains)) continue
            val path = "$zone/temp"
            val value = source.readText(path).value
                ?.let(SysfsValueParser::temperatureCelsiusFromMillidegrees)
                ?: continue
            return "$path ($type)" to value
        }
        return null
    }

    private suspend fun readGpu(metric: MetricId): MetricReading {
        val result = gpu.discover(metric)
        val value = result.reading ?: return MetricReading.unavailable(
            metric,
            result.failure.status,
            result.failure.reason,
            sysfsSource,
        )
        return MetricReading.available(metric, value.value, sysfsSource, "${value.adapter}:${value.path}")
    }

    private fun FileAccessResult<String>.toReading(metric: MetricId, source: MetricSource): MetricReading =
        MetricReading.unavailable(metric, status, reason ?: "System file unavailable", source)

    private data class Probe(
        val status: MetricStatus,
        val source: MetricSource?,
        val reason: String? = null,
    )

    private data class Discovery<T>(val value: T, val failure: AccessFailure)

    private companion object {
        val CPU_THERMAL_IDENTIFIERS = listOf("cpu", "soc", "ap", "cluster", "little", "big", "prime")
    }
}
