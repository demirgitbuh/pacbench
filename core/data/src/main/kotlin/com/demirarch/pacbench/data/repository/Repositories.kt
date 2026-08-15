package com.demirarch.pacbench.data.repository

import com.demirarch.pacbench.data.export.SessionExportSerializer
import com.demirarch.pacbench.data.local.Game
import com.demirarch.pacbench.data.local.GameAggregateStats
import com.demirarch.pacbench.data.local.GameDao
import com.demirarch.pacbench.data.local.GameWithSessions
import com.demirarch.pacbench.data.local.PerformanceSample
import com.demirarch.pacbench.data.local.PerformanceSessionDao
import com.demirarch.pacbench.data.local.SessionListItem
import com.demirarch.pacbench.data.local.SessionStartRequest
import com.demirarch.pacbench.data.local.SessionStatus
import com.demirarch.pacbench.data.local.SessionWithGameAndSamples
import com.demirarch.pacbench.model.SampleData
import kotlinx.coroutines.flow.Flow

interface GameRepository {
    fun observeGames(query: String = "", favoriteOnly: Boolean = false): Flow<List<Game>>

    fun observeAggregateStats(query: String = ""): Flow<List<GameAggregateStats>>

    suspend fun getById(id: Long): Game?

    suspend fun getByPackage(packageName: String): Game?

    suspend fun getWithSessions(id: Long): GameWithSessions?

    suspend fun getAggregateStats(gameId: Long): GameAggregateStats?

    suspend fun save(game: Game): Long

    suspend fun setFavorite(gameId: Long, favorite: Boolean): Boolean

    suspend fun delete(game: Game)
}

class RoomGameRepository(
    private val gameDao: GameDao,
) : GameRepository {
    override fun observeGames(query: String, favoriteOnly: Boolean): Flow<List<Game>> =
        gameDao.observeGames(query.trim(), favoriteOnly)

    override fun observeAggregateStats(query: String): Flow<List<GameAggregateStats>> =
        gameDao.observeAggregateStats(query.trim())

    override suspend fun getById(id: Long): Game? = gameDao.getById(id)

    override suspend fun getByPackage(packageName: String): Game? = gameDao.getByPackage(packageName)

    override suspend fun getWithSessions(id: Long): GameWithSessions? = gameDao.getWithSessions(id)

    override suspend fun getAggregateStats(gameId: Long): GameAggregateStats? =
        gameDao.getAggregateStats(gameId)

    override suspend fun save(game: Game): Long {
        require(game.packageName.isNotBlank()) { "Package name must not be blank" }
        require(game.displayName.isNotBlank()) { "Display name must not be blank" }
        require(game.lastSeenAt >= game.firstSeenAt) { "Last seen time must not precede first seen time" }
        return gameDao.save(game)
    }

    override suspend fun setFavorite(gameId: Long, favorite: Boolean): Boolean =
        gameDao.setFavorite(gameId, favorite) == 1

    override suspend fun delete(game: Game) = gameDao.delete(game)
}

interface SessionRepository {
    fun observeSessions(
        query: String = "",
        gamePackage: String? = null,
        status: SessionStatus? = null,
        fromTimestamp: Long? = null,
        toTimestamp: Long? = null,
    ): Flow<List<SessionListItem>>

    fun observeSamplesInRange(
        sessionId: Long,
        fromInclusive: Long = Long.MIN_VALUE,
        toInclusive: Long = Long.MAX_VALUE,
    ): Flow<List<PerformanceSample>>

    suspend fun getSessionWithRows(sessionId: Long): SessionWithGameAndSamples?

    suspend fun start(request: SessionStartRequest): Long

    suspend fun appendSamples(sessionId: Long, samples: List<SampleData>): List<Long>

    suspend fun finalize(
        sessionId: Long,
        endedAt: Long,
        dataQualitySummary: String,
        notes: String? = null,
    ): Boolean

    suspend fun recoverRunning(recoveredAt: Long, reason: String): Int

    suspend fun delete(sessionId: Long): Boolean

    suspend fun deleteEndedBefore(cutoffTimestamp: Long): Int

    suspend fun exportJson(sessionId: Long): String?

    suspend fun exportCsv(sessionId: Long): String?
}

class RoomSessionRepository(
    private val sessionDao: PerformanceSessionDao,
) : SessionRepository {
    override fun observeSessions(
        query: String,
        gamePackage: String?,
        status: SessionStatus?,
        fromTimestamp: Long?,
        toTimestamp: Long?,
    ): Flow<List<SessionListItem>> = sessionDao.observeSessions(
        query = query.trim(),
        gamePackage = gamePackage,
        status = status,
        fromTimestamp = fromTimestamp,
        toTimestamp = toTimestamp,
    )

    override fun observeSamplesInRange(
        sessionId: Long,
        fromInclusive: Long,
        toInclusive: Long,
    ): Flow<List<PerformanceSample>> {
        require(fromInclusive <= toInclusive) { "Invalid sample range" }
        return sessionDao.observeSamplesInRange(sessionId, fromInclusive, toInclusive)
    }

    override suspend fun getSessionWithRows(sessionId: Long): SessionWithGameAndSamples? =
        sessionDao.getSessionWithRows(sessionId)

    override suspend fun start(request: SessionStartRequest): Long = sessionDao.startSession(request)

    override suspend fun appendSamples(sessionId: Long, samples: List<SampleData>): List<Long> =
        sessionDao.insertSamples(sessionId, samples)

    override suspend fun finalize(
        sessionId: Long,
        endedAt: Long,
        dataQualitySummary: String,
        notes: String?,
    ): Boolean = sessionDao.finalizeSession(sessionId, endedAt, dataQualitySummary, notes)

    override suspend fun recoverRunning(recoveredAt: Long, reason: String): Int =
        sessionDao.recoverRunningSessions(recoveredAt, reason)

    override suspend fun delete(sessionId: Long): Boolean = sessionDao.deleteSession(sessionId) == 1

    override suspend fun deleteEndedBefore(cutoffTimestamp: Long): Int =
        sessionDao.deleteEndedBefore(cutoffTimestamp)

    override suspend fun exportJson(sessionId: Long): String? =
        sessionDao.getSessionWithRows(sessionId)?.let(SessionExportSerializer::toJson)

    override suspend fun exportCsv(sessionId: Long): String? =
        sessionDao.getSessionWithRows(sessionId)?.let(SessionExportSerializer::toCsv)
}
