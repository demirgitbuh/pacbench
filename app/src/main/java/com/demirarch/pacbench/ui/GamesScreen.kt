package com.demirarch.pacbench.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.demirarch.pacbench.data.local.Game
import com.demirarch.pacbench.data.local.GameAggregateStats
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun GamesScreen(
    viewModel: PacBenchViewModel,
    onOpenReport: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val detail by viewModel.selectedGame.collectAsStateWithLifecycle()
    if (detail != null) {
        GameDetailScreen(
            detail = detail!!,
            viewModel = viewModel,
            onOpenReport = onOpenReport,
            modifier = modifier,
        )
        return
    }

    val games by viewModel.games.collectAsStateWithLifecycle()
    val stats by viewModel.gameStats.collectAsStateWithLifecycle()
    val query by viewModel.gameQuery.collectAsStateWithLifecycle()
    val favoritesOnly by viewModel.favoriteGamesOnly.collectAsStateWithLifecycle()
    var showPicker by remember { mutableStateOf(false) }
    val statsById = remember(stats) { stats.associateBy(GameAggregateStats::gameId) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            PageHeader(
                eyebrow = "Launch targets",
                title = "Games",
                detail = "Discover launchable apps or register an exact package manually.",
                action = { Button(onClick = { showPicker = true }) { Text("Add game") } },
            )
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { viewModel.gameQuery.value = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Search library") },
                singleLine = true,
            )
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Favorites only", style = MaterialTheme.typography.titleMedium)
                    Text("Filter the Room game library", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = favoritesOnly,
                    onCheckedChange = { viewModel.favoriteGamesOnly.value = it },
                )
            }
        }
        if (games.isEmpty()) {
            item {
                EmptyState(
                    title = "No games in the library",
                    detail = "Discovery uses Android launcher activities. Manual registration supports packages not visible to discovery.",
                    actionLabel = "Choose a package",
                    onAction = { showPicker = true },
                )
            }
        } else {
            items(games, key = Game::id) { game ->
                GameCard(game, statsById[game.id], onOpen = { viewModel.openGame(game.id) })
            }
        }
    }

    if (showPicker) {
        GamePickerDialog(viewModel = viewModel, savedPackages = games.mapTo(hashSetOf(), Game::packageName)) {
            showPicker = false
        }
    }
}

@Composable
private fun GameCard(game: Game, stats: GameAggregateStats?, onOpen: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        onClick = onOpen,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(game.packageName, Modifier.size(48.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(game.displayName, style = MaterialTheme.typography.titleLarge)
                    Text(
                        game.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (game.isFavorite) StatusPill("FAVORITE", PacOrangeBright)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricTile("Sessions", stats?.sessionCount?.toString() ?: "0", Modifier.weight(1f))
                MetricTile(
                    "Average FPS",
                    stats?.averageFps?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: "Unavailable",
                    Modifier.weight(1f),
                )
                MetricTile(
                    "Last run",
                    stats?.lastSessionAt?.let(::formatDate) ?: "Never",
                    Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun GameDetailScreen(
    detail: GameDetailUi,
    viewModel: PacBenchViewModel,
    onOpenReport: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val game = detail.gameWithSessions.game
    val recording by viewModel.recording.collectAsStateWithLifecycle()
    val activePresetId by viewModel.activeHudPresetId.collectAsStateWithLifecycle()
    val presets by viewModel.allPresets.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    var editableName by remember(game.id, game.displayName) { mutableStateOf(game.displayName) }
    val ownRecording = recording?.packageName == game.packageName

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = viewModel::closeGame) { Text("Back") }
                Spacer(Modifier.width(12.dp))
                AppIcon(game.packageName, Modifier.size(56.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(game.displayName, style = MaterialTheme.typography.headlineMedium)
                    Text(game.packageName, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { viewModel.toggleFavorite(game) }) {
                    Text(if (game.isFavorite) "Unfavorite" else "Favorite")
                }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.38f)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Run control", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "The foreground monitoring service probes normal, Shizuku, and root sources, then records the best available reading for each metric.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "HUD preset: ${game.selectedHudPresetId ?: activePresetId} | Stored access mode comes from the first real snapshot.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { viewModel.launchGame(game.packageName) }, Modifier.weight(1f)) {
                            Text("Launch only")
                        }
                        if (ownRecording && recording?.active == true) {
                            Button(onClick = viewModel::stopRecording, Modifier.weight(1f)) { Text("Stop recording") }
                        } else {
                            Button(
                                onClick = { viewModel.recordAndLaunch(game) },
                                enabled = recording?.active != true,
                                modifier = Modifier.weight(1f),
                            ) { Text("Record + launch") }
                        }
                    }
                }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Per-game settings", style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(
                        value = editableName,
                        onValueChange = { editableName = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Display name") },
                        singleLine = true,
                    )
                    OutlinedButton(
                        onClick = { viewModel.renameGame(game, editableName) },
                        enabled = editableName.trim() != game.displayName,
                    ) { Text("Save name") }
                    if (game.customName != null) {
                        TextButton(onClick = {
                            editableName = game.appName
                            viewModel.renameGame(game, "")
                        }) { Text("Use app name") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Automatic monitoring")
                            Text("Detect this game with Usage Access", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(game.autoMonitoring, { viewModel.setGameAutoMonitoring(game, it) })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Automatic overlay")
                            Text("Show the assigned HUD when monitoring starts", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(game.autoOverlay, { viewModel.setGameAutoOverlay(game, it) })
                    }
                    Text("Assigned HUD", style = MaterialTheme.typography.titleMedium)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = game.selectedHudPresetId == null,
                            onClick = { viewModel.assignGamePreset(game, null) },
                            label = { Text("Default") },
                        )
                        presets.forEach { preset ->
                            FilterChip(
                                selected = game.selectedHudPresetId == preset.id,
                                onClick = { viewModel.assignGamePreset(game, preset.id) },
                                label = { Text(preset.name) },
                            )
                        }
                    }
                }
            }
        }
        item {
            val stats = detail.aggregate
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricTile("Sessions", stats?.sessionCount?.toString() ?: "0", Modifier.weight(1f))
                MetricTile("Samples", stats?.sampleCount?.toString() ?: "0", Modifier.weight(1f))
                MetricTile("Tracked", stats?.totalDurationMillis?.let(::formatDuration) ?: "0:00", Modifier.weight(1f))
            }
        }
        item {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("Package", style = MaterialTheme.typography.titleMedium)
                    DetailValue("Version", game.versionName ?: "Unavailable")
                    DetailValue("Version code", game.versionCode?.toString() ?: "Unavailable")
                    DetailValue("First seen", formatDate(game.firstSeenAt))
                    DetailValue("Last seen", formatDate(game.lastSeenAt))
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Stored sessions", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { viewModel.openGame(game.id) }) { Text("Refresh") }
            }
        }
        if (detail.gameWithSessions.sessions.isEmpty()) {
            item { EmptyState("No sessions", "Use Record + launch to create a real Room session.") }
        } else {
            items(detail.gameWithSessions.sessions.sortedByDescending { it.startedAt }, key = { it.id }) { session ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onOpenReport(session.id) },
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(formatDate(session.startedAt), style = MaterialTheme.typography.titleMedium)
                            Text(
                                session.endedAt?.let { formatDuration(it - session.startedAt) } ?: "In progress",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text(session.status.name, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        item {
            HorizontalDivider()
            Spacer(Modifier.height(4.dp))
            DestructiveButton("Remove game", onClick = { confirmDelete = true })
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Remove ${game.displayName}?") },
            text = { Text("The game will be hidden from the library. Stored performance sessions remain in Reports.") },
            confirmButton = { TextButton(onClick = { viewModel.deleteGame(game) }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DetailValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.width(110.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AppIcon(packageName: String, modifier: Modifier = Modifier) {
    val packageManager = LocalContext.current.packageManager
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching { packageManager.getApplicationIcon(packageName).toBitmap(192, 192).asImageBitmap() }
                .getOrNull()
        }
    }
    val loadedBitmap = bitmap
    if (loadedBitmap != null) {
        Image(bitmap = loadedBitmap, contentDescription = null, modifier = modifier)
    } else {
        Surface(modifier = modifier, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(packageName.take(1).uppercase(), color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun GamePickerDialog(
    viewModel: PacBenchViewModel,
    savedPackages: Set<String>,
    onDismiss: () -> Unit,
) {
    val installed by viewModel.installedGames.collectAsStateWithLifecycle()
    val discovering by viewModel.discoveringGames.collectAsStateWithLifecycle()
    var pickerMode by remember { mutableStateOf("games") }
    var packageName by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { viewModel.discoverInstalledGames() }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 8.dp,
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Add a game", style = MaterialTheme.typography.headlineMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = pickerMode == "games",
                        onClick = { pickerMode = "games" },
                        label = { Text("Auto games") },
                    )
                    FilterChip(
                        selected = pickerMode == "all",
                        onClick = { pickerMode = "all" },
                        label = { Text("All apps") },
                    )
                    FilterChip(
                        selected = pickerMode == "manual",
                        onClick = { pickerMode = "manual" },
                        label = { Text("Manual package") },
                    )
                }
                if (pickerMode != "manual") {
                    val visibleApps = if (pickerMode == "games") installed.filter(InstalledGame::likelyGame) else installed
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (discovering) "Reading launcher activities..." else "${visibleApps.size} matching app(s)",
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = viewModel::discoverInstalledGames, enabled = !discovering) { Text("Refresh") }
                    }
                    if (!discovering && visibleApps.isEmpty()) {
                        EmptyState(
                            "No launcher packages visible",
                            "Android package visibility may limit discovery. Use Manual package for an exact ID.",
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 430.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(visibleApps, key = InstalledGame::packageName) { game ->
                                Surface(
                                    shape = RoundedCornerShape(13.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                ) {
                                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(game.displayName, style = MaterialTheme.typography.titleMedium)
                                            Text(game.packageName, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                        }
                                        Button(
                                            onClick = { viewModel.addInstalledGame(game) },
                                            enabled = game.packageName !in savedPackages,
                                        ) { Text(if (game.packageName in savedPackages) "Added" else "Add") }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Text(
                        "The package can be registered even when Android does not expose it to discovery. Launch remains unavailable unless it has a visible launch activity.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = packageName,
                        onValueChange = { packageName = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Package name") },
                        placeholder = { Text("com.example.game") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = displayName,
                        onValueChange = { displayName = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Display name (required if not installed)") },
                        singleLine = true,
                    )
                    Button(
                        onClick = {
                            viewModel.addManualGame(packageName, displayName)
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Add exact package") }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Close") }
            }
        }
    }
}
