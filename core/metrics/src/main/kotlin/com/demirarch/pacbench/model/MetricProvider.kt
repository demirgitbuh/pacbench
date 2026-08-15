package com.demirarch.pacbench.model

/** A runtime-probed source of real metric readings. */
interface MetricProvider {
    val accessMode: AccessMode

    /** Higher-priority providers are tried first when multiple sources are available. */
    val priority: Int get() = 0

    suspend fun probe(metric: MetricId): MetricCapability

    suspend fun read(metric: MetricId): MetricReading

    suspend fun capabilities(
        metrics: Set<MetricId> = MetricId.entries.toSet(),
    ): List<MetricCapability> = metrics.map { probe(it) }
}
