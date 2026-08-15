package com.demirarch.pacbench.metrics

import android.os.SystemClock
import com.demirarch.pacbench.model.MetricId
import com.demirarch.pacbench.model.MetricReading
import com.demirarch.pacbench.model.MetricSource
import com.demirarch.pacbench.model.MetricStatus

data class FrameBackendProbe(
    val status: MetricStatus,
    val source: MetricSource,
    val reason: String? = null,
)

interface FrameMetricBackend {
    val source: MetricSource
    suspend fun probe(): FrameBackendProbe
    suspend fun read(metric: MetricId): MetricReading
}

class SurfaceFlingerLatencyBackend(
    private val executor: CommandExecutor,
    private val targetPackage: String? = null,
    private val explicitLayer: String? = null,
    private val elapsedRealtimeNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
) : FrameMetricBackend {
    override val source: MetricSource = MetricSource.SURFACE_FLINGER

    private var resolvedLayer: String? = null
    private var cachedAtNanos = Long.MIN_VALUE
    private var cached: BackendSample? = null
    private var lastObservedFrameTimestamp = Long.MIN_VALUE

    override suspend fun probe(): FrameBackendProbe {
        val layer = resolveLayer()
        if (layer.status != MetricStatus.AVAILABLE || layer.value == null) {
            return FrameBackendProbe(layer.status, source, layer.reason)
        }
        val command = executor.execute(SafeCommand.SurfaceFlingerLatency(layer.value))
        if (!command.successful) return command.toProbe(source)
        return if (SurfaceFlingerLatencyParser.parse(command.stdout) != null) {
            FrameBackendProbe(MetricStatus.AVAILABLE, source)
        } else {
            FrameBackendProbe(MetricStatus.SCHEMA_MISMATCH, source, "SurfaceFlinger latency schema was not recognized")
        }
    }

    override suspend fun read(metric: MetricId): MetricReading {
        require(metric == MetricId.FPS || metric == MetricId.FRAME_TIME) { "$metric is not a frame metric" }
        val sample = sample()
        val statistics = sample.statistics
        if (sample.status != MetricStatus.AVAILABLE || statistics == null) {
            return MetricReading.unavailable(metric, sample.status, sample.reason ?: "No SurfaceFlinger frame data", source)
        }
        val value = if (metric == MetricId.FPS) statistics.fps else statistics.averageFrameTimeMillis
        return MetricReading.available(metric, value, source, sample.identity)
    }

    @Synchronized
    private fun freshCache(now: Long): BackendSample? = cached?.takeIf {
        now >= cachedAtNanos && now - cachedAtNanos <= CACHE_NANOS
    }

    private suspend fun sample(): BackendSample {
        val now = elapsedRealtimeNanos()
        freshCache(now)?.let { return it }
        val layer = resolveLayer()
        if (layer.status != MetricStatus.AVAILABLE || layer.value == null) {
            return cache(now, BackendSample(layer.status, reason = layer.reason))
        }
        val command = executor.execute(SafeCommand.SurfaceFlingerLatency(layer.value))
        if (!command.successful) {
            resolvedLayer = null
            val probe = command.toProbe(source)
            return cache(now, BackendSample(probe.status, reason = probe.reason))
        }
        val parsed = SurfaceFlingerLatencyParser.parse(command.stdout)
            ?: return cache(now, BackendSample(MetricStatus.SCHEMA_MISMATCH, reason = "Invalid SurfaceFlinger latency output"))
        // The first line is refresh period metadata, never an FPS reading.
        val statistics = parsed.statistics()
            ?: return cache(now, BackendSample(MetricStatus.STALE, reason = "Fewer than two presented frames"))
        if (statistics.lastTimestampNanos == lastObservedFrameTimestamp) {
            return cache(now, BackendSample(MetricStatus.STALE, reason = "SurfaceFlinger frame timestamps did not advance"))
        }
        lastObservedFrameTimestamp = statistics.lastTimestampNanos
        return cache(
            now,
            BackendSample(
                MetricStatus.AVAILABLE,
                statistics,
                identity = "layer:${layer.value}",
            ),
        )
    }

    private suspend fun resolveLayer(): LayerResolution {
        explicitLayer?.let {
            return try {
                CommandPolicy.argv(SafeCommand.SurfaceFlingerLatency(it))
                LayerResolution(MetricStatus.AVAILABLE, it)
            } catch (error: IllegalArgumentException) {
                LayerResolution(MetricStatus.INVALID_VALUE, reason = error.message)
            }
        }
        resolvedLayer?.let { return LayerResolution(MetricStatus.AVAILABLE, it) }
        val packageName = targetPackage?.takeIf(String::isNotBlank)
            ?: return LayerResolution(MetricStatus.SOURCE_ABSENT, reason = "No target package or layer was supplied")
        val result = executor.execute(SafeCommand.SurfaceFlingerLayers)
        if (!result.successful) {
            val probe = result.toProbe(source)
            return LayerResolution(probe.status, reason = probe.reason)
        }
        val candidates = result.stdout.lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && packageName in it }
            .distinct()
            .toList()
        val surfaceViews = candidates.filter { "SurfaceView" in it }
        val selected = when {
            candidates.size == 1 -> candidates.single()
            surfaceViews.size == 1 -> surfaceViews.single()
            candidates.isEmpty() -> return LayerResolution(
                MetricStatus.SOURCE_ABSENT,
                reason = "No SurfaceFlinger layer matched $packageName",
            )
            else -> return LayerResolution(
                MetricStatus.TARGET_AMBIGUOUS,
                reason = "${candidates.size} SurfaceFlinger layers matched $packageName; supply an explicit layer",
            )
        }
        resolvedLayer = selected
        return LayerResolution(MetricStatus.AVAILABLE, selected)
    }

    @Synchronized
    private fun cache(now: Long, value: BackendSample): BackendSample {
        cachedAtNanos = now
        cached = value
        return value
    }

    private data class LayerResolution(
        val status: MetricStatus,
        val value: String? = null,
        val reason: String? = null,
    )

    private data class BackendSample(
        val status: MetricStatus,
        val statistics: FrameStatistics? = null,
        val reason: String? = null,
        val identity: String? = null,
    )

    private companion object {
        const val CACHE_NANOS = 100_000_000L
    }
}

class GfxInfoFramestatsBackend(
    private val executor: CommandExecutor,
    private val targetPackage: String?,
    private val elapsedRealtimeNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
) : FrameMetricBackend {
    override val source: MetricSource = MetricSource.GFXINFO

    private var cachedAtNanos = Long.MIN_VALUE
    private var cached: BackendSample? = null
    private var lastObservedFrameTimestamp = Long.MIN_VALUE

    override suspend fun probe(): FrameBackendProbe {
        val packageName = validPackage() ?: return FrameBackendProbe(
            MetricStatus.SOURCE_ABSENT,
            source,
            "No valid target package was supplied",
        )
        val command = executor.execute(SafeCommand.GfxInfoFramestats(packageName))
        if (!command.successful) return command.toProbe(source)
        return if (GfxInfoFramestatsParser.hasSchema(command.stdout)) {
            FrameBackendProbe(MetricStatus.AVAILABLE, source)
        } else {
            FrameBackendProbe(MetricStatus.SCHEMA_MISMATCH, source, "gfxinfo framestats schema was not recognized")
        }
    }

    override suspend fun read(metric: MetricId): MetricReading {
        require(metric == MetricId.FPS || metric == MetricId.FRAME_TIME) { "$metric is not a frame metric" }
        val sample = sample()
        val statistics = sample.statistics
        if (sample.status != MetricStatus.AVAILABLE || statistics == null) {
            return MetricReading.unavailable(metric, sample.status, sample.reason ?: "No gfxinfo frame data", source)
        }
        val value = if (metric == MetricId.FPS) statistics.fps else statistics.averageFrameTimeMillis
        return MetricReading.available(metric, value, source, "package:${validPackage()}")
    }

    private suspend fun sample(): BackendSample {
        val now = elapsedRealtimeNanos()
        synchronized(this) {
            cached?.takeIf { now >= cachedAtNanos && now - cachedAtNanos <= CACHE_NANOS }?.let { return it }
        }
        val packageName = validPackage()
            ?: return cache(now, BackendSample(MetricStatus.SOURCE_ABSENT, reason = "No valid target package was supplied"))
        val command = executor.execute(SafeCommand.GfxInfoFramestats(packageName))
        if (!command.successful) {
            val probe = command.toProbe(source)
            return cache(now, BackendSample(probe.status, reason = probe.reason))
        }
        val parsed = GfxInfoFramestatsParser.parse(command.stdout)
            ?: return cache(now, BackendSample(MetricStatus.STALE, reason = "gfxinfo contained no completed frames"))
        val statistics = parsed.statistics()
            ?: return cache(now, BackendSample(MetricStatus.STALE, reason = "gfxinfo contained fewer than two timed frames"))
        if (statistics.lastTimestampNanos == lastObservedFrameTimestamp) {
            return cache(now, BackendSample(MetricStatus.STALE, reason = "gfxinfo frame timestamps did not advance"))
        }
        lastObservedFrameTimestamp = statistics.lastTimestampNanos
        return cache(now, BackendSample(MetricStatus.AVAILABLE, statistics))
    }

    private fun validPackage(): String? {
        val value = targetPackage?.takeIf(String::isNotBlank) ?: return null
        return runCatching {
            CommandPolicy.argv(SafeCommand.GfxInfoFramestats(value))
            value
        }.getOrNull()
    }

    @Synchronized
    private fun cache(now: Long, value: BackendSample): BackendSample {
        cachedAtNanos = now
        cached = value
        return value
    }

    private data class BackendSample(
        val status: MetricStatus,
        val statistics: FrameStatistics? = null,
        val reason: String? = null,
    )

    private companion object {
        const val CACHE_NANOS = 100_000_000L
    }
}

internal class BestFrameMetricBackend(
    private val surfaceFlinger: SurfaceFlingerLatencyBackend,
    private val gfxInfo: GfxInfoFramestatsBackend,
) {
    suspend fun probe(): FrameBackendProbe {
        val surfaceProbe = surfaceFlinger.probe()
        if (surfaceProbe.status == MetricStatus.AVAILABLE) return surfaceProbe
        val gfxProbe = gfxInfo.probe()
        return when {
            gfxProbe.status == MetricStatus.AVAILABLE -> gfxProbe
            failureWeight(gfxProbe.status) > failureWeight(surfaceProbe.status) -> gfxProbe
            else -> surfaceProbe
        }
    }

    suspend fun read(metric: MetricId): MetricReading {
        val surfaceReading = surfaceFlinger.read(metric)
        if (surfaceReading.status == MetricStatus.AVAILABLE) return surfaceReading
        val gfxReading = gfxInfo.read(metric)
        return if (
            gfxReading.status == MetricStatus.AVAILABLE ||
            failureWeight(gfxReading.status) > failureWeight(surfaceReading.status)
        ) gfxReading else surfaceReading
    }

    private fun failureWeight(status: MetricStatus): Int = when (status) {
        MetricStatus.PERMISSION_DENIED -> 6
        MetricStatus.TARGET_AMBIGUOUS -> 5
        MetricStatus.SCHEMA_MISMATCH -> 4
        MetricStatus.INVALID_VALUE, MetricStatus.COUNTER_RESET -> 3
        MetricStatus.STALE -> 2
        MetricStatus.UNSUPPORTED_API, MetricStatus.SOURCE_ABSENT -> 1
        MetricStatus.AVAILABLE -> 0
    }
}

private fun CommandResult.toProbe(source: MetricSource): FrameBackendProbe = FrameBackendProbe(
    status = when (status) {
        CommandStatus.PERMISSION_DENIED -> MetricStatus.PERMISSION_DENIED
        CommandStatus.TIMED_OUT -> MetricStatus.STALE
        CommandStatus.OUTPUT_LIMIT -> MetricStatus.SCHEMA_MISMATCH
        CommandStatus.SUCCESS -> if (exitCode == 0) MetricStatus.AVAILABLE else MetricStatus.SOURCE_ABSENT
        CommandStatus.UNAVAILABLE, CommandStatus.FAILED -> MetricStatus.SOURCE_ABSENT
    },
    source = source,
    reason = reason ?: stderr.trim().ifBlank { null },
)
