package com.demirarch.pacbench.metrics

object BatteryCalculations {
    fun levelPercent(level: Int?, scale: Int?): Double? {
        if (level == null || scale == null || level < 0 || scale <= 0 || level > scale) return null
        return level.toDouble() * 100.0 / scale.toDouble()
    }

    fun temperatureCelsius(tenthsCelsius: Int?): Double? = tenthsCelsius
        ?.takeUnless { it == Int.MIN_VALUE }
        ?.toDouble()
        ?.div(10.0)
        ?.takeIf { it.isFinite() && it in MIN_BATTERY_CELSIUS..MAX_BATTERY_CELSIUS }

    fun voltageVolts(millivolts: Int?): Double? = millivolts
        ?.takeUnless { it == Int.MIN_VALUE }
        ?.takeIf { it > 0 }
        ?.toDouble()
        ?.div(MILLIVOLTS_PER_VOLT)

    /** Android's sign convention is positive while charging and negative while discharging. */
    fun currentAmperes(microamperes: Long?): Double? = microamperes
        ?.takeUnless { it == Long.MIN_VALUE }
        ?.toDouble()
        ?.div(MICROAMPERES_PER_AMPERE)
        ?.takeIf { it.isFinite() }

    fun powerWatts(millivolts: Int?, microamperes: Long?): Double? {
        val voltage = voltageVolts(millivolts) ?: return null
        val current = currentAmperes(microamperes) ?: return null
        return (voltage * current).takeIf { it.isFinite() }
    }

    private const val MIN_BATTERY_CELSIUS = -40.0
    private const val MAX_BATTERY_CELSIUS = 200.0
    private const val MILLIVOLTS_PER_VOLT = 1_000.0
    private const val MICROAMPERES_PER_AMPERE = 1_000_000.0
}
