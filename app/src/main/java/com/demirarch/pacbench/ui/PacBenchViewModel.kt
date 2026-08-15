package com.demirarch.pacbench.ui

import android.Manifest
import android.app.AppOpsManager
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.demirarch.pacbench.data.repository.GameRepository
import com.demirarch.pacbench.data.local.Game
import com.demirarch.pacbench.data.local.GameAggregateStats
import com.demirarch.pacbench.data.local.GameWithSessions
import com.demirarch.pacbench.data.local.PerformanceSample
import com.demirarch.pacbench.data.local.SessionStatus
import com.demirarch.pacbench.data.local.SessionWithGameAndSamples
import com.demirarch.pacbench.data.local.toSampleData
import com.demirarch.pacbench.data.repository.HudPresetRepository
import com.demirarch.pacbench.data.repository.SessionRepository
import com.demirarch.pacbench.data.settings.GraphMode
import com.demirarch.pacbench.data.settings.PacBenchSettings
import com.demirarch.pacbench.data.settings.SettingsRepository
import com.demirarch.pacbench.data.settings.ThemeMode
import com.demirarch.pacbench.metrics.MetricEngine
import com.demirarch.pacbench.metrics.NormalMetricProvider
import com.demirarch.pacbench.metrics.RootCommandExecutor
import com.demirarch.pacbench.metrics.ShizukuMetricProvider
import com.demirarch.pacbench.metrics.ShizukuPermissionState
import com.demirarch.pacbench.metrics.TcpLatencyEndpoint
import com.demirarch.pacbench.export.ExportCoordinator
import com.demirarch.pacbench.export.SessionExportFormat
import com.demirarch.pacbench.model.AccessMode
import com.demirarch.pacbench.model.BuiltInHudPresets
import com.demirarch.pacbench.model.HudPreset
import com.demirarch.pacbench.model.HudWidget
import com.demirarch.pacbench.model.HudWidgetType
import com.demirarch.pacbench.model.MetricCapability
import com.demirarch.pacbench.model.MetricId
import com.demirarch.pacbench.model.MetricSnapshot
import com.demirarch.pacbench.model.SampleData
import com.demirarch.pacbench.service.MonitoringService
import com.demirarch.pacbench.service.MonitoringState
import com.demirarch.pacbench.service.MonitoringStateStore
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlin.math.round
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class InstalledGame(
    val packageName: String,
    val displayName: String,
    val versionName: String?,
    val versionCode: Long?,
)

data class GameDetailUi(
    val gameWithSessions: GameWithSessions,
    val aggregate: GameAggregateStats?,
)

enum class ReportRange(val label: String) {
    ALL("All time"),
    DAY("24 hours"),
    WEEK("7 days"),
    MONTH("30 days"),
}

data class SessionDetailUi(
    val rows: SessionWithGameAndSamples,
) {
    val samples: List<SampleData> = rows.samples.map(PerformanceSample::toSampleData)
}

data class RecordingUiState(
    val sessionId: Long,
    val packageName: String,
    val gameName: String,
    val accessMode: AccessMode,
    val startedAt: Long,
    val sampleCount: Int = 0,
    val latest: MetricSnapshot? = null,
    val active: Boolean = true,
    val error: String? = null,
)

data class AccessUiState(
    val notificationDeclared: Boolean = false,
    val notificationGranted: Boolean = false,
    val overlayDeclared: Boolean = false,
    val overlayGranted: Boolean = false,
    val usageAccessGranted: Boolean = false,
    val shizukuState: ShizukuPermissionState = ShizukuPermissionState.BINDER_UNAVAILABLE,
    val rootStatus: String = "Not checked",
    val normalCapabilities: List<MetricCapability> = emptyList(),
    val checking: Boolean = true,
)

data class HudEditorUiState(
    val preset: HudPreset,
    val selectedWidgetId: String? = preset.widgets.maxByOrNull(HudWidget::layer)?.id,
    val snapToGrid: Boolean = true,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
) {
    val selectedWidget: HudWidget?
        get() = preset.widgets.firstOrNull { it.id == selectedWidgetId }
}

@HiltViewModel
class PacBenchViewModel @Inject constructor(
    application: Application,
    private val gameRepository: GameRepository,
    private val sessionRepository: SessionRepository,
    private val hudRepository: HudPresetRepository,
    private val settingsRepository: SettingsRepository,
    private val monitoringStateStore: MonitoringStateStore,
    private val exportCoordinator: ExportCoordinator,
) : AndroidViewModel(application) {
    private val app = application.applicationContext
    private val accessShizuku = ShizukuMetricProvider(app)
    private val accessRoot = RootCommandExecutor()

    val settings: StateFlow<PacBenchSettings> = settingsRepository.settings.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        PacBenchSettings(),
    )

    val gameQuery = MutableStateFlow("")
    val favoriteGamesOnly = MutableStateFlow(false)
    val games = combine(gameQuery, favoriteGamesOnly) { query, favorites -> query to favorites }
        .flatMapLatest { (query, favorites) -> gameRepository.observeGames(query, favorites) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val gameStats = gameQuery
        .flatMapLatest(gameRepository::observeAggregateStats)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val reportQuery = MutableStateFlow("")
    val reportStatus = MutableStateFlow<SessionStatus?>(null)
    val reportRange = MutableStateFlow(ReportRange.ALL)
    val sessions = combine(reportQuery, reportStatus, reportRange) { query, status, range ->
        Triple(query, status, range)
    }.flatMapLatest { (query, status, range) ->
        val from = when (range) {
            ReportRange.ALL -> null
            ReportRange.DAY -> System.currentTimeMillis() - 24L * 60 * 60 * 1_000
            ReportRange.WEEK -> System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1_000
            ReportRange.MONTH -> System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1_000
        }
        sessionRepository.observeSessions(query = query, status = status, fromTimestamp = from)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val presetQuery = MutableStateFlow("")
    val presets = presetQuery
        .flatMapLatest(hudRepository::observePresets)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val installedGames = MutableStateFlow<List<InstalledGame>>(emptyList())
    val discoveringGames = MutableStateFlow(false)
    val selectedGame = MutableStateFlow<GameDetailUi?>(null)
    val selectedSession = MutableStateFlow<SessionDetailUi?>(null)
    val comparisonIds = MutableStateFlow<Set<Long>>(emptySet())
    val comparisonSessions = MutableStateFlow<List<SessionDetailUi>>(emptyList())
    val recording = MutableStateFlow<RecordingUiState?>(null)
    val activeHudPresetId = MutableStateFlow(
        app.getSharedPreferences(PREFERENCES, 0).getString(ACTIVE_HUD_PRESET, "benchmark") ?: "benchmark",
    )
    val access = MutableStateFlow(AccessUiState())
    val message = MutableStateFlow<String?>(null)
    val editor = MutableStateFlow<HudEditorUiState?>(null)
    val onboardingVisible = MutableStateFlow(
        !app.getSharedPreferences(PREFERENCES, 0).getBoolean(ONBOARDING_COMPLETE, false),
    )

    private var initialEditorPreset: HudPreset? = null
    private val undo = ArrayDeque<HudPreset>()
    private val redo = ArrayDeque<HudPreset>()
    private var transactionStart: HudPreset? = null

    init {
        viewModelScope.launch {
            monitoringStateStore.state.collect { state ->
                recording.value = when (state) {
                    MonitoringState.Idle -> recording.value?.takeIf { it.active }?.copy(active = false)
                    is MonitoringState.Starting -> RecordingUiState(
                        sessionId = 0L,
                        packageName = state.targetPackage.orEmpty(),
                        gameName = state.targetPackage ?: "Automatic detection",
                        accessMode = AccessMode.NORMAL,
                        startedAt = System.currentTimeMillis(),
                    )
                    is MonitoringState.Running -> RecordingUiState(
                        sessionId = state.sessionId,
                        packageName = state.targetPackage,
                        gameName = state.targetLabel,
                        accessMode = state.latestSnapshot.accessMode,
                        startedAt = state.latestSnapshot.timestampMillis,
                        sampleCount = state.acceptedSamples.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        latest = state.latestSnapshot,
                    )
                    is MonitoringState.Failed -> recording.value?.copy(active = false, error = state.reason)
                        ?: RecordingUiState(
                            sessionId = 0L,
                            packageName = "",
                            gameName = "Monitoring",
                            accessMode = AccessMode.NORMAL,
                            startedAt = System.currentTimeMillis(),
                            active = false,
                            error = state.reason,
                        )
                }
            }
        }
        viewModelScope.launch {
            runCatching { hudRepository.seedBuiltIns(System.currentTimeMillis()) }
                .onFailure { showError("HUD presets", it) }
        }
        refreshAccess()
    }

    fun clearMessage() {
        message.value = null
    }

    fun completeOnboarding() {
        app.getSharedPreferences(PREFERENCES, 0).edit().putBoolean(ONBOARDING_COMPLETE, true).apply()
        onboardingVisible.value = false
    }

    fun refreshAccess() {
        viewModelScope.launch {
            val notificationDeclared = isPermissionDeclared(Manifest.permission.POST_NOTIFICATIONS)
            val overlayDeclared = isPermissionDeclared(Manifest.permission.SYSTEM_ALERT_WINDOW)
            access.value = access.value.copy(
                notificationDeclared = notificationDeclared,
                notificationGranted = app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED,
                overlayDeclared = overlayDeclared,
                overlayGranted = Settings.canDrawOverlays(app),
                usageAccessGranted = hasUsageAccess(),
                shizukuState = accessShizuku.permissionState,
                checking = true,
            )
            val endpoint = settingsRepository.settings.first().pingEndpoint
            val capabilities = runCatching {
                MetricEngine(listOf(NormalMetricProvider(app, safeEndpoint(endpoint))))
                    .capabilities()
                    .values
                    .flatten()
            }.getOrElse {
                message.value = "Capability check failed: ${it.message.orEmpty()}"
                emptyList()
            }
            access.value = access.value.copy(
                normalCapabilities = capabilities,
                shizukuState = accessShizuku.permissionState,
                checking = false,
            )
        }
    }

    fun requestShizuku() {
        val requested = accessShizuku.requestPermission()
        if (!requested) message.value = "Shizuku is not running or its binder is unavailable."
        viewModelScope.launch {
            repeat(10) {
                delay(350)
                access.value = access.value.copy(shizukuState = accessShizuku.permissionState)
                if (accessShizuku.permissionState == ShizukuPermissionState.READY) return@launch
            }
        }
    }

    fun checkRoot() {
        viewModelScope.launch {
            access.value = access.value.copy(rootStatus = "Checking")
            val availability = runCatching { accessRoot.availability() }.getOrElse {
                access.value = access.value.copy(rootStatus = "Unavailable: ${it.message.orEmpty()}")
                return@launch
            }
            access.value = access.value.copy(
                rootStatus = when {
                    availability.available -> "Ready"
                    availability.permissionDenied -> "Permission denied"
                    else -> "Unavailable: ${availability.reason ?: "no supported su executable"}"
                },
            )
        }
    }

    fun overlaySettingsIntent(): Intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${app.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun usageAccessSettingsIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun startAutomaticMonitoring() {
        if (!hasUsageAccess()) {
            message.value = "Usage access is required for automatic monitoring."
            return
        }
        runCatching { MonitoringService.startAutomaticDetection(app, showHud = true) }
            .onFailure { showError("Start automatic monitoring", it) }
    }

    fun discoverInstalledGames() {
        if (discoveringGames.value) return
        viewModelScope.launch(Dispatchers.IO) {
            discoveringGames.value = true
            installedGames.value = runCatching {
                val manager = app.packageManager
                val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                manager.queryIntentActivities(
                    launcherIntent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
                ).asSequence()
                    .map { it.activityInfo.applicationInfo }
                    .filter { it.packageName != app.packageName }
                    .distinctBy { it.packageName }
                    .map { applicationInfo ->
                        val info = manager.getPackageInfo(
                            applicationInfo.packageName,
                            PackageManager.PackageInfoFlags.of(0),
                        )
                        InstalledGame(
                            packageName = applicationInfo.packageName,
                            displayName = applicationInfo.loadLabel(manager).toString(),
                            versionName = info.versionName,
                            versionCode = info.longVersionCode,
                        )
                    }
                    .sortedBy(InstalledGame::displayName)
                    .toList()
            }.getOrElse {
                message.value = "Game discovery failed: ${it.message.orEmpty()}"
                emptyList()
            }
            discoveringGames.value = false
        }
    }

    fun addInstalledGame(game: InstalledGame) {
        saveGame(game.packageName, game.displayName, game.versionName, game.versionCode)
    }

    fun addManualGame(packageName: String, displayName: String) {
        val packageValue = packageName.trim()
        if (packageValue.isBlank()) {
            message.value = "Package name is required."
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val installed = installedGame(packageValue)
            val name = displayName.trim().ifBlank { installed?.displayName.orEmpty() }
            if (name.isBlank()) {
                message.value = "Display name is required when the package is not installed."
                return@launch
            }
            saveGameNow(packageValue, name, installed?.versionName, installed?.versionCode)
        }
    }

    private fun saveGame(
        packageName: String,
        displayName: String,
        versionName: String?,
        versionCode: Long?,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            saveGameNow(packageName, displayName, versionName, versionCode)
        }
    }

    private suspend fun saveGameNow(
        packageName: String,
        displayName: String,
        versionName: String?,
        versionCode: Long?,
    ) {
        runCatching {
            val now = System.currentTimeMillis()
            gameRepository.save(
                Game(
                    packageName = packageName,
                    displayName = displayName,
                    versionName = versionName,
                    versionCode = versionCode,
                    firstSeenAt = now,
                    lastSeenAt = now,
                ),
            )
        }.onSuccess {
            message.value = "$displayName added."
        }.onFailure { showError("Add game", it) }
    }

    fun openGame(gameId: Long) {
        viewModelScope.launch {
            selectedGame.value = runCatching {
                val rows = gameRepository.getWithSessions(gameId) ?: return@runCatching null
                GameDetailUi(rows, gameRepository.getAggregateStats(gameId))
            }.getOrElse {
                showError("Game detail", it)
                null
            }
        }
    }

    fun closeGame() {
        selectedGame.value = null
    }

    fun toggleFavorite(game: Game) {
        viewModelScope.launch {
            runCatching { gameRepository.setFavorite(game.id, !game.isFavorite) }
                .onSuccess { openGame(game.id) }
                .onFailure { showError("Favorite", it) }
        }
    }

    fun deleteGame(game: Game) {
        viewModelScope.launch {
            runCatching { gameRepository.delete(game) }
                .onSuccess {
                    selectedGame.value = null
                    message.value = "${game.displayName} removed."
                }
                .onFailure {
                    message.value = "Game cannot be removed while stored sessions reference it."
                }
        }
    }

    fun launchGame(packageName: String) {
        val intent = app.packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            message.value = "No launchable activity is visible for $packageName."
            return
        }
        runCatching { app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { showError("Launch", it) }
    }

    fun recordAndLaunch(game: Game) {
        if (recording.value?.active == true) {
            message.value = "Stop the current session before starting another."
            return
        }
        runCatching {
            ContextCompat.startForegroundService(
                app,
                MonitoringService.startPackageIntent(
                    context = app,
                    packageName = game.packageName,
                    displayName = game.displayName,
                    showHud = true,
                    hudPresetId = activeHudPresetId.value,
                ),
            )
        }.onSuccess {
            launchGame(game.packageName)
        }.onFailure { showError("Start monitoring service", it) }
    }

    fun stopRecording() {
        if (recording.value?.active != true) {
            message.value = "No sampling session is active."
            return
        }
        runCatching { MonitoringService.stop(app) }
            .onFailure { showError("Stop monitoring service", it) }
    }

    fun dismissFinishedRecording() {
        if (recording.value?.active == false) recording.value = null
    }

    fun openSession(sessionId: Long) {
        viewModelScope.launch {
            selectedSession.value = runCatching {
                sessionRepository.getSessionWithRows(sessionId)?.let(::SessionDetailUi)
            }.getOrElse {
                showError("Session detail", it)
                null
            }
        }
    }

    fun closeSession() {
        selectedSession.value = null
    }

    fun deleteSession(sessionId: Long) {
        viewModelScope.launch {
            runCatching { sessionRepository.delete(sessionId) }
                .onSuccess {
                    selectedSession.value = null
                    comparisonIds.value = comparisonIds.value - sessionId
                    loadComparison()
                    message.value = "Session deleted."
                }
                .onFailure { showError("Delete session", it) }
        }
    }

    fun exportSession(sessionId: Long, format: SessionExportFormat) {
        viewModelScope.launch {
            runCatching { exportCoordinator.export(sessionId, format) }
                .onSuccess { report ->
                    runCatching {
                        app.startActivity(
                            exportCoordinator.createShareChooser(report)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }.onFailure { showError("Open share sheet", it) }
                }
                .onFailure { showError("Export ${format.name}", it) }
        }
    }

    fun toggleComparison(sessionId: Long) {
        val current = comparisonIds.value
        comparisonIds.value = when {
            sessionId in current -> current - sessionId
            current.size < 2 -> current + sessionId
            else -> {
                message.value = "Select at most two sessions."
                current
            }
        }
        loadComparison()
    }

    fun clearComparison() {
        comparisonIds.value = emptySet()
        comparisonSessions.value = emptyList()
    }

    private fun loadComparison() {
        val ids = comparisonIds.value.toList()
        viewModelScope.launch {
            comparisonSessions.value = ids.mapNotNull { id ->
                runCatching { sessionRepository.getSessionWithRows(id)?.let(::SessionDetailUi) }
                    .getOrNull()
            }
        }
    }

    fun deleteExpiredSessions() {
        viewModelScope.launch {
            val retention = settingsRepository.settings.first().retentionDays
            val cutoff = System.currentTimeMillis() - retention * 24L * 60 * 60 * 1_000
            runCatching { sessionRepository.deleteEndedBefore(cutoff) }
                .onSuccess { message.value = "$it expired session(s) deleted." }
                .onFailure { showError("Retention cleanup", it) }
        }
    }

    fun setTheme(value: ThemeMode) = updateSetting { settingsRepository.setTheme(value) }
    fun setGraphMode(value: GraphMode) = updateSetting { settingsRepository.setGraphMode(value) }
    fun setSamplingInterval(value: Long) = updateSetting { settingsRepository.setSamplingIntervalMillis(value) }
    fun setAutoDetectionTimeout(value: Long) = updateSetting { settingsRepository.setAutoDetectionTimeoutMillis(value) }
    fun setRetentionDays(value: Int) = updateSetting { settingsRepository.setRetentionDays(value) }
    fun setDatabaseCap(value: Long) = updateSetting { settingsRepository.setDatabaseCapBytes(value) }
    fun setPingEndpoint(value: String) = updateSetting { settingsRepository.setPingEndpoint(value) }
    fun setEnabledMetric(metric: MetricId, enabled: Boolean) = updateSetting {
        val current = settingsRepository.settings.first().enabledMetrics
        settingsRepository.setEnabledMetrics(if (enabled) current + metric else current - metric)
    }

    fun resetSettings() = updateSetting {
        settingsRepository.reset()
        message.value = "Settings reset."
    }

    private fun updateSetting(block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }.onFailure { showError("Settings", it) }
        }
    }

    fun newHudPreset() {
        val preset = HudPreset(
            id = "custom-${UUID.randomUUID()}",
            name = "Untitled HUD",
            widgets = emptyList(),
        )
        beginEditor(preset)
    }

    fun openHudPreset(preset: HudPreset) {
        val editable = if (BuiltInHudPresets.all.any { it.id == preset.id }) {
            preset.copy(id = "custom-${UUID.randomUUID()}", name = "${preset.name} copy")
        } else {
            preset
        }
        beginEditor(editable)
    }

    private fun beginEditor(preset: HudPreset) {
        initialEditorPreset = preset
        undo.clear()
        redo.clear()
        transactionStart = null
        editor.value = HudEditorUiState(preset)
    }

    fun closeEditor() {
        editor.value = null
        undo.clear()
        redo.clear()
        transactionStart = null
    }

    fun saveEditor() {
        val preset = editor.value?.preset ?: return
        viewModelScope.launch {
            runCatching { hudRepository.save(preset, System.currentTimeMillis()) }
                .onSuccess {
                    initialEditorPreset = preset
                    message.value = "${preset.name} saved."
                }
                .onFailure { showError("Save HUD", it) }
        }
    }

    fun deleteHudPreset(preset: HudPreset) {
        viewModelScope.launch {
            runCatching { hudRepository.deleteCustom(preset.id) }
                .onSuccess { deleted ->
                    if (deleted && activeHudPresetId.value == preset.id) {
                        activeHudPresetId.value = "benchmark"
                        app.getSharedPreferences(PREFERENCES, 0).edit().putString(ACTIVE_HUD_PRESET, "benchmark").apply()
                    }
                    message.value = if (deleted) "${preset.name} deleted." else "Built-in presets cannot be deleted."
                }
                .onFailure { showError("Delete HUD", it) }
        }
    }

    fun useHudPreset(preset: HudPreset) {
        activeHudPresetId.value = preset.id
        app.getSharedPreferences(PREFERENCES, 0).edit().putString(ACTIVE_HUD_PRESET, preset.id).apply()
        message.value = "${preset.name} will be used for new monitoring sessions."
    }

    fun renameEditor(name: String) = editPreset { it.copy(name = name) }

    fun setEditorOrientation(portrait: Boolean) = editPreset { preset ->
        val short = minOf(preset.canvasWidth, preset.canvasHeight)
        val long = maxOf(preset.canvasWidth, preset.canvasHeight)
        val width = if (portrait) short else long
        val height = if (portrait) long else short
        preset.copy(
            canvasWidth = width,
            canvasHeight = height,
            widgets = preset.widgets.map { widget ->
                val widgetWidth = widget.width.coerceAtMost(width)
                val widgetHeight = widget.height.coerceAtMost(height)
                widget.copy(
                    width = widgetWidth,
                    height = widgetHeight,
                    x = widget.x.coerceIn(0f, (width - widgetWidth).coerceAtLeast(0f)),
                    y = widget.y.coerceIn(0f, (height - widgetHeight).coerceAtLeast(0f)),
                )
            },
        )
    }

    fun setSafeArea(show: Boolean) = editPreset { it.copy(showSafeArea = show) }
    fun setPresetLocked(locked: Boolean) = editPreset { it.copy(lockedByDefault = locked) }
    fun setGridSize(size: Float) = editPreset { it.copy(gridSize = size.coerceIn(1f, 64f)) }
    fun setSnapToGrid(enabled: Boolean) {
        editor.value = editor.value?.copy(snapToGrid = enabled)
    }

    fun selectWidget(id: String?) {
        editor.value = editor.value?.copy(selectedWidgetId = id)
    }

    fun addWidget(type: HudWidgetType = HudWidgetType.METRIC_WITH_UNIT) {
        val state = editor.value ?: return
        val nextLayer = (state.preset.widgets.maxOfOrNull(HudWidget::layer) ?: -1) + 1
        val offset = (state.preset.widgets.size % 6) * state.preset.gridSize
        val widget = HudWidget(
            id = "widget-${UUID.randomUUID()}",
            type = type,
            x = 16f + offset,
            y = 16f + offset,
            layer = nextLayer,
        )
        editPreset(selectedId = widget.id) { it.copy(widgets = it.widgets + widget) }
    }

    fun updateSelectedWidget(transform: (HudWidget) -> HudWidget) {
        val selected = editor.value?.selectedWidgetId ?: return
        editPreset { preset ->
            preset.copy(widgets = preset.widgets.map { if (it.id == selected) transform(it) else it })
        }
    }

    fun beginWidgetGesture() {
        transactionStart = editor.value?.preset
    }

    fun moveWidget(id: String, deltaX: Float, deltaY: Float) {
        transformWidgetTransient(id) { widget, preset, snap ->
            val maximumX = (preset.canvasWidth - widget.width).coerceAtLeast(0f)
            val maximumY = (preset.canvasHeight - widget.height).coerceAtLeast(0f)
            widget.copy(
                x = snapValue(
                    (widget.x + deltaX).coerceIn(0f, maximumX),
                    preset.gridSize,
                    snap,
                ).coerceIn(0f, maximumX),
                y = snapValue(
                    (widget.y + deltaY).coerceIn(0f, maximumY),
                    preset.gridSize,
                    snap,
                ).coerceIn(0f, maximumY),
            )
        }
    }

    fun resizeWidget(id: String, deltaWidth: Float, deltaHeight: Float) {
        transformWidgetTransient(id) { widget, preset, snap ->
            val maximumWidth = (preset.canvasWidth - widget.x).coerceAtLeast(32f)
            val maximumHeight = (preset.canvasHeight - widget.y).coerceAtLeast(24f)
            widget.copy(
                width = snapValue(
                    (widget.width + deltaWidth).coerceIn(32f, maximumWidth),
                    preset.gridSize,
                    snap,
                ).coerceIn(32f, maximumWidth),
                height = snapValue(
                    (widget.height + deltaHeight).coerceIn(24f, maximumHeight),
                    preset.gridSize,
                    snap,
                ).coerceIn(24f, maximumHeight),
            )
        }
    }

    fun commitWidgetGesture() {
        val start = transactionStart
        val current = editor.value?.preset
        if (start != null && current != null && start != current) {
            undo.addLast(start)
            redo.clear()
            refreshEditorFlags()
        }
        transactionStart = null
    }

    fun duplicateSelectedWidget() {
        val state = editor.value ?: return
        val selected = state.selectedWidget ?: return
        val grid = state.preset.gridSize
        val copy = selected.copy(
            id = "widget-${UUID.randomUUID()}",
            x = (selected.x + grid).coerceAtMost(state.preset.canvasWidth - selected.width),
            y = (selected.y + grid).coerceAtMost(state.preset.canvasHeight - selected.height),
            layer = (state.preset.widgets.maxOfOrNull(HudWidget::layer) ?: 0) + 1,
            locked = false,
        )
        editPreset(selectedId = copy.id) { it.copy(widgets = it.widgets + copy) }
    }

    fun deleteSelectedWidget() {
        val state = editor.value ?: return
        val selected = state.selectedWidgetId ?: return
        editPreset(selectedId = null) { preset ->
            preset.copy(widgets = preset.widgets.filterNot { it.id == selected })
        }
    }

    fun moveSelectedLayer(delta: Int) {
        val selected = editor.value?.selectedWidget ?: return
        updateSelectedWidget { it.copy(layer = (selected.layer + delta).coerceAtLeast(0)) }
    }

    fun undoEditor() {
        val state = editor.value ?: return
        if (undo.isEmpty()) return
        redo.addLast(state.preset)
        val previous = undo.removeLast()
        editor.value = state.copy(
            preset = previous,
            selectedWidgetId = state.selectedWidgetId?.takeIf { id -> previous.widgets.any { it.id == id } },
            canUndo = undo.isNotEmpty(),
            canRedo = true,
        )
    }

    fun redoEditor() {
        val state = editor.value ?: return
        if (redo.isEmpty()) return
        undo.addLast(state.preset)
        val next = redo.removeLast()
        editor.value = state.copy(
            preset = next,
            selectedWidgetId = state.selectedWidgetId?.takeIf { id -> next.widgets.any { it.id == id } },
            canUndo = true,
            canRedo = redo.isNotEmpty(),
        )
    }

    fun resetEditor() {
        val initial = initialEditorPreset ?: return
        editPreset(selectedId = initial.widgets.maxByOrNull(HudWidget::layer)?.id) { initial }
    }

    private fun editPreset(
        selectedId: String? = editor.value?.selectedWidgetId,
        transform: (HudPreset) -> HudPreset,
    ) {
        val state = editor.value ?: return
        val changed = transform(state.preset)
        if (changed == state.preset) return
        undo.addLast(state.preset)
        if (undo.size > HISTORY_LIMIT) undo.removeFirst()
        redo.clear()
        editor.value = state.copy(
            preset = changed,
            selectedWidgetId = selectedId,
            canUndo = true,
            canRedo = false,
        )
    }

    private fun transformWidgetTransient(
        id: String,
        transform: (HudWidget, HudPreset, Boolean) -> HudWidget,
    ) {
        val state = editor.value ?: return
        val preset = state.preset
        editor.value = state.copy(
            preset = preset.copy(
                widgets = preset.widgets.map { widget ->
                    if (widget.id == id && !widget.locked) transform(widget, preset, state.snapToGrid) else widget
                },
            ),
            selectedWidgetId = id,
        )
    }

    private fun refreshEditorFlags() {
        editor.value = editor.value?.copy(canUndo = undo.isNotEmpty(), canRedo = redo.isNotEmpty())
    }

    private fun snapValue(value: Float, grid: Float, enabled: Boolean): Float =
        if (enabled && grid > 1f) round(value / grid) * grid else value

    private fun safeEndpoint(host: String): TcpLatencyEndpoint? =
        runCatching { TcpLatencyEndpoint(host.trim()) }.getOrNull()

    private fun installedGame(packageName: String): InstalledGame? = runCatching {
        val manager = app.packageManager
        val info = manager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        InstalledGame(
            packageName = packageName,
            displayName = info.applicationInfo?.loadLabel(manager)?.toString().orEmpty(),
            versionName = info.versionName,
            versionCode = info.longVersionCode,
        )
    }.getOrNull()

    private fun isPermissionDeclared(permission: String): Boolean = runCatching {
        val packageInfo = app.packageManager.getPackageInfo(
            app.packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
        )
        packageInfo.requestedPermissions?.contains(permission) == true
    }.getOrDefault(false)

    private fun hasUsageAccess(): Boolean {
        val appOps = app.getSystemService(AppOpsManager::class.java) ?: return false
        return appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            app.packageName,
        ) == AppOpsManager.MODE_ALLOWED
    }

    private fun showError(action: String, error: Throwable) {
        message.value = "$action failed: ${error.message ?: error::class.java.simpleName}"
    }

    override fun onCleared() {
        accessShizuku.close()
        accessRoot.close()
        super.onCleared()
    }

    private companion object {
        const val PREFERENCES = "pacbench_ui"
        const val ONBOARDING_COMPLETE = "onboarding_complete"
        const val ACTIVE_HUD_PRESET = "active_hud_preset"
        const val HISTORY_LIMIT = 80
    }
}
