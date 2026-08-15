package com.demirarch.pacbench.access

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Process
import com.demirarch.pacbench.data.settings.SettingsRepository
import com.demirarch.pacbench.data.repository.GameRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

data class DetectedGame(
    val packageName: String,
    val displayName: String,
    val appName: String = displayName,
    val versionName: String?,
    val versionCode: Long?,
)

sealed interface ForegroundGameDetectionResult {
    data class Found(val game: DetectedGame) : ForegroundGameDetectionResult
    data object UsageAccessRequired : ForegroundGameDetectionResult
    data object TimedOut : ForegroundGameDetectionResult
    data class Unavailable(val reason: String) : ForegroundGameDetectionResult
}

/** Detection only runs while [detect] is being called by an explicit user action. */
@Singleton
class ForegroundGameDetector @Inject constructor(
    @ApplicationContext context: Context,
    private val settingsRepository: SettingsRepository,
    private val gameRepository: GameRepository,
) {
    private val appContext = context.applicationContext
    private val usageStatsManager = appContext.getSystemService(UsageStatsManager::class.java)
    private val appOpsManager = appContext.getSystemService(AppOpsManager::class.java)
    private val packageManager = appContext.packageManager
    private var lastForegroundEventAt = 0L
    private var lastKnownForegroundPackage: String? = null

    suspend fun detect(
        knownGamePackages: Set<String> = emptySet(),
        timeoutMillis: Long? = null,
    ): ForegroundGameDetectionResult {
        if (!hasUsageAccess()) return ForegroundGameDetectionResult.UsageAccessRequired
        val manager = usageStatsManager
            ?: return ForegroundGameDetectionResult.Unavailable("UsageStatsManager is unavailable")
        val configuredTimeout = timeoutMillis
            ?: settingsRepository.settings.first().autoDetectionTimeoutMillis
        val monitoredPackages = knownGamePackages.ifEmpty {
            gameRepository.observeGames().first()
                .filter { it.autoMonitoring }
                .mapTo(mutableSetOf()) { it.packageName }
        }
        if (monitoredPackages.isEmpty()) {
            return ForegroundGameDetectionResult.Unavailable("No games have automatic monitoring enabled")
        }

        val found = withTimeoutOrNull(configuredTimeout) {
            while (true) {
                foregroundPackage(manager, configuredTimeout)?.let { packageName ->
                    resolveGame(packageName, monitoredPackages)?.let { return@withTimeoutOrNull it }
                }
                delay(POLL_INTERVAL_MILLIS)
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }
        return found?.let(ForegroundGameDetectionResult::Found)
            ?: ForegroundGameDetectionResult.TimedOut
    }

    fun hasUsageAccess(): Boolean {
        val manager = appOpsManager ?: return false
        return runCatching {
            manager.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                appContext.packageName,
            ) == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
    }

    fun currentForegroundPackage(): String? {
        if (!hasUsageAccess()) return null
        return usageStatsManager?.let { foregroundPackage(it, MIN_EVENT_LOOKBACK_MILLIS) }
    }

    @Synchronized
    private fun foregroundPackage(manager: UsageStatsManager, timeoutMillis: Long): String? {
        val end = System.currentTimeMillis()
        val begin = if (lastForegroundEventAt == 0L) {
            end - maxOf(INITIAL_EVENT_LOOKBACK_MILLIS, timeoutMillis)
        } else {
            maxOf(lastForegroundEventAt - 1L, end - maxOf(MIN_EVENT_LOOKBACK_MILLIS, timeoutMillis))
        }
        val events = runCatching { manager.queryEvents(begin, end) }.getOrNull()
            ?: return lastKnownForegroundPackage
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val packageName = event.packageName?.takeIf(String::isNotBlank) ?: continue
            if (event.timeStamp < lastForegroundEventAt) continue
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED,
                UsageEvents.Event.MOVE_TO_FOREGROUND,
                -> lastKnownForegroundPackage = packageName
                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    if (lastKnownForegroundPackage == packageName) lastKnownForegroundPackage = null
                }
            }
            lastForegroundEventAt = maxOf(lastForegroundEventAt, event.timeStamp)
        }
        return lastKnownForegroundPackage
    }

    private fun resolveGame(packageName: String, knownGamePackages: Set<String>): DetectedGame? {
        if (packageName == appContext.packageName) return null
        return runCatching {
            val applicationInfo = packageManager.getApplicationInfo(
                packageName,
                PackageManager.ApplicationInfoFlags.of(0),
            )
            if (packageName !in knownGamePackages) {
                return null
            }
            val packageInfo = packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(0),
            )
            DetectedGame(
                packageName = packageName,
                displayName = packageManager.getApplicationLabel(applicationInfo).toString()
                    .takeIf(String::isNotBlank) ?: packageName,
                versionName = packageInfo.versionName,
                versionCode = packageInfo.longVersionCode,
            )
        }.getOrNull()
    }

    private companion object {
        const val POLL_INTERVAL_MILLIS = 500L
        const val MIN_EVENT_LOOKBACK_MILLIS = 60_000L
        const val INITIAL_EVENT_LOOKBACK_MILLIS = 24L * 60 * 60 * 1_000
    }
}
