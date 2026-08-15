package com.demirarch.pacbench.data.export

import com.demirarch.pacbench.data.local.Game
import com.demirarch.pacbench.data.local.PerformanceSample
import com.demirarch.pacbench.data.local.PerformanceSession
import com.demirarch.pacbench.data.local.SessionStatus
import com.demirarch.pacbench.data.local.SessionWithGameAndSamples
import com.demirarch.pacbench.model.AccessMode
import com.demirarch.pacbench.model.SessionExport
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionExportSerializerTest {
    @Test
    fun jsonUsesPersistedMetadataAndSortsActualSampleRows() {
        val rows = rows(
            samples = listOf(
                PerformanceSample(id = 2, sessionId = 7, timestamp = 2_000, fps = 58.5),
                PerformanceSample(id = 1, sessionId = 7, timestamp = 1_000, fps = 60.0, ramUsed = 42),
            ),
        )

        val export = Json.decodeFromString<SessionExport>(SessionExportSerializer.toJson(rows))

        assertEquals(7, export.sessionId)
        assertEquals("com.example.game", export.gamePackage)
        assertEquals("Game, \"Edition\"", export.gameName)
        assertEquals(1_000, export.samples.first().timestamp)
        assertEquals(42L, export.samples.first().ramUsed)
        assertEquals(2_000, export.samples.last().timestamp)
    }

    @Test
    fun csvEscapesTextAndRepresentsNullMetricsAsEmptyColumns() {
        val csv = SessionExportSerializer.toCsv(
            rows(samples = listOf(PerformanceSample(id = 3, sessionId = 7, timestamp = 1_000))),
        )

        val lines = csv.split("\r\n")
        assertEquals(3, lines.size)
        assertTrue(lines[0].startsWith("session_id,game_package,game_name"))
        assertTrue(lines[1].contains("\"Game, \"\"Edition\"\"\""))
        assertTrue(lines[1].contains(",3,1000,"))
        assertTrue(csv.endsWith("\r\n"))
    }

    @Test
    fun csvRetainsSessionMetadataWhenThereAreNoSamples() {
        val csv = SessionExportSerializer.toCsv(rows(samples = emptyList()))

        assertEquals(3, csv.split("\r\n").size)
        assertTrue(csv.contains("com.example.game"))
    }

    private fun rows(samples: List<PerformanceSample>) = SessionWithGameAndSamples(
        session = PerformanceSession(
            id = 7,
            gameId = 4,
            startedAt = 900,
            endedAt = 2_100,
            accessMode = AccessMode.SHIZUKU,
            status = SessionStatus.COMPLETED,
            deviceManufacturer = "Example Corp",
            deviceModel = "Device 1",
            androidVersion = "13",
            appVersion = "0.1.0",
            dataQualitySummary = "complete",
            notes = "line one\nline two",
        ),
        game = Game(
            id = 4,
            packageName = "com.example.game",
            displayName = "Game, \"Edition\"",
            firstSeenAt = 800,
            lastSeenAt = 900,
        ),
        samples = samples,
    )
}
