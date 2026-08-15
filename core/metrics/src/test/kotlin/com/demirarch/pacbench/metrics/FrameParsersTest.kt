package com.demirarch.pacbench.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameParsersTest {
    @Test
    fun surfaceFlingerUsesPresentationTimestampsNotRefreshPeriodAsFps() {
        val parsed = SurfaceFlingerLatencyParser.parse(
            """
            8333333
            0 1000000000 0
            0 1016666667 0
            0 1033333334 0
            """.trimIndent(),
        )

        assertNotNull(parsed)
        assertEquals(8_333_333L, parsed!!.refreshPeriodNanos)
        val statistics = parsed.statistics()!!
        assertEquals(60.0, statistics.fps, 0.001)
        assertEquals(16.666667, statistics.averageFrameTimeMillis, 0.001)
        assertFalse(statistics.fps > 100.0)
    }

    @Test
    fun surfaceFlingerIgnoresInvalidPresentationSentinel() {
        val parsed = SurfaceFlingerLatencyParser.parse(
            "16666667\n0 1000000000 0\n0 ${Long.MAX_VALUE} 0\n0 1016666667 0",
        )

        assertEquals(listOf(1_000_000_000L, 1_016_666_667L), parsed!!.presentationTimestampsNanos)
        assertNull(SurfaceFlingerLatencyParser.parse("not-a-period\n0 1 0"))
    }

    @Test
    fun gfxInfoUsesFrameDurationAndPresentationCadence() {
        val parsed = GfxInfoFramestatsParser.parse(
            """
            ---PROFILEDATA---
            Flags,IntendedVsync,DisplayPresentTime,FrameCompleted
            0,1000000000,1016666667,1020000000
            0,1016666667,1033333334,1036666667
            0,1033333334,1050000001,1053333334
            ---PROFILEDATA---
            """.trimIndent(),
        )

        assertNotNull(parsed)
        val statistics = parsed!!.statistics()!!
        assertEquals(60.0, statistics.fps, 0.001)
        assertEquals(20.0, statistics.averageFrameTimeMillis, 0.001)
    }

    @Test
    fun gfxInfoSelectsNewestWindowInsteadOfMergingWindows() {
        val parsed = GfxInfoFramestatsParser.parse(
            """
            Flags,IntendedVsync,DisplayPresentTime,FrameCompleted
            0,100,110,120
            0,110,120,130
            Flags,IntendedVsync,DisplayPresentTime,FrameCompleted
            0,1000,1010,1020
            0,1010,1020,1030
            1,1020,1030,1040
            """.trimIndent(),
        )

        assertEquals(listOf(20L, 20L), parsed!!.frameDurationsNanos)
        assertEquals(listOf(1_010L, 1_020L), parsed.completionTimestampsNanos)
    }

    @Test
    fun gfxInfoSchemaDetectionTrimsColumnsAndRejectsMissingFrames() {
        assertTrue(GfxInfoFramestatsParser.hasSchema("Flags, IntendedVsync, FrameCompleted"))
        assertNull(GfxInfoFramestatsParser.parse("Flags,IntendedVsync,FrameCompleted\n1,10,20"))
    }
}
