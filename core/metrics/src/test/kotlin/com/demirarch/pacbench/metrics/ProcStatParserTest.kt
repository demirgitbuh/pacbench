package com.demirarch.pacbench.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ProcStatParserTest {
    @Test
    fun parsesAggregateCpuWithoutDoubleCountingGuestFields() {
        val parsed = ProcStatParser.parse(
            "cpu 100 20 30 400 10 5 7 8 9 11\ncpu0 50 10 15 200 5 2 3 4 4 5\n",
        )

        assertEquals(CpuTicks(total = 580, idle = 410), parsed)
    }

    @Test
    fun rejectsMalformedNegativeAndOverflowingCounters() {
        assertNull(ProcStatParser.parse("cpu 1 2 3"))
        assertNull(ProcStatParser.parse("cpu 1 2 3 -4"))
        assertNull(ProcStatParser.parse("cpu ${Long.MAX_VALUE} 1 0 0"))
        assertNull(ProcStatParser.parse("cpu0 1 2 3 4"))
    }

    @Test
    fun calculatesUsageFromCounterDeltas() {
        val calculator = CpuUsageCalculator()

        assertSame(CpuUsageResult.FirstSample, calculator.add(CpuTicks(total = 100, idle = 40)))
        val value = calculator.add(CpuTicks(total = 200, idle = 70)) as CpuUsageResult.Value
        assertEquals(70.0, value.percent, 0.0001)
    }

    @Test
    fun reportsCounterReset() {
        val calculator = CpuUsageCalculator()
        calculator.add(CpuTicks(total = 200, idle = 100))

        assertSame(CpuUsageResult.CounterReset, calculator.add(CpuTicks(total = 100, idle = 50)))
    }
}
