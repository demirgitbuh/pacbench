package com.demirarch.pacbench.metrics

import com.demirarch.pacbench.model.MetricId
import kotlinx.coroutines.CancellationException

data class GpuSysfsReading(
    val value: Double,
    val path: String,
    val adapter: String,
)

internal data class GpuSysfsDiscovery(
    val reading: GpuSysfsReading?,
    val failure: AccessFailure,
)

interface GpuSysfsAdapter {
    val name: String
    suspend fun read(metric: MetricId, access: SystemFileAccess): GpuSysfsReading?
}

class QualcommKgslSysfsAdapter : GpuSysfsAdapter {
    override val name: String = "qualcomm-kgsl"

    override suspend fun read(metric: MetricId, access: SystemFileAccess): GpuSysfsReading? {
        val paths = when (metric) {
            MetricId.GPU_USAGE -> listOf("/sys/class/kgsl/kgsl-3d0/gpubusy")
            MetricId.GPU_FREQUENCY -> listOf(
                "/sys/class/kgsl/kgsl-3d0/gpuclk",
                "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq",
            )
            else -> return null
        }
        return paths.firstNotNullOfOrNull { path ->
            val text = access.readText(path).value ?: return@firstNotNullOfOrNull null
            val value = when (metric) {
                MetricId.GPU_USAGE -> SysfsValueParser.ratioPercentage(text)
                MetricId.GPU_FREQUENCY -> SysfsValueParser.frequencyMhzFromHertz(text)
                else -> null
            } ?: return@firstNotNullOfOrNull null
            GpuSysfsReading(value, path, name)
        }
    }
}

class MaliDevfreqSysfsAdapter : GpuSysfsAdapter {
    override val name: String = "mali-devfreq"

    override suspend fun read(metric: MetricId, access: SystemFileAccess): GpuSysfsReading? {
        if (metric != MetricId.GPU_USAGE && metric != MetricId.GPU_FREQUENCY) return null
        val directories = access.listPaths(DEVFREQ_ROOT).value.orEmpty()
        for (directory in directories) {
            val identity = buildString {
                append(directory.substringAfterLast('/').lowercase())
                access.readText("$directory/name").value?.let { append(' ').append(it.lowercase()) }
            }
            if (MALI_IDENTIFIERS.none(identity::contains)) continue
            val candidates = when (metric) {
                MetricId.GPU_USAGE -> listOf("$directory/load", "$directory/device/load", "$directory/utilization")
                MetricId.GPU_FREQUENCY -> listOf("$directory/cur_freq")
                else -> emptyList()
            }
            for (path in candidates) {
                val text = access.readText(path).value ?: continue
                val value = when (metric) {
                    MetricId.GPU_USAGE -> SysfsValueParser.percentage(text)
                    MetricId.GPU_FREQUENCY -> SysfsValueParser.frequencyMhzFromHertz(text)
                    else -> null
                } ?: continue
                return GpuSysfsReading(value, path, name)
            }
        }
        return null
    }

    private companion object {
        const val DEVFREQ_ROOT = "/sys/class/devfreq"
        val MALI_IDENTIFIERS = listOf("mali", "panfrost", "panthor")
    }
}

class XclipseVendorSysfsAdapter : GpuSysfsAdapter {
    override val name: String = "xclipse-vendor"

    override suspend fun read(metric: MetricId, access: SystemFileAccess): GpuSysfsReading? {
        if (metric != MetricId.GPU_USAGE && metric != MetricId.GPU_FREQUENCY) return null

        val fixedPaths = when (metric) {
            MetricId.GPU_USAGE -> listOf("/sys/kernel/gpu/gpu_busy")
            MetricId.GPU_FREQUENCY -> listOf("/sys/kernel/gpu/gpu_clock")
            else -> emptyList()
        }
        readFirst(metric, fixedPaths, access)?.let { return it }

        val devfreq = access.listPaths("/sys/class/devfreq").value.orEmpty()
        for (directory in devfreq) {
            val identity = buildString {
                append(directory.substringAfterLast('/').lowercase())
                access.readText("$directory/name").value?.let { append(' ').append(it.lowercase()) }
            }
            if (VENDOR_IDENTIFIERS.none(identity::contains)) continue
            val paths = if (metric == MetricId.GPU_USAGE) {
                listOf("$directory/load", "$directory/utilization", "$directory/device/gpu_busy_percent")
            } else {
                listOf("$directory/cur_freq")
            }
            readFirst(metric, paths, access)?.let { return it }
        }

        if (metric == MetricId.GPU_USAGE) {
            val cards = access.listPaths("/sys/class/drm").value.orEmpty()
                .filter { it.substringAfterLast('/').matches(Regex("card\\d+")) }
            for (card in cards) {
                val uevent = access.readText("$card/device/uevent").value?.lowercase().orEmpty()
                if (VENDOR_IDENTIFIERS.none(uevent::contains)) continue
                readFirst(metric, listOf("$card/device/gpu_busy_percent"), access)?.let { return it }
            }
        }
        return null
    }

    private suspend fun readFirst(
        metric: MetricId,
        paths: List<String>,
        access: SystemFileAccess,
    ): GpuSysfsReading? = paths.firstNotNullOfOrNull { path ->
        val text = access.readText(path).value ?: return@firstNotNullOfOrNull null
        val value = (if (metric == MetricId.GPU_USAGE) {
            SysfsValueParser.percentage(text)
        } else if (path.endsWith("/cur_freq")) {
            SysfsValueParser.frequencyMhzFromHertz(text)
        } else {
            SysfsValueParser.frequencyMhz(text)
        }) ?: return@firstNotNullOfOrNull null
        GpuSysfsReading(value, path, name)
    }

    private companion object {
        val VENDOR_IDENTIFIERS = listOf("xclipse", "amdgpu", "exynos-gpu")
    }
}

class GpuSysfsProbe(
    private val access: SystemFileAccess,
    private val adapters: List<GpuSysfsAdapter> = listOf(
        QualcommKgslSysfsAdapter(),
        XclipseVendorSysfsAdapter(),
        MaliDevfreqSysfsAdapter(),
    ),
) {
    suspend fun read(metric: MetricId): GpuSysfsReading? = discover(metric).reading

    internal suspend fun discover(metric: MetricId): GpuSysfsDiscovery {
        val probing = ProbingSystemFileAccess(access)
        val reading = read(metric, probing)
        return GpuSysfsDiscovery(reading, probing.failure("No readable GPU sysfs source"))
    }

    private suspend fun read(metric: MetricId, source: SystemFileAccess): GpuSysfsReading? {
        if (metric == MetricId.GPU_TEMPERATURE) return readGpuTemperature(source)
        for (adapter in adapters) {
            val reading = try {
                adapter.read(metric, source)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            if (reading != null) return reading
        }
        return null
    }

    private suspend fun readGpuTemperature(source: SystemFileAccess): GpuSysfsReading? {
        val zones = source.listPaths("/sys/class/thermal").value.orEmpty()
            .filter { it.substringAfterLast('/').startsWith("thermal_zone") }
        for (zone in zones) {
            val type = source.readText("$zone/type").value?.trim()?.lowercase() ?: continue
            if (GPU_THERMAL_IDENTIFIERS.none(type::contains)) continue
            val path = "$zone/temp"
            val value = source.readText(path).value
                ?.let(SysfsValueParser::temperatureCelsiusFromMillidegrees)
                ?: continue
            return GpuSysfsReading(value, path, "thermal:$type")
        }
        return null
    }

    private companion object {
        val GPU_THERMAL_IDENTIFIERS = listOf("gpu", "kgsl", "mali", "xclipse", "g3d")
    }
}
