package com.demirarch.pacbench.metrics

data class TrafficCounters(
    val timestampNanos: Long,
    val receivedBytes: Long,
    val transmittedBytes: Long,
)

sealed interface NetworkRateResult {
    data class Value(val downloadMbps: Double, val uploadMbps: Double) : NetworkRateResult
    data object FirstSample : NetworkRateResult
    data object CounterReset : NetworkRateResult
    data object Invalid : NetworkRateResult
}

class NetworkRateCalculator {
    private var previous: TrafficCounters? = null

    @Synchronized
    fun add(sample: TrafficCounters): NetworkRateResult {
        if (sample.timestampNanos < 0 || sample.receivedBytes < 0 || sample.transmittedBytes < 0) {
            return NetworkRateResult.Invalid
        }
        val old = previous
        if (old == null) {
            previous = sample
            return NetworkRateResult.FirstSample
        }
        if (sample.timestampNanos <= old.timestampNanos) return NetworkRateResult.Invalid
        val elapsed = sample.timestampNanos - old.timestampNanos
        val received = sample.receivedBytes - old.receivedBytes
        val transmitted = sample.transmittedBytes - old.transmittedBytes
        previous = sample
        if (received < 0 || transmitted < 0) return NetworkRateResult.CounterReset
        val seconds = elapsed.toDouble() / NANOS_PER_SECOND
        val down = received.toDouble() * 8.0 / seconds / BITS_PER_MEGABIT
        val up = transmitted.toDouble() * 8.0 / seconds / BITS_PER_MEGABIT
        return if (down.isFinite() && up.isFinite()) {
            NetworkRateResult.Value(down, up)
        } else {
            NetworkRateResult.Invalid
        }
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000.0
        const val BITS_PER_MEGABIT = 1_000_000.0
    }
}

object SysfsValueParser {
    fun percentage(text: String): Double? {
        val trimmed = text.trim()
        val beforeAt = trimmed.substringBefore('@').trim().removeSuffix("%").trim()
        val scalar = beforeAt.split(Regex("\\s+")).firstOrNull()?.toDoubleOrNull()
        if (scalar != null && scalar in 0.0..100.0) return scalar

        val pair = trimmed.split(Regex("\\s+")).mapNotNull(String::toDoubleOrNull)
        if (pair.size >= 2 && pair[1] > 0.0) {
            val value = pair[0] * 100.0 / pair[1]
            if (value.isFinite() && value in 0.0..100.0) return value
        }
        return null
    }

    fun ratioPercentage(text: String): Double? {
        val values = text.trim().split(Regex("\\s+")).mapNotNull(String::toDoubleOrNull)
        if (values.size < 2 || values[0] < 0.0 || values[1] <= 0.0 || values[0] > values[1]) return null
        return (values[0] * 100.0 / values[1]).takeIf { it.isFinite() && it in 0.0..100.0 }
    }

    fun frequencyMhz(text: String): Double? {
        val raw = positiveScalar(text) ?: return null
        return when {
            raw >= 10_000_000.0 -> raw / 1_000_000.0 // Hz
            raw >= 10_000.0 -> raw / 1_000.0 // kHz
            else -> raw // MHz
        }.validFrequencyMhz()
    }

    fun frequencyMhzFromHertz(text: String): Double? = positiveScalar(text)
        ?.div(1_000_000.0)
        ?.validFrequencyMhz()

    fun frequencyMhzFromKilohertz(text: String): Double? = positiveScalar(text)
        ?.div(1_000.0)
        ?.validFrequencyMhz()

    fun temperatureCelsius(text: String): Double? {
        val raw = text.trim().split(Regex("\\s+")).firstOrNull()?.toDoubleOrNull() ?: return null
        if (!raw.isFinite()) return null
        val celsius = when {
            kotlin.math.abs(raw) >= 1_000.0 -> raw / 1_000.0
            kotlin.math.abs(raw) > 200.0 -> raw / 10.0
            else -> raw
        }
        return celsius.takeIf { it.isFinite() && it in -40.0..200.0 }
    }

    fun temperatureCelsiusFromMillidegrees(text: String): Double? = text.trim()
        .split(Regex("\\s+"))
        .firstOrNull()
        ?.toDoubleOrNull()
        ?.div(1_000.0)
        ?.takeIf { it.isFinite() && it in -40.0..200.0 }

    private fun positiveScalar(text: String): Double? = text.trim()
        .split(Regex("\\s+"))
        .firstOrNull()
        ?.toDoubleOrNull()
        ?.takeIf { it.isFinite() && it > 0.0 }

    private fun Double.validFrequencyMhz(): Double? = takeIf { it.isFinite() && it in 1.0..10_000.0 }
}
