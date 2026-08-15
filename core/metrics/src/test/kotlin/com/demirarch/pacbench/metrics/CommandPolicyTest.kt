package com.demirarch.pacbench.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandPolicyTest {
    @Test
    fun mapsOnlyTypedCommandsToFixedExecutables() {
        assertEquals(
            listOf("/system/bin/cat", "/proc/stat"),
            CommandPolicy.argv(SafeCommand.ReadFile("/proc/stat")),
        )
        assertEquals(
            listOf("/system/bin/dumpsys", "gfxinfo", "com.example.game", "framestats"),
            CommandPolicy.argv(SafeCommand.GfxInfoFramestats("com.example.game")),
        )
    }

    @Test
    fun rejectsTraversalNonNormalizedAndNonMetricPaths() {
        assertFalse(CommandPolicy.isAllowedSystemPath("/sys/class/thermal/../proc/stat"))
        assertFalse(CommandPolicy.isAllowedSystemPath("/sys//class/thermal"))
        assertFalse(CommandPolicy.isAllowedSystemPath("/data/local/tmp/value"))
        assertTrue(CommandPolicy.isAllowedSystemPath("/sys/class/thermal/thermal_zone0/temp"))
    }

    @Test
    fun rejectsUntrustedPackageAndLayerSyntax() {
        assertThrows(IllegalArgumentException::class.java) {
            CommandPolicy.argv(SafeCommand.GfxInfoFramestats("com.example.game;id"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CommandPolicy.argv(SafeCommand.SurfaceFlingerLatency("layer\n/system/bin/id"))
        }
    }
}
