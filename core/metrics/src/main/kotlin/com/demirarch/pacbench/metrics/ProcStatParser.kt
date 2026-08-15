package com.demirarch.pacbench.metrics

data class CpuTicks(val total: Long, val idle: Long)

object ProcStatParser {
    fun parse(text: String): CpuTicks? {
        val line = text.lineSequence().firstOrNull { it.startsWith("cpu ") } ?: return null
        val fields = line.trim().split(Regex("\\s+")).drop(1)
        if (fields.size < 4) return null
        val ticks = fields.map { it.toLongOrNull() ?: return null }
        if (ticks.any { it < 0 }) return null
        // guest and guest_nice are already included in user and nice respectively.
        val total = ticks.take(MAX_NON_DUPLICATED_FIELDS).fold(0L) { accumulator, value ->
            if (Long.MAX_VALUE - accumulator < value) return null
            accumulator + value
        }
        val idle = ticks[3] + (ticks.getOrNull(4) ?: 0L)
        return CpuTicks(total, idle)
    }

    private const val MAX_NON_DUPLICATED_FIELDS = 8
}

sealed interface CpuUsageResult {
    data class Value(val percent: Double) : CpuUsageResult
    data object FirstSample : CpuUsageResult
    data object CounterReset : CpuUsageResult
    data object Invalid : CpuUsageResult
}

class CpuUsageCalculator {
    private var previous: CpuTicks? = null

    @Synchronized
    fun add(sample: CpuTicks): CpuUsageResult {
        if (sample.total < 0 || sample.idle < 0 || sample.idle > sample.total) return CpuUsageResult.Invalid
        val old = previous
        previous = sample
        if (old == null) return CpuUsageResult.FirstSample
        val totalDelta = sample.total - old.total
        val idleDelta = sample.idle - old.idle
        if (totalDelta < 0 || idleDelta < 0) return CpuUsageResult.CounterReset
        if (totalDelta == 0L || idleDelta > totalDelta) return CpuUsageResult.Invalid
        val usage = (totalDelta - idleDelta).toDouble() * 100.0 / totalDelta.toDouble()
        return if (usage.isFinite() && usage in 0.0..100.0) {
            CpuUsageResult.Value(usage)
        } else {
            CpuUsageResult.Invalid
        }
    }
}
