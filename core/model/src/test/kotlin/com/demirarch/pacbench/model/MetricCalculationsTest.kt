package com.demirarch.pacbench.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MetricCalculationsTest {
    @Test
    fun onePercentLowUsesSlowestOnePercent() {
        val values = (1..1000).map(Int::toDouble)
        assertEquals(5.5, MetricCalculations.onePercentLow(values)!!, 0.0001)
    }

    @Test
    fun pointOnePercentLowUsesAtLeastOneValue() {
        val values = (1..1000).map(Int::toDouble)
        assertEquals(1.0, MetricCalculations.pointOnePercentLow(values)!!, 0.0001)
        assertNull(MetricCalculations.pointOnePercentLow(emptyList()))
    }

    @Test
    fun frametimeStatisticsAreCalculatedFromRealValues() {
        val stats = MetricCalculations.frametimeStatistics(listOf(10.0, 20.0, 30.0, 40.0))!!
        assertEquals(25.0, stats.averageMs, 0.0001)
        assertEquals(25.0, stats.medianMs, 0.0001)
        assertEquals(3, stats.jankCount)
        assertEquals(1, stats.spikeCount)
    }

    @Test
    fun batteryPowerHonorsAndroidCurrentSignAndUnits() {
        assertEquals(-4.0, MetricCalculations.batteryPowerWatts(4000, -1_000_000), 0.0001)
        assertEquals(4.0, MetricCalculations.dischargePowerWatts(4000, -1_000_000), 0.0001)
        assertEquals(0.0, MetricCalculations.dischargePowerWatts(4000, 1_000_000), 0.0001)
    }

    @Test
    fun summaryLeavesMissingMetricsNull() {
        val summary = MetricCalculations.sessionSummary(
            listOf(
                SampleData(timestamp = 1000, fps = 60.0, batteryLevel = 90.0),
                SampleData(timestamp = 2000, fps = 30.0, batteryLevel = 89.0),
            ),
        )
        assertEquals(45.0, summary.averageFps!!, 0.0001)
        assertEquals(1.0, summary.batteryConsumed!!, 0.0001)
        assertNull(summary.averageGpu)
    }
}
