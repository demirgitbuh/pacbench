package com.demirarch.pacbench.data.repository

import com.demirarch.pacbench.data.local.HudPresetDao
import com.demirarch.pacbench.data.local.HudPresetEntity
import com.demirarch.pacbench.model.BuiltInHudPresets
import com.demirarch.pacbench.model.HudPreset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface HudPresetRepository {
    fun observePresets(query: String = ""): Flow<List<HudPreset>>

    suspend fun get(id: String): HudPreset?

    suspend fun save(preset: HudPreset, updatedAt: Long)

    suspend fun deleteCustom(id: String): Boolean

    suspend fun seedBuiltIns(timestamp: Long)
}

class RoomHudPresetRepository(
    private val presetDao: HudPresetDao,
    private val json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    },
) : HudPresetRepository {
    private val builtInPresetIds = BuiltInHudPresets.all.mapTo(hashSetOf(), HudPreset::id)

    override fun observePresets(query: String): Flow<List<HudPreset>> {
        val entities = if (query.isBlank()) {
            presetDao.observePresets()
        } else {
            presetDao.searchPresets(query.trim())
        }
        return entities.map { rows -> rows.map(::decode) }
    }

    override suspend fun get(id: String): HudPreset? = presetDao.getById(id)?.let(::decode)

    override suspend fun save(preset: HudPreset, updatedAt: Long) {
        require(preset.id.isNotBlank()) { "Preset ID must not be blank" }
        require(preset.name.isNotBlank()) { "Preset name must not be blank" }
        require(preset.id !in builtInPresetIds) { "Built-in preset IDs are reserved" }
        val existing = presetDao.getById(preset.id)
        presetDao.saveCustom(
            HudPresetEntity(
                id = preset.id,
                name = preset.name,
                presetJson = json.encodeToString(preset),
                isBuiltIn = false,
                createdAt = existing?.createdAt ?: updatedAt,
                updatedAt = updatedAt,
            ),
        )
    }

    override suspend fun deleteCustom(id: String): Boolean = presetDao.deleteCustom(id) == 1

    override suspend fun seedBuiltIns(timestamp: Long) {
        presetDao.replaceBuiltIns(
            BuiltInHudPresets.all.map { preset ->
                HudPresetEntity(
                    id = preset.id,
                    name = preset.name,
                    presetJson = json.encodeToString(preset),
                    isBuiltIn = true,
                    createdAt = timestamp,
                    updatedAt = timestamp,
                )
            },
        )
    }

    private fun decode(entity: HudPresetEntity): HudPreset = json.decodeFromString(entity.presetJson)
}
