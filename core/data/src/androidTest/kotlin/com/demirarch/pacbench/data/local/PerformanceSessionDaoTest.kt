package com.demirarch.pacbench.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.demirarch.pacbench.model.AccessMode
import com.demirarch.pacbench.model.SampleData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PerformanceSessionDaoTest {
    private lateinit var database: PacBenchDatabase
    private lateinit var sessionDao: PerformanceSessionDao

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, PacBenchDatabase::class.java).build()
        sessionDao = database.performanceSessionDao()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun startBatchInsertRangeAndFinalizeAreConsistent() = runBlocking {
        val sessionId = sessionDao.startSession(startRequest(startedAt = 1_000))
        sessionDao.insertSamples(
            sessionId,
            listOf(
                SampleData(timestamp = 1_100, fps = 60.0, cpuUsage = 40.0),
                SampleData(timestamp = 1_200, fps = 50.0, cpuUsage = 60.0),
                SampleData(timestamp = 1_300, fps = 40.0, cpuUsage = 80.0),
            ),
        )

        val range = sessionDao.observeSamplesInRange(sessionId, 1_150, 1_250).first()
        assertEquals(listOf(1_200L), range.map { it.timestamp })
        assertTrue(sessionDao.finalizeSession(sessionId, 1_400, "all sources available"))
        assertFalse(sessionDao.finalizeSession(sessionId, 1_500, "duplicate finalize"))

        val stored = sessionDao.getSessionWithRows(sessionId)
        assertNotNull(stored)
        assertEquals(SessionStatus.COMPLETED, stored?.session?.status)
        assertEquals(3, stored?.samples?.size)

        val stats = database.gameDao().getAggregateStats(requireNotNull(stored).game.id)
        assertEquals(1L, stats?.sessionCount)
        assertEquals(3L, stats?.sampleCount)
        assertEquals(300L, stats?.totalDurationMillis)
        assertEquals(50.0, stats?.averageFps ?: 0.0, 0.001)
    }

    @Test
    fun recoveryEndsAtLastPersistedSampleAndMarksInterrupted() = runBlocking {
        val sessionId = sessionDao.startSession(startRequest(startedAt = 2_000))
        sessionDao.insertSamples(sessionId, listOf(SampleData(timestamp = 2_100, fps = 60.0)))

        assertEquals(1, sessionDao.recoverRunningSessions(3_000, "process restarted"))

        val recovered = sessionDao.getSession(sessionId)
        assertEquals(SessionStatus.INTERRUPTED, recovered?.status)
        assertEquals(2_100L, recovered?.endedAt)
        assertEquals("process restarted", recovered?.dataQualitySummary)
    }

    @Test
    fun invalidBatchAndEarlyFinalizeLeaveRunningSessionUnchanged() = runBlocking {
        val sessionId = sessionDao.startSession(startRequest(startedAt = 4_000))
        sessionDao.insertSamples(sessionId, listOf(SampleData(timestamp = 4_200, fps = 60.0)))

        expectIllegalArgument {
            sessionDao.insertSamples(
                sessionId,
                listOf(
                    SampleData(timestamp = 4_300, fps = 59.0),
                    SampleData(timestamp = 4_400, fps = Double.NaN),
                ),
            )
        }
        expectIllegalArgument {
            sessionDao.finalizeSession(sessionId, 4_100, "ended too early")
        }

        val stored = requireNotNull(sessionDao.getSessionWithRows(sessionId))
        assertEquals(SessionStatus.RUNNING, stored.session.status)
        assertEquals(listOf(4_200L), stored.samples.map { it.timestamp })
    }

    private suspend fun expectIllegalArgument(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    private fun startRequest(startedAt: Long) = SessionStartRequest(
        packageName = "com.example.game",
        displayName = "Example Game",
        versionName = "1.0",
        versionCode = 1,
        startedAt = startedAt,
        accessMode = AccessMode.NORMAL,
        deviceManufacturer = "Example Corp",
        deviceModel = "Device 1",
        androidVersion = "13",
        appVersion = "0.1.0",
    )
}
