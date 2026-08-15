package com.demirarch.pacbench.metrics

data class GfxInfoFramestatsData(
    val frameDurationsNanos: List<Long>,
    val completionTimestampsNanos: List<Long>,
) {
    fun statistics(): FrameStatistics? {
        val timing = calculateFrameStatistics(completionTimestampsNanos) ?: return null
        val validDurations = frameDurationsNanos.filter { it > 0 }
        if (validDurations.isEmpty()) return timing
        return timing.copy(averageFrameTimeMillis = validDurations.average() / 1_000_000.0)
    }
}

object GfxInfoFramestatsParser {
    fun hasSchema(text: String): Boolean = text.lineSequence().any { line ->
        val columns = line.trim().split(',').map(String::trim)
        "IntendedVsync" in columns && "FrameCompleted" in columns
    }

    fun parse(text: String): GfxInfoFramestatsData? {
        var header: List<String>? = null
        var current: FrameSeries? = null
        val series = mutableListOf<FrameSeries>()

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val columns = line.split(',').map(String::trim)
            if ("IntendedVsync" in columns && "FrameCompleted" in columns) {
                header = columns
                current = FrameSeries().also { series += it }
                continue
            }
            val names = header ?: continue
            val destination = current ?: continue
            if (columns.size < names.size) continue
            val intendedIndex = names.indexOf("IntendedVsync")
            val completedIndex = names.indexOf("FrameCompleted")
            val flagsIndex = names.indexOf("Flags")
            if (intendedIndex < 0 || completedIndex < 0) continue
            val flags = if (flagsIndex >= 0) columns[flagsIndex].toLongOrNull() ?: continue else 0L
            if (flags != 0L) continue
            val intended = columns[intendedIndex].toLongOrNull() ?: continue
            val completed = columns[completedIndex].toLongOrNull() ?: continue
            if (intended <= 0 || completed <= intended || completed == Long.MAX_VALUE) continue
            val presentIndex = names.indexOf("DisplayPresentTime")
            val presented = presentIndex.takeIf { it >= 0 }
                ?.let { columns[it].toLongOrNull() }
                ?.takeIf { it > 0 && it != Long.MAX_VALUE }
            destination.durations += completed - intended
            destination.completions += presented ?: completed
        }

        val populated = series.filter { it.durations.isNotEmpty() }
        val viable = populated.filter { calculateFrameStatistics(it.completions) != null }
        val selected = (viable.ifEmpty { populated }).asSequence()
            .maxWithOrNull(compareBy<FrameSeries>({ it.lastTimestamp }, { it.durations.size }))
            ?: return null
        return GfxInfoFramestatsData(selected.durations, selected.completions)
    }

    private data class FrameSeries(
        val durations: MutableList<Long> = mutableListOf(),
        val completions: MutableList<Long> = mutableListOf(),
    ) {
        val lastTimestamp: Long get() = completions.maxOrNull() ?: Long.MIN_VALUE
    }
}
