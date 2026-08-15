package com.demirarch.pacbench.ui

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private enum class MainSection(val label: String, val marker: String) {
    HUD("HUD", "H"),
    REPORTS("Reports", "R"),
    GAMES("Games", "G"),
    SETTINGS("Settings", "S"),
}

@Composable
fun PacBenchRoot(viewModel: PacBenchViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val editor by viewModel.editor.collectAsStateWithLifecycle()
    val onboarding by viewModel.onboardingVisible.collectAsStateWithLifecycle()
    val selectedGame by viewModel.selectedGame.collectAsStateWithLifecycle()
    val selectedSession by viewModel.selectedSession.collectAsStateWithLifecycle()
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.refreshAccess()
    }
    val overlayLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.refreshAccess()
    }
    val usageAccessLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.refreshAccess()
    }
    val requestNotification = { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
    val openOverlay = { overlayLauncher.launch(viewModel.overlaySettingsIntent()) }
    val openUsageAccess = { usageAccessLauncher.launch(viewModel.usageAccessSettingsIntent()) }

    PacBenchTheme(settings.theme) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when {
                onboarding -> OnboardingScreen(
                    viewModel = viewModel,
                    onRequestNotification = requestNotification,
                    onOpenOverlay = openOverlay,
                    onOpenUsageAccess = openUsageAccess,
                    modifier = Modifier.safeDrawingPadding(),
                )
                editor != null -> {
                    BackHandler(onBack = viewModel::closeEditor)
                    HudDesignerScreen(viewModel, Modifier.safeDrawingPadding())
                }
                else -> {
                    if (selectedSession != null) BackHandler(onBack = viewModel::closeSession)
                    else if (selectedGame != null) BackHandler(onBack = viewModel::closeGame)
                    MainShell(
                        viewModel = viewModel,
                        onRequestNotification = requestNotification,
                        onOpenOverlay = openOverlay,
                        onOpenUsageAccess = openUsageAccess,
                    )
                }
            }
        }
    }
}

@Composable
private fun MainShell(
    viewModel: PacBenchViewModel,
    onRequestNotification: () -> Unit,
    onOpenOverlay: () -> Unit,
    onOpenUsageAccess: () -> Unit,
) {
    var selectedName by rememberSaveable { mutableStateOf(MainSection.HUD.name) }
    val selected = MainSection.valueOf(selectedName)
    val message by viewModel.message.collectAsStateWithLifecycle()
    val recording by viewModel.recording.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        val value = message ?: return@LaunchedEffect
        snackbar.showSnackbar(value)
        viewModel.clearMessage()
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (!wide) {
                    NavigationBar {
                        MainSection.entries.forEach { section ->
                            NavigationBarItem(
                                selected = section == selected,
                                onClick = {
                                    selectedName = section.name
                                },
                                icon = {
                                    Text(
                                        section.marker,
                                        fontWeight = FontWeight.Black,
                                        color = if (section == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                                label = { Text(section.label) },
                            )
                        }
                    }
                }
            },
        ) { innerPadding ->
            Row(Modifier.fillMaxSize().padding(innerPadding)) {
                if (wide) {
                    NavigationRail {
                        PacBenchLogo(Modifier.padding(14.dp).size(48.dp))
                        MainSection.entries.forEach { section ->
                            NavigationRailItem(
                                selected = section == selected,
                                onClick = {
                                    selectedName = section.name
                                },
                                icon = { Text(section.marker, fontWeight = FontWeight.Black) },
                                label = { Text(section.label) },
                            )
                        }
                    }
                }
                Column(Modifier.fillMaxSize().weight(1f)) {
                    if (recording != null) {
                        RecordingBanner(
                            state = recording!!,
                            onStop = viewModel::stopRecording,
                            onDismiss = viewModel::dismissFinishedRecording,
                        )
                    }
                    val screenModifier = Modifier.fillMaxSize().weight(1f)
                    when (selected) {
                        MainSection.HUD -> HudScreen(viewModel, screenModifier)
                        MainSection.REPORTS -> ReportsScreen(viewModel, screenModifier)
                        MainSection.GAMES -> GamesScreen(
                            viewModel = viewModel,
                            onOpenReport = { sessionId ->
                                viewModel.openSession(sessionId)
                                selectedName = MainSection.REPORTS.name
                            },
                            modifier = screenModifier,
                        )
                        MainSection.SETTINGS -> SettingsScreen(
                            viewModel = viewModel,
                            onRequestNotification = onRequestNotification,
                            onOpenOverlay = onOpenOverlay,
                            onOpenUsageAccess = onOpenUsageAccess,
                            modifier = screenModifier,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordingBanner(
    state: RecordingUiState,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (state.active) PacOrange else MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (state.active) "RECORDING | ${state.gameName}" else "SESSION ENDED | ${state.gameName}",
                color = if (state.active) PacNearBlack else MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Black,
            )
            Text(
                "${state.sampleCount} samples | ${state.accessMode.name}${state.error?.let { " | $it" }.orEmpty()}",
                color = if (state.active) PacNearBlack.copy(alpha = 0.72f) else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (state.active) {
            Button(onClick = onStop) { Text("Stop") }
        } else {
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}
