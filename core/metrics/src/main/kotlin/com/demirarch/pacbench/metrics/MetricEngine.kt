package com.demirarch.pacbench.metrics

import android.os.SystemClock
import com.demirarch.pacbench.model.AccessMode
import com.demirarch.pacbench.model.MetricCapability
import com.demirarch.pacbench.model.MetricId
import com.demirarch.pacbench.model.MetricProvider
import com.demirarch.pacbench.model.MetricReading
import com.demirarch.pacbench.model.MetricSnapshot
import com.demirarch.pacbench.model.MetricSource
import com.demirarch.pacbench.model.MetricStatus
import kotlinx.coroutines.CancellationException

class MetricEngine(
    private val providers: List<MetricProvider>,
    private val wallClockMillis: () -> Long = System::currentTimeMillis,
    private val elapsedRealtimeNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
) {
    init {
        require(providers.isNotEmpty()) { "At least one metric provider is required" }
    }

    suspend fun capabilities(
        metrics: Set<MetricId> = MetricId.entries.toSet(),
    ): Map<MetricId, List<MetricCapability>> = metrics.associateWith { metric ->
        providers.map { provider -> provider to safeProbe(provider, metric) }
            .sortedWith(providerCapabilityComparator(metric))
            .map { it.second }
    }

    suspend fun sample(
        metrics: Set<MetricId> = MetricId.entries.toSet(),
    ): MetricSnapshot {
        val selectedModes = mutableListOf<AccessMode>()
        val readings = metrics.map { metric ->
            val result = readBest(metric)
            result.provider?.let { selectedModes += it.accessMode }
            result.reading
        }
        return MetricSnapshot(
            timestampMillis = wallClockMillis(),
            elapsedRealtimeNanos = elapsedRealtimeNanos(),
            accessMode = selectedModes.maxByOrNull(::modeWeight) ?: AccessMode.NORMAL,
            readings = readings,
        )
    }

    private suspend fun readBest(metric: MetricId): Selection {
        val probed = providers.map { provider -> provider to safeProbe(provider, metric) }
            .sortedWith(providerCapabilityComparator(metric))

        var mostUsefulFailure: MetricReading? = null
        for ((provider, capability) in probed) {
            if (!capability.available) {
                if (mostUsefulFailure == null) {
                    mostUsefulFailure = MetricReading.unavailable(
                        metric = metric,
                        status = capability.status,
                        reason = capability.reason ?: "Capability probe failed",
                        source = capability.source,
                    )
                }
                continue
            }

            val reading = try {
                provider.read(metric)
            } catch (error: CancellationException) {
                throw error
            } catch (error: SecurityException) {
                MetricReading.unavailable(
                    metric,
                    MetricStatus.PERMISSION_DENIED,
                    error.message ?: "Provider permission denied",
                    capability.source,
                )
            } catch (error: Exception) {
                MetricReading.unavailable(
                    metric,
                    MetricStatus.SOURCE_ABSENT,
                    error.message ?: "Provider read failed",
                    capability.source,
                )
            }
            if (reading.metric != metric) {
                mostUsefulFailure = MetricReading.unavailable(
                    metric,
                    MetricStatus.SCHEMA_MISMATCH,
                    "Provider returned ${reading.metric} for $metric",
                    capability.source,
                )
            } else if (reading.status == MetricStatus.AVAILABLE) {
                return Selection(reading, provider)
            } else if (mostUsefulFailure == null || mostUsefulFailure.status == MetricStatus.STALE) {
                mostUsefulFailure = reading
            }
        }

        return Selection(
            mostUsefulFailure ?: MetricReading.unavailable(
                metric,
                MetricStatus.SOURCE_ABSENT,
                "No provider supports $metric",
            ),
            null,
        )
    }

    private suspend fun safeProbe(provider: MetricProvider, metric: MetricId): MetricCapability = try {
        provider.probe(metric).let { capability ->
            if (capability.metric == metric && capability.mode == provider.accessMode) {
                capability
            } else {
                MetricCapability(
                    metric,
                    provider.accessMode,
                    MetricStatus.SCHEMA_MISMATCH,
                    capability.source,
                    "Provider returned an invalid capability identity",
                )
            }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: SecurityException) {
        MetricCapability(
            metric,
            provider.accessMode,
            MetricStatus.PERMISSION_DENIED,
            reason = error.message ?: "Capability probe permission denied",
        )
    } catch (error: Exception) {
        MetricCapability(
            metric,
            provider.accessMode,
            MetricStatus.SOURCE_ABSENT,
            reason = error.message ?: "Capability probe failed",
        )
    }

    private fun providerCapabilityComparator(metric: MetricId) =
        compareByDescending<Pair<MetricProvider, MetricCapability>> { it.second.available }
            .thenByDescending { it.first.priority }
            .thenByDescending { sourceWeight(metric, it.second.source) }
            .thenByDescending { modeWeight(it.first.accessMode) }

    private fun sourceWeight(metric: MetricId, source: MetricSource?): Int = when (source) {
        MetricSource.SURFACE_FLINGER -> if (metric == MetricId.FPS || metric == MetricId.FRAME_TIME) 100 else 40
        MetricSource.GFXINFO -> if (metric == MetricId.FPS || metric == MetricId.FRAME_TIME) 90 else 40
        MetricSource.ANDROID_API -> 80
        MetricSource.PROCFS, MetricSource.SYSFS -> 70
        MetricSource.SHIZUKU_SHELL -> 60
        MetricSource.ROOT_SHELL -> 50
        null -> 0
    }

    private fun modeWeight(mode: AccessMode): Int = when (mode) {
        AccessMode.NORMAL -> 0
        AccessMode.SHIZUKU -> 1
        AccessMode.ROOT -> 2
    }

    private data class Selection(val reading: MetricReading, val provider: MetricProvider?)
}
