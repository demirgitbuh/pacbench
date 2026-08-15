package com.demirarch.pacbench.metrics

import com.demirarch.pacbench.model.AccessMode
import com.demirarch.pacbench.model.MetricCapability
import com.demirarch.pacbench.model.MetricId
import com.demirarch.pacbench.model.MetricProvider
import com.demirarch.pacbench.model.MetricReading
import com.demirarch.pacbench.model.MetricSource
import com.demirarch.pacbench.model.MetricStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class MetricEngineTest {
    @Test
    fun fallsBackPerMetricWhenPreferredProviderCannotProduceCurrentReading() = runTest {
        val preferred = FakeProvider(
            accessMode = AccessMode.SHIZUKU,
            priority = 200,
            source = MetricSource.SHIZUKU_SHELL,
            reading = MetricReading.unavailable(
                MetricId.CPU_USAGE,
                MetricStatus.STALE,
                "Needs another sample",
                MetricSource.SHIZUKU_SHELL,
            ),
        )
        val fallback = FakeProvider(
            accessMode = AccessMode.ROOT,
            priority = 100,
            source = MetricSource.ROOT_SHELL,
            reading = MetricReading.available(MetricId.CPU_USAGE, 37.5, MetricSource.ROOT_SHELL),
        )
        val engine = MetricEngine(listOf(preferred, fallback), { 123 }, { 456 })

        val snapshot = engine.sample(setOf(MetricId.CPU_USAGE))

        assertEquals(37.5, snapshot[MetricId.CPU_USAGE]!!.value!!, 0.0)
        assertEquals(MetricSource.ROOT_SHELL, snapshot[MetricId.CPU_USAGE]!!.source)
        assertEquals(AccessMode.ROOT, snapshot.accessMode)
        assertEquals(123L, snapshot.timestampMillis)
        assertEquals(456L, snapshot.elapsedRealtimeNanos)
    }

    @Test
    fun capabilitiesAreOrderedByTheActualProviderPriority() = runTest {
        val lower = FakeProvider(
            accessMode = AccessMode.SHIZUKU,
            priority = 10,
            source = MetricSource.GFXINFO,
            reading = MetricReading.available(MetricId.FPS, 30.0, MetricSource.GFXINFO),
        )
        val higher = FakeProvider(
            accessMode = AccessMode.SHIZUKU,
            priority = 20,
            source = MetricSource.SURFACE_FLINGER,
            reading = MetricReading.available(MetricId.FPS, 60.0, MetricSource.SURFACE_FLINGER),
        )
        val engine = MetricEngine(listOf(lower, higher), { 0 }, { 0 })

        val capabilities = engine.capabilities(setOf(MetricId.FPS)).getValue(MetricId.FPS)

        assertEquals(listOf(MetricSource.SURFACE_FLINGER, MetricSource.GFXINFO), capabilities.map { it.source })
    }

    private class FakeProvider(
        override val accessMode: AccessMode,
        override val priority: Int,
        private val source: MetricSource,
        private val reading: MetricReading,
    ) : MetricProvider {
        override suspend fun probe(metric: MetricId) = MetricCapability(
            metric = metric,
            mode = accessMode,
            status = MetricStatus.AVAILABLE,
            source = source,
        )

        override suspend fun read(metric: MetricId): MetricReading = reading
    }
}
