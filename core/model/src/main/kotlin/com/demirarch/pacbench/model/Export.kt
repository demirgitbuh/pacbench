package com.demirarch.pacbench.model

import kotlinx.serialization.Serializable

@Serializable
data class SessionExport(
    val schemaVersion: Int = 1,
    val appVersion: String = "0.1.0",
    val sessionId: Long,
    val gamePackage: String,
    val gameName: String,
    val deviceManufacturer: String,
    val deviceModel: String,
    val accessMode: AccessMode,
    val startedAt: Long,
    val endedAt: Long?,
    val dataQualitySummary: String,
    val samples: List<SampleData>,
)
