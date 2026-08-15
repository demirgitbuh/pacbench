package com.demirarch.pacbench.metrics

data class FrameStatistics(
    val fps: Double,
    val averageFrameTimeMillis: Double,
    val lastTimestampNanos: Long,
    val frameCount: Int,
)

data class SurfaceFlingerLatencyData(
    val refreshPeriodNanos: Long,
    val presentationTimestampsNanos: List<Long>,
) {
    fun statistics(): FrameStatistics? = calculateFrameStatistics(presentationTimestampsNanos)
}

object SurfaceFlingerLatencyParser {
    private const val INVALID_TIMESTAMP = Long.MAX_VALUE

    fun parse(text: String): SurfaceFlingerLatencyData? {
        val lines = text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        val refreshPeriod = lines.firstOrNull()?.toLongOrNull()?.takeIf { it > 0 } ?: return null
        val timestamps = lines.drop(1).mapNotNull { line ->
            val values = line.split(Regex("\\s+"))
            if (values.size != 3) return@mapNotNull null
            val actualPresentTime = values[1].toLongOrNull() ?: return@mapNotNull null
            actualPresentTime.takeIf { it > 0 && it != INVALID_TIMESTAMP }
        }
        return SurfaceFlingerLatencyData(refreshPeriod, timestamps)
    }
}

internal fun calculateFrameStatistics(rawTimestamps: List<Long>): FrameStatistics? {
    val timestamps = rawTimestamps.asSequence()
        .filter { it > 0 && it != Long.MAX_VALUE }
        .distinct()
        .sorted()
        .toList()
    if (timestamps.size < 2) return null
    val deltas = timestamps.zipWithNext { first, second -> second - first }.filter { it > 0 }
    if (deltas.isEmpty()) return null
    val span = timestamps.last() - timestamps.first()
    if (span <= 0) return null
    val fps = (timestamps.size - 1).toDouble() * 1_000_000_000.0 / span.toDouble()
    val averageFrameTime = deltas.average() / 1_000_000.0
    if (!fps.isFinite() || !averageFrameTime.isFinite() || fps <= 0.0 || averageFrameTime <= 0.0) return null
    return FrameStatistics(fps, averageFrameTime, timestamps.last(), timestamps.size)
}
