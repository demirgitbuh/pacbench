package com.demirarch.pacbench.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class SerializationTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun hudPresetRoundTrips() {
        val preset = BuiltInHudPresets.all.first()
        assertEquals(preset, json.decodeFromString<HudPreset>(json.encodeToString(preset)))
    }

    @Test
    fun sessionExportIncludesSchemaVersion() {
        val export = SessionExport(
            sessionId = 4,
            gamePackage = "com.example.game",
            gameName = "Example",
            deviceManufacturer = "Vendor",
            deviceModel = "Device",
            accessMode = AccessMode.NORMAL,
            startedAt = 1,
            endedAt = 2,
            dataQualitySummary = "FPS unavailable in normal mode",
            samples = emptyList(),
        )
        val encoded = json.encodeToString(export)
        assert(encoded.contains("\"schemaVersion\":1"))
        assertEquals(export, json.decodeFromString<SessionExport>(encoded))
    }
}
