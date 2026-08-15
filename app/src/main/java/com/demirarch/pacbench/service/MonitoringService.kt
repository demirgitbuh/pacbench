package com.demirarch.pacbench.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.demirarch.pacbench.BuildConfig
import com.demirarch.pacbench.R
import com.demirarch.pacbench.access.DetectedGame
import com.demirarch.pacbench.access.ForegroundGameDetectionResult
import com.demirarch.pacbench.access.ForegroundGameDetector
import com.demirarch.pacbench.access.InterruptedSessionRecovery
import com.demirarch.pacbench.access.MetricEngineFactory
import com.demirarch.pacbench.access.MetricEngineHandle
import com.demirarch.pacbench.data.local.SessionStartRequest
import com.demirarch.pacbench.data.repository.HudPresetRepository
import com.demirarch.pacbench.data.repository.SessionRepository
import com.demirarch.pacbench.data.settings.PacBenchSettings
import com.demirarch.pacbench.data.settings.SettingsRepository
import com.demirarch.pacbench.model.BuiltInHudPresets
import com.demirarch.pacbench.model.HudPreset
import com.demirarch.pacbench.model.MetricSnapshot
import com.demirarch.pacbench.model.MetricStatus
import com.demirarch.pacbench.model.SampleData
import com.demirarch.pacbench.model.toSampleData
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@AndroidEntryPoint
class MonitoringService : Service() {
    @Inject lateinit var sessionRepository: SessionRepository
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var hudPresetRepository: HudPresetRepository
    @Inject lateinit var metricEngineFactory: MetricEngineFactory
    @Inject lateinit var foregroundGameDetector: ForegroundGameDetector
    @Inject lateinit var interruptedSessionRecovery: InterruptedSessionRecovery
    @Inject lateinit var overlayController: OverlayController
    @Inject lateinit var stateStore: MonitoringStateStore

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lifecycleMutex = Mutex()
    private lateinit var notificationManager: NotificationManager

    @Volatile
    private var activeSession: ActiveSession? = null

    @Volatile
    private var startupJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
        serviceScope.launch {
            overlayController.state.collect {
                activeSession?.let(::publishState)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val commandIntent = intent ?: Intent().setAction(ACTION_START_PACKAGE)
        when (commandIntent.action ?: ACTION_START_PACKAGE) {
            ACTION_STOP -> {
                startupJob?.cancel()
                serviceScope.launch {
                    lifecycleMutex.withLock {
                        stopActiveSession(completed = true, reason = "Stopped by user")
                        stateStore.update(MonitoringState.Idle)
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelfResult(startId)
                    }
                }
            }
            ACTION_TOGGLE_HUD -> serviceScope.launch {
                lifecycleMutex.withLock { toggleHud() }
            }
            ACTION_START_AUTOMATIC -> {
                startForeground(NOTIFICATION_ID, buildNotification("Looking for a foreground game"))
                launchStartup {
                    lifecycleMutex.withLock { startAutomatic(commandIntent, startId) }
                }
            }
            ACTION_START_PACKAGE -> {
                startForeground(NOTIFICATION_ID, buildNotification("Preparing performance monitoring"))
                launchStartup {
                    lifecycleMutex.withLock { startExplicit(commandIntent, startId) }
                }
            }
            else -> {
                startForeground(NOTIFICATION_ID, buildNotification("Unsupported monitoring action"))
                launchStartup { failAndStop("Unsupported monitoring action", startId) }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        overlayController.hide()
        val session = activeSession
        if (session == null) {
            serviceScope.cancel()
        } else {
            serviceScope.launch {
                lifecycleMutex.withLock {
                    stopActiveSession(
                        completed = false,
                        reason = "Monitoring service was destroyed before a clean stop",
                    )
                }
                serviceScope.cancel()
            }
        }
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        serviceScope.launch {
            lifecycleMutex.withLock {
                stopActiveSession(
                    completed = false,
                    reason = "Foreground service runtime expired",
                )
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelfResult(startId)
            }
        }
    }

    private suspend fun startExplicit(intent: Intent, startId: Int) {
        val targetPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE)?.trim().orEmpty()
        stateStore.update(MonitoringState.Starting(targetPackage.takeIf(String::isNotBlank), false))
        val game = resolveInstalledGame(targetPackage, intent.getStringExtra(EXTRA_TARGET_LABEL))
        if (game == null) {
            failAndStop("Target package is missing, invalid, or not visible", startId)
            return
        }
        beginMonitoring(game, intent, startId, automatic = false)
    }

    private suspend fun startAutomatic(intent: Intent, startId: Int) {
        stateStore.update(MonitoringState.Starting(null, true))
        when (val result = foregroundGameDetector.detect()) {
            is ForegroundGameDetectionResult.Found -> beginMonitoring(result.game, intent, startId, automatic = true)
            ForegroundGameDetectionResult.UsageAccessRequired ->
                failAndStop("Usage access is required for automatic game detection", startId)
            ForegroundGameDetectionResult.TimedOut ->
                failAndStop("No foreground game was detected before the configured timeout", startId)
            is ForegroundGameDetectionResult.Unavailable -> failAndStop(result.reason, startId)
        }
    }

    private suspend fun beginMonitoring(
        game: DetectedGame,
        intent: Intent,
        startId: Int,
        automatic: Boolean,
    ) {
        var unownedEngineHandle: MetricEngineHandle? = null
        try {
            stopActiveSession(completed = true, reason = "Target changed")
            withContext(Dispatchers.IO) {
                interruptedSessionRecovery.recover(
                    recoveredAt = System.currentTimeMillis(),
                    reason = "Recovered when a new monitoring session started",
                )
            }

            val settings = settingsRepository.settings.first()
            val preset = resolvePreset(intent.getStringExtra(EXTRA_HUD_PRESET_ID))
            val engineHandle = metricEngineFactory.create(
                targetPackage = game.packageName,
                pingEndpoint = settings.pingEndpoint,
                explicitSurfaceLayer = intent.getStringExtra(EXTRA_SURFACE_LAYER),
            )
            unownedEngineHandle = engineHandle
            val firstSnapshot = engineHandle.sample(settings.enabledMetrics)
            val startedAt = firstSnapshot.timestampMillis
            val sessionId = withContext(Dispatchers.IO) {
                sessionRepository.start(
                    SessionStartRequest(
                        packageName = game.packageName,
                        displayName = game.displayName,
                        versionName = game.versionName,
                        versionCode = game.versionCode,
                        startedAt = startedAt,
                        accessMode = firstSnapshot.accessMode,
                        deviceManufacturer = Build.MANUFACTURER,
                        deviceModel = Build.MODEL,
                        androidVersion = Build.VERSION.RELEASE,
                        appVersion = BuildConfig.VERSION_NAME,
                    ),
                )
            }

            val droppedByBuffer = AtomicLong()
            val channel = Channel<SampleData>(
                capacity = SAMPLE_CHANNEL_CAPACITY,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
                onUndeliveredElement = { droppedByBuffer.incrementAndGet() },
            )
            val session = ActiveSession(
                sessionId = sessionId,
                startedAt = startedAt,
                game = game,
                settings = settings,
                preset = preset,
                engineHandle = engineHandle,
                channel = channel,
                droppedByBuffer = droppedByBuffer,
                hudRequested = intent.getBooleanExtra(EXTRA_SHOW_HUD, true),
                automatic = automatic,
            )
            activeSession = session
            unownedEngineHandle = null
            session.writerJob = serviceScope.launch(Dispatchers.IO) { writeBatches(session) }
            acceptSnapshot(session, firstSnapshot)
            if (session.hudRequested) overlayController.show(preset, firstSnapshot, locked = true)
            session.samplerJob = serviceScope.launch { sampleLoop(session) }
            publishState(session)
            updateNotification(session)
        } catch (error: CancellationException) {
            unownedEngineHandle?.close()
            if (activeSession == null) {
                withContext(NonCancellable + Dispatchers.IO) {
                    interruptedSessionRecovery.recover(
                        System.currentTimeMillis(),
                        "Monitoring startup was cancelled before ownership was established",
                    )
                }
            }
            throw error
        } catch (error: Exception) {
            unownedEngineHandle?.close()
            val active = activeSession
            active?.let {
                stopActiveSession(
                    completed = false,
                    reason = error.message ?: "Monitoring startup failed",
                )
            }
            if (active == null) {
                withContext(Dispatchers.IO) {
                    interruptedSessionRecovery.recover(
                        System.currentTimeMillis(),
                        error.message ?: "Monitoring startup failed",
                    )
                }
            }
            failAndStop(error.message ?: "Monitoring could not be started", startId)
        }
    }

    private suspend fun sampleLoop(session: ActiveSession) {
        var leftTargetAt: Long? = null
        while (serviceScope.isActive && activeSession === session) {
            delay(session.settings.samplingIntervalMillis)
            if (session.automatic) {
                val foregroundPackage = foregroundGameDetector.currentForegroundPackage()
                leftTargetAt = if (foregroundPackage == session.game.packageName) {
                    null
                } else {
                    leftTargetAt ?: SystemClock.elapsedRealtime()
                }
                val awayFor = leftTargetAt?.let { SystemClock.elapsedRealtime() - it } ?: 0L
                if (awayFor >= session.settings.autoDetectionTimeoutMillis) {
                    serviceScope.launch {
                        lifecycleMutex.withLock {
                            if (activeSession === session) {
                                stopActiveSession(completed = true, reason = "Target left foreground")
                                stateStore.update(MonitoringState.Idle)
                                stopForeground(STOP_FOREGROUND_REMOVE)
                                stopSelf()
                            }
                        }
                    }
                    return
                }
            }
            try {
                acceptSnapshot(session, session.engineHandle.sample(session.settings.enabledMetrics))
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                session.samplingFailures.incrementAndGet()
            }
        }
    }

    private fun acceptSnapshot(session: ActiveSession, snapshot: MetricSnapshot) {
        session.latestSnapshot = snapshot
        session.lastTimestamp.accumulateAndGet(snapshot.timestampMillis) { current, value ->
            maxOf(current, value)
        }
        snapshot.readings.forEach { reading ->
            if (reading.status != MetricStatus.AVAILABLE) {
                session.unavailableReadings
                    .computeIfAbsent(reading.status) { AtomicLong() }
                    .incrementAndGet()
            }
        }
        if (session.channel.trySend(snapshot.toSampleData()).isSuccess) {
            session.acceptedSamples.incrementAndGet()
        } else {
            session.droppedByBuffer.incrementAndGet()
        }
        if (session.hudRequested) overlayController.update(snapshot)
        publishState(session)
    }

    private suspend fun writeBatches(session: ActiveSession) {
        while (true) {
            val firstResult = session.channel.receiveCatching()
            val first = firstResult.getOrNull() ?: break
            val batch = ArrayList<SampleData>(MAX_BATCH_SIZE)
            batch += first
            val deadline = SystemClock.elapsedRealtime() + BATCH_WINDOW_MILLIS
            while (batch.size < MAX_BATCH_SIZE) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0L) break
                val nextResult = withTimeoutOrNull(remaining) { session.channel.receiveCatching() } ?: break
                val next = nextResult.getOrNull() ?: break
                batch += next
            }
            persistBatch(session, batch)
        }
    }

    private suspend fun persistBatch(session: ActiveSession, batch: List<SampleData>) {
        var lastError: Throwable? = null
        repeat(BATCH_WRITE_ATTEMPTS) { attempt ->
            try {
                sessionRepository.appendSamples(session.sessionId, batch)
                session.persistedSamples.addAndGet(batch.size.toLong())
                return
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                lastError = error
                if (attempt + 1 < BATCH_WRITE_ATTEMPTS) delay(BATCH_RETRY_DELAY_MILLIS)
            }
        }
        session.lostOnWrite.addAndGet(batch.size.toLong())
        session.lastWriteFailure = lastError?.message ?: "Room batch write failed"
    }

    private suspend fun stopActiveSession(completed: Boolean, reason: String) {
        val session = activeSession ?: return
        activeSession = null
        session.samplerJob?.cancelAndJoin()
        session.channel.close()
        listOfNotNull(session.writerJob).joinAll()
        session.engineHandle.close()
        overlayController.hide()

        val endedAt = maxOf(
            session.startedAt,
            session.lastTimestamp.get(),
            System.currentTimeMillis(),
        )
        withContext(Dispatchers.IO) {
            if (completed) {
                val finalized = runCatching {
                    sessionRepository.finalize(
                        sessionId = session.sessionId,
                        endedAt = endedAt,
                        dataQualitySummary = qualitySummary(session),
                    )
                }.getOrDefault(false)
                if (!finalized) {
                    interruptedSessionRecovery.recover(
                        endedAt,
                        "Session finalization failed; ${qualitySummary(session)}",
                    )
                }
            } else {
                interruptedSessionRecovery.recover(
                    endedAt,
                    "$reason; ${qualitySummary(session)}",
                )
            }
        }
    }

    private fun toggleHud() {
        val session = activeSession ?: return
        session.hudRequested = !session.hudRequested
        val snapshot = session.latestSnapshot
        if (session.hudRequested && snapshot != null) {
            overlayController.show(session.preset, snapshot, locked = true)
        } else {
            overlayController.hide()
        }
        publishState(session)
        updateNotification(session)
    }

    private fun publishState(session: ActiveSession) {
        val snapshot = session.latestSnapshot ?: return
        stateStore.update(
            MonitoringState.Running(
                sessionId = session.sessionId,
                targetPackage = session.game.packageName,
                targetLabel = session.game.displayName,
                latestSnapshot = snapshot,
                acceptedSamples = session.acceptedSamples.get(),
                droppedSamples = session.droppedByBuffer.get() + session.lostOnWrite.get(),
                hudVisible = session.hudRequested && overlayController.state.value.visible,
            ),
        )
    }

    private fun qualitySummary(session: ActiveSession): String = buildString {
        append("accepted=").append(session.acceptedSamples.get())
        append(", persisted=").append(session.persistedSamples.get())
        append(", buffer_dropped=").append(session.droppedByBuffer.get())
        append(", write_lost=").append(session.lostOnWrite.get())
        append(", sampling_failures=").append(session.samplingFailures.get())
        val unavailable = session.unavailableReadings.entries
            .filter { it.value.get() > 0L }
            .sortedBy { it.key.name }
        if (unavailable.isNotEmpty()) {
            append(", unavailable_readings=")
            unavailable.joinTo(this, "|") { (status, count) -> "${status.name}:${count.get()}" }
        }
        session.lastWriteFailure?.let { append(", last_write_error=").append(it.take(160)) }
    }

    private suspend fun resolvePreset(id: String?): HudPreset {
        val requested = id?.takeIf(String::isNotBlank)
        return requested?.let { runCatching { hudPresetRepository.get(it) }.getOrNull() }
            ?: BuiltInHudPresets.all.first { it.id == DEFAULT_HUD_PRESET_ID }
    }

    private fun resolveInstalledGame(packageName: String, suppliedLabel: String?): DetectedGame? {
        if (packageName.isBlank() || packageName == this.packageName) return null
        return runCatching {
            val applicationInfo = packageManager.getApplicationInfo(
                packageName,
                PackageManager.ApplicationInfoFlags.of(0),
            )
            val packageInfo = packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(0),
            )
            DetectedGame(
                packageName = packageName,
                displayName = suppliedLabel?.trim()?.takeIf(String::isNotBlank)
                    ?: packageManager.getApplicationLabel(applicationInfo).toString()
                        .takeIf(String::isNotBlank)
                    ?: packageName,
                versionName = packageInfo.versionName,
                versionCode = packageInfo.longVersionCode,
            )
        }.getOrNull()
    }

    private suspend fun failAndStop(reason: String, startId: Int) {
        stateStore.update(MonitoringState.Failed(reason))
        notificationManager.notify(NOTIFICATION_ID, buildNotification(reason))
        delay(FAILURE_NOTIFICATION_MILLIS)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelfResult(startId)
    }

    private fun createNotificationChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.monitoring_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.monitoring_channel_description)
                setShowBadge(false)
            },
        )
    }

    private fun launchStartup(block: suspend () -> Unit) {
        startupJob?.cancel()
        val job = serviceScope.launch { block() }
        startupJob = job
        job.invokeOnCompletion {
            if (startupJob === job) startupJob = null
        }
    }

    private fun updateNotification(session: ActiveSession) {
        val count = session.acceptedSamples.get()
        notificationManager.notify(
            NOTIFICATION_ID,
            buildNotification("${session.game.displayName} - $count real samples"),
        )
    }

    private fun buildNotification(content: String): Notification {
        val toggleIntent = PendingIntent.getService(
            this,
            REQUEST_TOGGLE_HUD,
            Intent(this, MonitoringService::class.java).setAction(ACTION_TOGGLE_HUD),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, MonitoringService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this,
                REQUEST_OPEN_APP,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(content)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(Notification.Action.Builder(0, getString(R.string.toggle_hud), toggleIntent).build())
            .addAction(Notification.Action.Builder(0, getString(R.string.stop), stopIntent).build())
            .build()
    }

    private class ActiveSession(
        val sessionId: Long,
        val startedAt: Long,
        val game: DetectedGame,
        val settings: PacBenchSettings,
        val preset: HudPreset,
        val engineHandle: MetricEngineHandle,
        val channel: Channel<SampleData>,
        val droppedByBuffer: AtomicLong,
        @Volatile var hudRequested: Boolean,
        val automatic: Boolean,
    ) {
        val acceptedSamples = AtomicLong()
        val persistedSamples = AtomicLong()
        val lostOnWrite = AtomicLong()
        val samplingFailures = AtomicLong()
        val lastTimestamp = AtomicLong(startedAt)
        val unavailableReadings = ConcurrentHashMap<MetricStatus, AtomicLong>()
        @Volatile var latestSnapshot: MetricSnapshot? = null
        @Volatile var lastWriteFailure: String? = null
        var samplerJob: Job? = null
        var writerJob: Job? = null
    }

    companion object {
        const val ACTION_START_PACKAGE = "com.demirarch.pacbench.action.START_PACKAGE_MONITORING"
        const val ACTION_START_AUTOMATIC = "com.demirarch.pacbench.action.START_AUTOMATIC_MONITORING"
        const val ACTION_TOGGLE_HUD = "com.demirarch.pacbench.action.TOGGLE_HUD"
        const val ACTION_STOP = "com.demirarch.pacbench.action.STOP_MONITORING"

        const val EXTRA_TARGET_PACKAGE = "com.demirarch.pacbench.extra.TARGET_PACKAGE"
        const val EXTRA_TARGET_LABEL = "com.demirarch.pacbench.extra.TARGET_LABEL"
        const val EXTRA_SURFACE_LAYER = "com.demirarch.pacbench.extra.SURFACE_LAYER"
        const val EXTRA_SHOW_HUD = "com.demirarch.pacbench.extra.SHOW_HUD"
        const val EXTRA_HUD_PRESET_ID = "com.demirarch.pacbench.extra.HUD_PRESET_ID"

        fun startPackageIntent(
            context: Context,
            packageName: String,
            displayName: String? = null,
            surfaceLayer: String? = null,
            showHud: Boolean = true,
            hudPresetId: String? = null,
        ): Intent = Intent(context, MonitoringService::class.java)
            .setAction(ACTION_START_PACKAGE)
            .putExtra(EXTRA_TARGET_PACKAGE, packageName)
            .putExtra(EXTRA_TARGET_LABEL, displayName)
            .putExtra(EXTRA_SURFACE_LAYER, surfaceLayer)
            .putExtra(EXTRA_SHOW_HUD, showHud)
            .putExtra(EXTRA_HUD_PRESET_ID, hudPresetId)

        fun startAutomaticIntent(
            context: Context,
            showHud: Boolean = true,
            hudPresetId: String? = null,
        ): Intent = Intent(context, MonitoringService::class.java)
            .setAction(ACTION_START_AUTOMATIC)
            .putExtra(EXTRA_SHOW_HUD, showHud)
            .putExtra(EXTRA_HUD_PRESET_ID, hudPresetId)

        fun startForPackage(
            context: Context,
            packageName: String,
            displayName: String? = null,
            showHud: Boolean = true,
        ) {
            ContextCompat.startForegroundService(
                context,
                startPackageIntent(context, packageName, displayName, showHud = showHud),
            )
        }

        fun startAutomaticDetection(context: Context, showHud: Boolean = true) {
            ContextCompat.startForegroundService(context, startAutomaticIntent(context, showHud))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, MonitoringService::class.java).setAction(ACTION_STOP))
        }

        private const val NOTIFICATION_CHANNEL_ID = "pacbench_monitoring"
        private const val NOTIFICATION_ID = 7_201
        private const val REQUEST_TOGGLE_HUD = 7_202
        private const val REQUEST_STOP = 7_203
        private const val REQUEST_OPEN_APP = 7_204
        private const val SAMPLE_CHANNEL_CAPACITY = 120
        private const val MAX_BATCH_SIZE = 32
        private const val BATCH_WINDOW_MILLIS = 2_000L
        private const val BATCH_WRITE_ATTEMPTS = 2
        private const val BATCH_RETRY_DELAY_MILLIS = 250L
        private const val FAILURE_NOTIFICATION_MILLIS = 1_500L
        private const val DEFAULT_HUD_PRESET_ID = "benchmark"
    }
}
