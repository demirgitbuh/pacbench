package com.demirarch.pacbench.data.export

import com.demirarch.pacbench.data.local.PerformanceSample
import com.demirarch.pacbench.data.local.SessionWithGameAndSamples
import com.demirarch.pacbench.data.local.toSampleData
import com.demirarch.pacbench.model.SessionExport
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object SessionExportSerializer {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
    }

    private val csvHeader = listOf(
        "session_id",
        "game_package",
        "game_name",
        "access_mode",
        "started_at",
        "ended_at",
        "device_manufacturer",
        "device_model",
        "android_version",
        "app_version",
        "data_quality_summary",
        "notes",
        "sample_id",
        "timestamp",
        "fps",
        "frame_time",
        "cpu_usage",
        "cpu_frequency",
        "cpu_temp",
        "gpu_usage",
        "gpu_frequency",
        "gpu_temp",
        "ram_used",
        "ram_available",
        "battery_level",
        "battery_temp",
        "voltage",
        "current",
        "power_watts",
        "download_rate",
        "upload_rate",
        "ping",
        "thermal_state",
    )

    fun toJson(rows: SessionWithGameAndSamples): String {
        val session = rows.session
        return json.encodeToString(
            SessionExport(
                appVersion = session.appVersion,
                sessionId = session.id,
                gamePackage = rows.game.packageName,
                gameName = rows.game.displayName,
                deviceManufacturer = session.deviceManufacturer,
                deviceModel = session.deviceModel,
                accessMode = session.accessMode,
                startedAt = session.startedAt,
                endedAt = session.endedAt,
                dataQualitySummary = session.dataQualitySummary,
                samples = sortedSamples(rows).map { it.toSampleData() },
            ),
        )
    }

    fun toCsv(rows: SessionWithGameAndSamples): String = buildString {
        appendCsvRow(csvHeader)
        val samples = sortedSamples(rows)
        if (samples.isEmpty()) {
            appendCsvRow(metadataValues(rows) + List(csvHeader.size - METADATA_COLUMN_COUNT) { null })
        } else {
            samples.forEach { sample ->
                appendCsvRow(
                    metadataValues(rows) + listOf(
                        sample.id,
                        sample.timestamp,
                        sample.fps,
                        sample.frameTime,
                        sample.cpuUsage,
                        sample.cpuFrequency,
                        sample.cpuTemp,
                        sample.gpuUsage,
                        sample.gpuFrequency,
                        sample.gpuTemp,
                        sample.ramUsed,
                        sample.ramAvailable,
                        sample.batteryLevel,
                        sample.batteryTemp,
                        sample.voltage,
                        sample.current,
                        sample.powerWatts,
                        sample.downloadRate,
                        sample.uploadRate,
                        sample.ping,
                        sample.thermalState,
                    ),
                )
            }
        }
    }

    private fun metadataValues(rows: SessionWithGameAndSamples): List<Any?> = with(rows.session) {
        listOf(
            id,
            rows.game.packageName,
            rows.game.displayName,
            accessMode.name,
            startedAt,
            endedAt,
            deviceManufacturer,
            deviceModel,
            androidVersion,
            appVersion,
            dataQualitySummary,
            notes,
        )
    }

    private fun sortedSamples(rows: SessionWithGameAndSamples): List<PerformanceSample> =
        rows.samples.sortedWith(compareBy(PerformanceSample::timestamp, PerformanceSample::id))

    private fun StringBuilder.appendCsvRow(values: List<Any?>) {
        values.forEachIndexed { index, value ->
            if (index > 0) append(',')
            append(escapeCsv(value?.toString().orEmpty()))
        }
        append("\r\n")
    }

    private fun escapeCsv(value: String): String {
        if (value.none { it == ',' || it == '"' || it == '\r' || it == '\n' }) return value
        return buildString(value.length + 2) {
            append('"')
            value.forEach { character ->
                if (character == '"') append('"')
                append(character)
            }
            append('"')
        }
    }

    private const val METADATA_COLUMN_COUNT = 12
}
