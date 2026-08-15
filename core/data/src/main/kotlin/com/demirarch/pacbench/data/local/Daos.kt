package com.demirarch.pacbench.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import com.demirarch.pacbench.model.MetricCalculations
import com.demirarch.pacbench.model.SampleData
import kotlinx.coroutines.flow.Flow

@Dao
abstract class GameDao {
    @Query(
        """
        SELECT * FROM games
        WHERE is_archived = 0
            AND (:query = '' OR display_name LIKE '%' || :query || '%' COLLATE NOCASE
            OR package_name LIKE '%' || :query || '%' COLLATE NOCASE)
            AND (:favoriteOnly = 0 OR is_favorite = 1)
        ORDER BY is_favorite DESC, COALESCE(last_played_at, 0) DESC, display_name COLLATE NOCASE
        """,
    )
    abstract fun observeGames(query: String = "", favoriteOnly: Boolean = false): Flow<List<Game>>

    @Query("SELECT * FROM games WHERE id = :id")
    abstract suspend fun getById(id: Long): Game?

    @Query("SELECT * FROM games WHERE package_name = :packageName")
    abstract suspend fun getByPackage(packageName: String): Game?

    @Transaction
    @Query("SELECT * FROM games WHERE id = :id")
    abstract suspend fun getWithSessions(id: Long): GameWithSessions?

    @Transaction
    open suspend fun save(game: Game): Long {
        require(game.packageName.isNotBlank()) { "Package name must not be blank" }
        require(game.displayName.isNotBlank()) { "Display name must not be blank" }
        require(game.lastSeenAt >= game.firstSeenAt) { "Last seen time must not precede first seen time" }

        val existingById = game.id.takeIf { it != 0L }?.let { getById(it) }
        require(existingById == null || existingById.packageName == game.packageName) {
            "Game ID belongs to another package"
        }
        val existing = getByPackage(game.packageName)
        if (existing != null) {
            require(game.id == 0L || game.id == existing.id) { "Package name belongs to another game" }
            upsertEntity(
                game.copy(
                    id = existing.id,
                    displayName = if (game.id == 0L) existing.customName ?: game.displayName else game.displayName,
                    appName = game.appName.ifBlank { existing.appName },
                    customName = if (game.id == 0L) existing.customName else game.customName,
                    iconReference = if (game.id == 0L) existing.iconReference else game.iconReference,
                    versionName = game.versionName ?: existing.versionName,
                    versionCode = game.versionCode ?: existing.versionCode,
                    isFavorite = if (game.id == 0L) existing.isFavorite else game.isFavorite,
                    firstSeenAt = minOf(existing.firstSeenAt, game.firstSeenAt),
                    addedAt = minOf(existing.addedAt, game.addedAt),
                    lastSeenAt = maxOf(existing.lastSeenAt, game.lastSeenAt),
                    lastPlayedAt = listOfNotNull(existing.lastPlayedAt, game.lastPlayedAt).maxOrNull(),
                    launchConfiguration = if (game.id == 0L) existing.launchConfiguration else game.launchConfiguration,
                    selectedHudPresetId = if (game.id == 0L) existing.selectedHudPresetId else game.selectedHudPresetId,
                    autoMonitoring = if (game.id == 0L) existing.autoMonitoring else game.autoMonitoring,
                    autoOverlay = if (game.id == 0L) existing.autoOverlay else game.autoOverlay,
                    isArchived = if (game.id == 0L) false else existing.isArchived,
                ),
            )
            return existing.id
        }
        return upsertEntity(game)
    }

    @Upsert
    protected abstract suspend fun upsertEntity(game: Game): Long

    @Query("UPDATE games SET is_favorite = :favorite WHERE id = :gameId")
    abstract suspend fun setFavorite(gameId: Long, favorite: Boolean): Int

    @Query("UPDATE games SET custom_name = :customName, display_name = COALESCE(:customName, app_name) WHERE id = :gameId")
    abstract suspend fun setCustomName(gameId: Long, customName: String?): Int

    @Query("UPDATE games SET auto_monitoring = :enabled WHERE id = :gameId")
    abstract suspend fun setAutoMonitoring(gameId: Long, enabled: Boolean): Int

    @Query("UPDATE games SET auto_overlay = :enabled WHERE id = :gameId")
    abstract suspend fun setAutoOverlay(gameId: Long, enabled: Boolean): Int

    @Query("UPDATE games SET selected_hud_preset_id = :presetId WHERE id = :gameId")
    abstract suspend fun setHudPreset(gameId: Long, presetId: String?): Int

    @Query("UPDATE games SET selected_hud_preset_id = NULL WHERE selected_hud_preset_id = :presetId")
    abstract suspend fun clearHudPresetAssignments(presetId: String): Int

    @Query("UPDATE games SET is_archived = 1 WHERE id = :gameId")
    protected abstract suspend fun archive(gameId: Long): Int

    open suspend fun delete(game: Game) {
        check(archive(game.id) == 1) { "Game ${game.id} does not exist" }
    }

    @Query(
        """
        SELECT
            g.id AS gameId,
            g.package_name AS packageName,
            g.display_name AS displayName,
            (SELECT COUNT(*) FROM performance_sessions s WHERE s.game_id = g.id) AS sessionCount,
            (SELECT COUNT(*) FROM performance_samples p
                INNER JOIN performance_sessions s ON s.id = p.session_id
                WHERE s.game_id = g.id) AS sampleCount,
            COALESCE((SELECT SUM(MAX(s.ended_at - s.started_at, 0))
                FROM performance_sessions s
                WHERE s.game_id = g.id AND s.ended_at IS NOT NULL), 0) AS totalDurationMillis,
            (SELECT AVG(p.fps) FROM performance_samples p
                INNER JOIN performance_sessions s ON s.id = p.session_id
                WHERE s.game_id = g.id) AS averageFps,
            (SELECT MIN(p.fps) FROM performance_samples p
                INNER JOIN performance_sessions s ON s.id = p.session_id
                WHERE s.game_id = g.id) AS minFps,
            (SELECT MAX(p.fps) FROM performance_samples p
                INNER JOIN performance_sessions s ON s.id = p.session_id
                WHERE s.game_id = g.id) AS maxFps,
            (SELECT AVG(p.cpu_usage) FROM performance_samples p
                INNER JOIN performance_sessions s ON s.id = p.session_id
                WHERE s.game_id = g.id) AS averageCpuUsage,
            (SELECT AVG(p.gpu_usage) FROM performance_samples p
                INNER JOIN performance_sessions s ON s.id = p.session_id
                WHERE s.game_id = g.id) AS averageGpuUsage,
            (SELECT MAX(p.cpu_temp) FROM performance_samples p
                INNER JOIN performance_sessions s ON s.id = p.session_id
                WHERE s.game_id = g.id) AS peakCpuTemp,
            (SELECT MAX(p.gpu_temp) FROM performance_samples p
                INNER JOIN performance_sessions s ON s.id = p.session_id
                WHERE s.game_id = g.id) AS peakGpuTemp,
            (SELECT MAX(s.started_at) FROM performance_sessions s WHERE s.game_id = g.id) AS lastSessionAt
        FROM games g
        WHERE g.is_archived = 0
            AND (:query = '' OR g.display_name LIKE '%' || :query || '%' COLLATE NOCASE
            OR g.package_name LIKE '%' || :query || '%' COLLATE NOCASE)
        ORDER BY COALESCE(lastSessionAt, 0) DESC, g.display_name COLLATE NOCASE
        """,
    )
    abstract fun observeAggregateStats(query: String = ""): Flow<List<GameAggregateStats>>

    @Query(
        """
        SELECT
            g.id AS gameId,
            g.package_name AS packageName,
            g.display_name AS displayName,
            (SELECT COUNT(*) FROM performance_sessions s WHERE s.game_id = g.id) AS sessionCount,
            (SELECT COUNT(*) FROM performance_samples p
                INNER JOIN performance_sessions s ON s.id = p.session_id
                WHERE s.game_id = g.id) AS sampleCount,
            COALESCE((SELECT SUM(MAX(s.ended_at - s.started_at, 0))
                FROM performance_sessions s
                WHERE s.game_id = g.id AND s.ended_at IS NOT NULL), 0) AS totalDurationMillis,
            (SELECT AVG(p.fps) FROM performance_samples p
                INNER JOIN performance_sessions s ON s.id = p.session_id
                WHERE s.game_id = g.id) AS averageFps,
            (SELECT MIN(p.fps) FROM performance_samples p
                INNER JOIN performance_sessions s ON s.id = p.session_id
                WHERE s.game_id = g.id) AS minFps,
            (SELECT MAX(p.fps) FROM performance_samples p
                INNER JOIN performance_sessions s ON s.id = p.session_id
                WHERE s.game_id = g.id) AS maxFps,
            (SELECT AVG(p.cpu_usage) FROM performance_samples p
                INNER JOIN performance_sessions s ON s.id = p.session_id
                WHERE s.game_id = g.id) AS averageCpuUsage,
            (SELECT AVG(p.gpu_usage) FROM performance_samples p
                INNER JOIN performance_sessions s ON s.id = p.session_id
                WHERE s.game_id = g.id) AS averageGpuUsage,
            (SELECT MAX(p.cpu_temp) FROM performance_samples p
                INNER JOIN performance_sessions s ON s.id = p.session_id
                WHERE s.game_id = g.id) AS peakCpuTemp,
            (SELECT MAX(p.gpu_temp) FROM performance_samples p
                INNER JOIN performance_sessions s ON s.id = p.session_id
                WHERE s.game_id = g.id) AS peakGpuTemp,
            (SELECT MAX(s.started_at) FROM performance_sessions s WHERE s.game_id = g.id) AS lastSessionAt
        FROM games g
        WHERE g.id = :gameId
        """,
    )
    abstract suspend fun getAggregateStats(gameId: Long): GameAggregateStats?
}

@Dao
abstract class PerformanceSessionDao {
    @Query(
        """
        SELECT s.*,
            g.package_name AS game_package_name,
            g.display_name AS game_display_name,
            (SELECT COUNT(*) FROM performance_samples p WHERE p.session_id = s.id) AS sample_count,
            COALESCE(s.average_fps,
                (SELECT AVG(p.fps) FROM performance_samples p WHERE p.session_id = s.id)
            ) AS effective_average_fps,
            (SELECT MAX(value) FROM (
                SELECT MAX(p.cpu_temp) AS value FROM performance_samples p WHERE p.session_id = s.id
                UNION ALL
                SELECT MAX(p.gpu_temp) AS value FROM performance_samples p WHERE p.session_id = s.id
                UNION ALL
                SELECT MAX(p.battery_temp) AS value FROM performance_samples p WHERE p.session_id = s.id
            )) AS effective_max_temperature,
            COALESCE(s.duration_millis,
                MAX(COALESCE(s.ended_at, CAST(strftime('%s', 'now') AS INTEGER) * 1000) - s.started_at, 0)
            ) AS effective_duration_millis
        FROM performance_sessions s
        INNER JOIN games g ON g.id = s.game_id
        WHERE (:query = '' OR g.display_name LIKE '%' || :query || '%' COLLATE NOCASE
            OR g.package_name LIKE '%' || :query || '%' COLLATE NOCASE)
            AND (:gamePackage IS NULL OR g.package_name = :gamePackage)
            AND (:status IS NULL OR s.status = :status)
            AND (:fromTimestamp IS NULL OR s.started_at >= :fromTimestamp)
            AND (:toTimestamp IS NULL OR s.started_at <= :toTimestamp)
        ORDER BY s.started_at DESC
        """,
    )
    abstract fun observeSessions(
        query: String = "",
        gamePackage: String? = null,
        status: SessionStatus? = null,
        fromTimestamp: Long? = null,
        toTimestamp: Long? = null,
    ): Flow<List<SessionListItem>>

    @Query("SELECT * FROM performance_sessions WHERE id = :sessionId")
    abstract suspend fun getSession(sessionId: Long): PerformanceSession?

    @Transaction
    @Query("SELECT * FROM performance_sessions WHERE id = :sessionId")
    abstract suspend fun getSessionWithRows(sessionId: Long): SessionWithGameAndSamples?

    @Query(
        """
        SELECT * FROM performance_samples
        WHERE session_id = :sessionId AND timestamp BETWEEN :fromInclusive AND :toInclusive
        ORDER BY timestamp, id
        """,
    )
    abstract fun observeSamplesInRange(
        sessionId: Long,
        fromInclusive: Long = Long.MIN_VALUE,
        toInclusive: Long = Long.MAX_VALUE,
    ): Flow<List<PerformanceSample>>

    @Query(
        """
        SELECT * FROM performance_samples
        WHERE session_id = :sessionId AND timestamp BETWEEN :fromInclusive AND :toInclusive
        ORDER BY timestamp, id
        """,
    )
    abstract suspend fun getSamplesInRange(
        sessionId: Long,
        fromInclusive: Long = Long.MIN_VALUE,
        toInclusive: Long = Long.MAX_VALUE,
    ): List<PerformanceSample>

    @Transaction
    open suspend fun startSession(request: SessionStartRequest): Long {
        require(request.packageName.isNotBlank()) { "Package name must not be blank" }
        require(request.displayName.isNotBlank()) { "Display name must not be blank" }
        check(findRunningSessionId() == null) { "A performance session is already running" }

        val insertedGameId = insertGame(
            Game(
                packageName = request.packageName,
                displayName = request.displayName,
                appName = request.appName,
                versionName = request.versionName,
                versionCode = request.versionCode,
                firstSeenAt = request.startedAt,
                lastSeenAt = request.startedAt,
                lastPlayedAt = request.startedAt,
            ),
        )
        val gameId = if (insertedGameId == -1L) {
            requireNotNull(findGameId(request.packageName))
        } else {
            insertedGameId
        }
        updateGameForSession(
            gameId = gameId,
            displayName = request.displayName,
            appName = request.appName,
            versionName = request.versionName,
            versionCode = request.versionCode,
            observedAt = request.startedAt,
        )
        return insertSession(
            PerformanceSession(
                gameId = gameId,
                startedAt = request.startedAt,
                accessMode = request.accessMode,
                deviceManufacturer = request.deviceManufacturer,
                deviceModel = request.deviceModel,
                androidVersion = request.androidVersion,
                appVersion = request.appVersion,
                notes = request.notes,
            ),
        )
    }

    @Transaction
    open suspend fun insertSamples(sessionId: Long, samples: List<SampleData>): List<Long> {
        if (samples.isEmpty()) return emptyList()
        val session = requireNotNull(getSession(sessionId)) { "Session $sessionId does not exist" }
        check(session.status == SessionStatus.RUNNING) { "Samples can only be added to a running session" }
        require(samples.all { it.timestamp >= session.startedAt }) {
            "Sample timestamps must not precede the session"
        }
        require(samples.all(SampleData::hasOnlyFiniteMetricValues)) {
            "Sample metric values must be finite"
        }
        return insertSampleEntities(samples.map { it.toEntity(sessionId) })
    }

    @Transaction
    open suspend fun finalizeSession(
        sessionId: Long,
        endedAt: Long,
        dataQualitySummary: String,
        notes: String? = null,
    ): Boolean {
        val session = getSession(sessionId) ?: return false
        if (session.status != SessionStatus.RUNNING) return false
        require(endedAt >= session.startedAt) { "Session cannot end before it starts" }
        val latestSampleAt = findLatestSampleTimestamp(sessionId)
        require(latestSampleAt == null || endedAt >= latestSampleAt) {
            "Session cannot end before its latest sample"
        }
        val samples = getSamplesInRange(sessionId).map(PerformanceSample::toSampleData)
        val summary = MetricCalculations.sessionSummary(samples)
        return updateSession(
            session.copy(
                endedAt = endedAt,
                durationMillis = endedAt - session.startedAt,
                status = SessionStatus.COMPLETED,
                dataQualitySummary = dataQualitySummary,
                averageFps = summary.averageFps,
                medianFps = summary.medianFps,
                minFps = summary.minFps,
                maxFps = summary.maxFps,
                onePercentLow = summary.onePercentLow,
                pointOnePercentLow = summary.pointOnePercentLow,
                averageCpu = summary.averageCpu,
                averageGpu = summary.averageGpu,
                maxCpuTemp = summary.maxCpuTemp,
                maxGpuTemp = summary.maxGpuTemp,
                maxBatteryTemp = summary.maxBatteryTemp,
                averagePower = summary.averagePower,
                batteryStart = samples.firstNotNullOfOrNull(SampleData::batteryLevel),
                batteryEnd = samples.asReversed().firstNotNullOfOrNull(SampleData::batteryLevel),
                thermalEventCount = summary.thermalEventCount,
                notes = notes ?: session.notes,
            ),
        ) == 1
    }

    @Transaction
    open suspend fun recoverRunningSessions(recoveredAt: Long, reason: String): Int {
        require(reason.isNotBlank()) { "Recovery reason must not be blank" }
        return markRunningSessionsInterrupted(recoveredAt, reason)
    }

    @Query("SELECT id FROM performance_sessions WHERE status = 'RUNNING' LIMIT 1")
    protected abstract suspend fun findRunningSessionId(): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertGame(game: Game): Long

    @Query("SELECT id FROM games WHERE package_name = :packageName")
    protected abstract suspend fun findGameId(packageName: String): Long?

    @Query(
        """
        UPDATE games SET
            app_name = :appName,
            display_name = COALESCE(custom_name, :displayName),
            version_name = COALESCE(:versionName, version_name),
            version_code = COALESCE(:versionCode, version_code),
            last_seen_at = MAX(last_seen_at, :observedAt),
            last_played_at = MAX(COALESCE(last_played_at, :observedAt), :observedAt),
            is_archived = 0
        WHERE id = :gameId
        """,
    )
    protected abstract suspend fun updateGameForSession(
        gameId: Long,
        displayName: String,
        appName: String,
        versionName: String?,
        versionCode: Long?,
        observedAt: Long,
    )

    @Insert
    protected abstract suspend fun insertSession(session: PerformanceSession): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertSampleEntities(samples: List<PerformanceSample>): List<Long>

    @Query("SELECT MAX(timestamp) FROM performance_samples WHERE session_id = :sessionId")
    protected abstract suspend fun findLatestSampleTimestamp(sessionId: Long): Long?

    @Update
    protected abstract suspend fun updateSession(session: PerformanceSession): Int

    @Query(
        """
        UPDATE performance_sessions SET
            ended_at = CASE
                WHEN (SELECT MAX(p.timestamp) FROM performance_samples p
                    WHERE p.session_id = performance_sessions.id) IS NULL THEN started_at
                ELSE MAX(started_at, MIN(:recoveredAt,
                    (SELECT MAX(p.timestamp) FROM performance_samples p
                        WHERE p.session_id = performance_sessions.id)))
            END,
            duration_millis = CASE
                WHEN (SELECT MAX(p.timestamp) FROM performance_samples p
                    WHERE p.session_id = performance_sessions.id) IS NULL THEN 0
                ELSE MAX(0, MIN(:recoveredAt,
                    (SELECT MAX(p.timestamp) FROM performance_samples p
                        WHERE p.session_id = performance_sessions.id)) - started_at)
            END,
            status = 'INTERRUPTED',
            data_quality_summary = CASE
                WHEN data_quality_summary = '' THEN :reason
                ELSE data_quality_summary || '; ' || :reason
            END
        WHERE status = 'RUNNING'
        """,
    )
    protected abstract suspend fun markRunningSessionsInterrupted(recoveredAt: Long, reason: String): Int

    @Query("DELETE FROM performance_sessions WHERE id = :sessionId")
    abstract suspend fun deleteSession(sessionId: Long): Int

    @Query("DELETE FROM performance_sessions WHERE ended_at IS NOT NULL AND ended_at < :cutoffTimestamp")
    abstract suspend fun deleteEndedBefore(cutoffTimestamp: Long): Int
}

@Dao
abstract class HudPresetDao {
    @Query("SELECT * FROM hud_presets ORDER BY is_built_in DESC, name COLLATE NOCASE")
    abstract fun observePresets(): Flow<List<HudPresetEntity>>

    @Query(
        """
        SELECT * FROM hud_presets
        WHERE :query = '' OR name LIKE '%' || :query || '%' COLLATE NOCASE
        ORDER BY is_built_in DESC, name COLLATE NOCASE
        """,
    )
    abstract fun searchPresets(query: String): Flow<List<HudPresetEntity>>

    @Query("SELECT * FROM hud_presets WHERE id = :id")
    abstract suspend fun getById(id: String): HudPresetEntity?

    @Transaction
    open suspend fun saveCustom(entity: HudPresetEntity) {
        require(!entity.isBuiltIn) { "Custom presets cannot be marked as built in" }
        check(getById(entity.id)?.isBuiltIn != true) { "Built-in presets cannot be replaced" }
        upsert(entity)
    }

    @Transaction
    open suspend fun replaceBuiltIns(entities: List<HudPresetEntity>) {
        require(entities.all { it.isBuiltIn }) { "Seeded presets must be built in" }
        require(entities.map(HudPresetEntity::id).distinct().size == entities.size) {
            "Seeded preset IDs must be unique"
        }
        require(entities.all { it.id.isNotBlank() && it.name.isNotBlank() && it.presetJson.isNotBlank() }) {
            "Seeded presets must have an ID, name, and JSON payload"
        }

        val ids = entities.map(HudPresetEntity::id)
        if (ids.isNotEmpty()) {
            check(findCustomPresetIds(ids).isEmpty()) { "A built-in preset ID belongs to a custom preset" }
        }
        val replacements = entities.map { entity ->
            val existing = getById(entity.id)
            if (existing?.isBuiltIn == true) entity.copy(createdAt = existing.createdAt) else entity
        }
        deleteBuiltIns()
        insertAll(replacements)
    }

    @Upsert
    protected abstract suspend fun upsert(entity: HudPresetEntity)

    @Query("SELECT id FROM hud_presets WHERE is_built_in = 0 AND id IN (:ids)")
    protected abstract suspend fun findCustomPresetIds(ids: List<String>): List<String>

    @Query("DELETE FROM hud_presets WHERE is_built_in = 1")
    protected abstract suspend fun deleteBuiltIns()

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertAll(entities: List<HudPresetEntity>)

    @Query("DELETE FROM hud_presets WHERE id = :id AND is_built_in = 0")
    abstract suspend fun deleteCustom(id: String): Int
}
