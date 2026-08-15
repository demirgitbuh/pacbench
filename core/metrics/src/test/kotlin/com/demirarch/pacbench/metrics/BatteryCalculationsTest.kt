package com.demirarch.pacbench.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryCalculationsTest {
    @Test
    fun convertsDocumentedBatteryUnits() {
        assertEquals(25.0, BatteryCalculations.levelPercent(50, 200)!!, 0.0)
        assertEquals(42.5, BatteryCalculations.temperatureCelsius(425)!!, 0.0)
        assertEquals(4.2, BatteryCalculations.voltageVolts(4_200)!!, 0.0)
        assertEquals(-0.5, BatteryCalculations.currentAmperes(-500_000)!!, 0.0)
        assertEquals(-2.0, BatteryCalculations.powerWatts(4_000, -500_000)!!, 0.0)
    }

    @Test
    fun preservesAndroidCurrentAndPowerSignConvention() {
        assertEquals(1.25, BatteryCalculations.currentAmperes(1_250_000)!!, 0.0)
        assertEquals(5.0, BatteryCalculations.powerWatts(4_000, 1_250_000)!!, 0.0)
        assertEquals(0.0, BatteryCalculations.currentAmperes(0)!!, 0.0)
    }

    @Test
    fun rejectsSentinelsAndInvalidBatteryValues() {
        assertNull(BatteryCalculations.levelPercent(-1, 100))
        assertNull(BatteryCalculations.levelPercent(101, 100))
        assertNull(BatteryCalculations.temperatureCelsius(Int.MIN_VALUE))
        assertNull(BatteryCalculations.temperatureCelsius(2_500))
        assertNull(BatteryCalculations.voltageVolts(Int.MIN_VALUE))
        assertNull(BatteryCalculations.voltageVolts(0))
        assertNull(BatteryCalculations.currentAmperes(Long.MIN_VALUE))
        assertNull(BatteryCalculations.powerWatts(null, 1_000_000))
    }
}
