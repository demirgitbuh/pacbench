package com.demirarch.pacbench.access

import com.demirarch.pacbench.data.repository.SessionRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class InterruptedSessionRecovery @Inject constructor(
    private val sessionRepository: SessionRepository,
) {
    private val mutex = Mutex()

    suspend fun recover(recoveredAt: Long, reason: String): Int = mutex.withLock {
        require(reason.isNotBlank()) { "Recovery reason must not be blank" }
        sessionRepository.recoverRunning(recoveredAt, reason)
    }
}
