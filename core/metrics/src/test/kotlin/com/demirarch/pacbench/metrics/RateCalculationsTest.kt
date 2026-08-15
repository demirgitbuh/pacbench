package com.demirarch.pacbench.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class RateCalculationsTest {
    @Test
    fun calculatesDecimalMegabitsPerSecond() {
        val calculator = NetworkRateCalculator()
        assertSame(NetworkRateResult.FirstSample, calculator.add(TrafficCounters(1_000_000_000, 1_000, 2_000)))

        val result = calculator.add(TrafficCounters(3_000_000_000, 2_001_000, 1_002_000)) as NetworkRateResult.Value
        assertEquals(8.0, result.downloadMbps, 0.0001)
        assertEquals(4.0, result.uploadMbps, 0.0001)
    }

    @Test
    fun invalidTimestampDoesNotReplaceValidBaseline() {
        val calculator = NetworkRateCalculator()
        calculator.add(TrafficCounters(1_000, 100, 100))
        assertSame(NetworkRateResult.Invalid, calculator.add(TrafficCounters(1_000, 200, 200)))

        val result = calculator.add(TrafficCounters(1_000_001_000, 1_000_100, 500_100)) as NetworkRateResult.Value
        assertEquals(8.0, result.downloadMbps, 0.0001)
        assertEquals(4.0, result.uploadMbps, 0.0001)
    }

    @Test
    fun reportsCounterResetAndUsesResetAsNewBaseline() {
        val calculator = NetworkRateCalculator()
        calculator.add(TrafficCounters(0, 100, 100))
        assertSame(NetworkRateResult.CounterReset, calculator.add(TrafficCounters(1_000, 10, 20)))
        val result = calculator.add(TrafficCounters(1_001_000, 1_010, 1_020)) as NetworkRateResult.Value
        assertEquals(8.0, result.downloadMbps, 0.0001)
        assertEquals(8.0, result.uploadMbps, 0.0001)
    }

    @Test
    fun parsesSysfsUnitsAndFormatsExplicitly() {
        assertEquals(42.0, SysfsValueParser.percentage("42@800000000")!!, 0.0)
        assertEquals(25.0, SysfsValueParser.ratioPercentage("1 4")!!, 0.0)
        assertEquals(8.0, SysfsValueParser.frequencyMhzFromHertz("8000000")!!, 0.0)
        assertEquals(300.0, SysfsValueParser.frequencyMhzFromKilohertz("300000")!!, 0.0)
        assertEquals(702.0, SysfsValueParser.frequencyMhz("702")!!, 0.0)
        assertEquals(42.0, SysfsValueParser.temperatureCelsiusFromMillidegrees("42000")!!, 0.0)
        assertNull(SysfsValueParser.ratioPercentage("5 4"))
        assertNull(SysfsValueParser.frequencyMhzFromHertz("0"))
    }
}
