package com.demirarch.pacbench.ui

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.demirarch.pacbench.data.local.PacBenchDatabase
import com.demirarch.pacbench.data.settings.GraphMode
import com.demirarch.pacbench.data.settings.ThemeMode
import com.demirarch.pacbench.metrics.ShizukuPermissionState
import com.demirarch.pacbench.model.MetricId
import com.demirarch.pacbench.model.MetricStatus
import java.io.File
import java.util.Locale

@Composable
fun SettingsScreen(
    viewModel: PacBenchViewModel,
    onRequestNotification: () -> Unit,
    onOpenOverlay: () -> Unit,
    onOpenUsageAccess: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val access by viewModel.access.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var endpoint by remember { mutableStateOf(settings.pingEndpoint) }
    LaunchedEffect(settings.pingEndpoint) { endpoint = settings.pingEndpoint }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            PageHeader(
                eyebrow = "Local configuration",
                title = "Settings",
                detail = "Changes are persisted with DataStore on this device.",
            )
        }
        item {
            SettingsCard("Appearance", "System, light, or near-black display") {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = settings.theme == mode,
                            onClick = { viewModel.setTheme(mode) },
                            label = { Text(mode.name) },
                        )
                    }
                }
            }
        }
        item {
            SettingsCard("Sampling", "Intervals are applied when the next session starts") {
                Text("Sample interval", style = MaterialTheme.typography.titleMedium)
                ChoiceRow(
                    values = listOf(250L, 500L, 1_000L, 2_000L, 5_000L),
                    selected = settings.samplingIntervalMillis,
                    label = { "$it ms" },
                    onSelect = viewModel::setSamplingInterval,
                )
                Text("Discovery timeout", style = MaterialTheme.typography.titleMedium)
                ChoiceRow(
                    values = listOf(5_000L, 10_000L, 20_000L, 30_000L),
                    selected = settings.autoDetectionTimeoutMillis,
                    label = { "${it / 1_000}s" },
                    onSelect = viewModel::setAutoDetectionTimeout,
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Automatic session detection", style = MaterialTheme.typography.titleMedium)
                        Text("Keeps the user-started foreground watcher active between configured games", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(settings.automaticDetectionEnabled, viewModel::setAutomaticDetection)
                }
                Text("Default graph context", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GraphMode.entries.forEach { mode ->
                        FilterChip(
                            selected = settings.graphMode == mode,
                            onClick = { viewModel.setGraphMode(mode) },
                            label = { Text(mode.name) },
                        )
                    }
                }
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("TCP latency host") },
                    supportingText = { Text("A TCP connection to port 443 is measured; no ICMP value is invented.") },
                    singleLine = true,
                )
                Button(onClick = { viewModel.setPingEndpoint(endpoint) }) { Text("Save endpoint") }
            }
        }
        item {
            SettingsCard("Enabled metrics", "Disabled metrics are not requested in new sessions") {
                MetricId.entries.forEach { metric ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(metric.displayName(), style = MaterialTheme.typography.titleMedium)
                            Text(metric.defaultUnit.ifBlank { "State value" }, style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = metric in settings.enabledMetrics,
                            onCheckedChange = { viewModel.setEnabledMetric(metric, it) },
                        )
                    }
                }
            }
        }
        item {
            SettingsCard("Storage", "Room data is local unless the user deliberately exports it") {
                val databaseBytes = rememberDatabaseBytes(context)
                DetailSetting("Database files", formatBytes(databaseBytes))
                Text("Retention", style = MaterialTheme.typography.titleMedium)
                ChoiceRow(
                    values = listOf(7, 30, 90, 365),
                    selected = settings.retentionDays,
                    label = { "$it days" },
                    onSelect = viewModel::setRetentionDays,
                )
                Text("Configured cap", style = MaterialTheme.typography.titleMedium)
                ChoiceRow(
                    values = listOf(128L, 512L, 1_024L).map { it * 1024 * 1024 },
                    selected = settings.databaseCapBytes,
                    label = { "${it / 1024 / 1024} MB" },
                    onSelect = viewModel::setDatabaseCap,
                )
                Text(
                    "The data layer stores the cap but does not expose an automatic size-enforcement API. Retention cleanup below performs an actual timestamp deletion.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = viewModel::deleteExpiredSessions) { Text("Delete expired sessions now") }
            }
        }
        item {
            AccessSettings(
                state = access,
                onRequestNotification = onRequestNotification,
                onOpenOverlay = onOpenOverlay,
                onOpenUsageAccess = onOpenUsageAccess,
                onRequestShizuku = viewModel::requestShizuku,
                onCheckRoot = viewModel::checkRoot,
                onRefresh = viewModel::refreshAccess,
            )
        }
        item {
            SettingsCard("Reset", "Restore DataStore defaults without deleting sessions, games, or HUD presets") {
                OutlinedButton(onClick = viewModel::resetSettings) { Text("Reset settings") }
            }
        }
        item { AboutCard(context) }
    }
}

@Composable
private fun AccessSettings(
    state: AccessUiState,
    onRequestNotification: () -> Unit,
    onOpenOverlay: () -> Unit,
    onOpenUsageAccess: () -> Unit,
    onRequestShizuku: () -> Unit,
    onCheckRoot: () -> Unit,
    onRefresh: () -> Unit,
) {
    SettingsCard("Access", "Every status below comes from the platform or a provider probe") {
        AccessRow(
            label = "Notifications",
            status = when {
                !state.notificationDeclared -> "Not declared"
                state.notificationGranted -> "Granted"
                else -> "Permission required"
            },
            good = state.notificationGranted,
            action = if (state.notificationDeclared && !state.notificationGranted) "Request" else null,
            onAction = onRequestNotification,
        )
        AccessRow(
            label = "System overlay",
            status = when {
                !state.overlayDeclared -> "Not declared"
                state.overlayGranted -> "Granted"
                else -> "Permission required"
            },
            good = state.overlayGranted,
            action = if (state.overlayDeclared && !state.overlayGranted) "Open settings" else null,
            onAction = onOpenOverlay,
        )
        AccessRow(
            label = "Usage access",
            status = if (state.usageAccessGranted) "Granted" else "Required for automatic detection",
            good = state.usageAccessGranted,
            action = if (state.usageAccessGranted) null else "Open settings",
            onAction = onOpenUsageAccess,
        )
        AccessRow(
            label = "Shizuku",
            status = state.shizukuState.name.replace('_', ' '),
            good = state.shizukuState == ShizukuPermissionState.READY,
            action = if (state.shizukuState != ShizukuPermissionState.READY) "Connect" else null,
            onAction = onRequestShizuku,
        )
        AccessRow(
            label = "Root",
            status = state.rootStatus,
            good = state.rootStatus == "Ready",
            action = "Check",
            onAction = onCheckRoot,
        )
        HorizontalDivider()
        val available = state.normalCapabilities.count { it.status == MetricStatus.AVAILABLE }
        Text(
            if (state.checking) "Probing normal-access metrics..." else "$available / ${state.normalCapabilities.size} normal capabilities available",
            style = MaterialTheme.typography.bodySmall,
        )
        state.normalCapabilities.forEach { capability ->
            Row(Modifier.fillMaxWidth()) {
                Text(capability.metric.displayName(), Modifier.weight(1f))
                Text(
                    capability.status.name.replace('_', ' '),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (capability.available) PacGood else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        TextButton(onClick = onRefresh) { Text("Refresh access status") }
    }
}

@Composable
private fun AccessRow(
    label: String,
    status: String,
    good: Boolean,
    action: String?,
    onAction: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(status, style = MaterialTheme.typography.bodySmall, color = if (good) PacGood else PacWarn)
        }
        if (action != null) OutlinedButton(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun SettingsCard(title: String, detail: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()
            content()
        }
    }
}

@Composable
private fun <T> ChoiceRow(
    values: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        values.forEach { value ->
            FilterChip(selected = selected == value, onClick = { onSelect(value) }, label = { Text(label(value)) })
        }
    }
}

@Composable
private fun DetailSetting(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun AboutCard(context: Context) {
    val version = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0),
            ).versionName
        }.getOrNull() ?: "Unavailable"
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = PacNearBlack,
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            PacBenchLogo(Modifier.size(76.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("PACBENCH", color = Color.White, style = MaterialTheme.typography.titleLarge)
                Text("Performance without invented data", color = PacOrangeBright)
                Spacer(Modifier.height(6.dp))
                Text("Version $version | GPL-3.0", color = Color.White.copy(alpha = 0.65f), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun PacBenchLogo(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        drawRoundRect(PacOrange, cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.minDimension * 0.22f))
        val path = Path().apply {
            moveTo(size.width * 0.27f, size.height * 0.2f)
            lineTo(size.width * 0.55f, size.height * 0.2f)
            cubicTo(size.width * 0.83f, size.height * 0.2f, size.width * 0.83f, size.height * 0.58f, size.width * 0.55f, size.height * 0.58f)
            lineTo(size.width * 0.43f, size.height * 0.58f)
            lineTo(size.width * 0.43f, size.height * 0.8f)
            lineTo(size.width * 0.27f, size.height * 0.8f)
            close()
        }
        drawPath(path, PacNearBlack)
        drawLine(
            Color.White.copy(alpha = 0.75f),
            Offset(size.width * 0.5f, size.height * 0.42f),
            Offset(size.width * 0.67f, size.height * 0.42f),
            strokeWidth = size.width * 0.06f,
        )
    }
}

@Composable
fun OnboardingScreen(
    viewModel: PacBenchViewModel,
    onRequestNotification: () -> Unit,
    onOpenOverlay: () -> Unit,
    onOpenUsageAccess: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val access by viewModel.access.collectAsStateWithLifecycle()
    Box(modifier.fillMaxSize().background(PacNearBlack), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PacBenchLogo(Modifier.size(92.dp))
            Text("PACBENCH", color = Color.White, style = MaterialTheme.typography.displaySmall)
            Text(
                "Choose access deliberately. Normal mode works without elevated access; unsupported metrics stay unavailable.",
                color = Color.White.copy(alpha = 0.75f),
                modifier = Modifier.fillMaxWidth(),
            )
            Surface(shape = RoundedCornerShape(20.dp), color = Color.White.copy(alpha = 0.07f)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OnboardingAccessLine(
                        "Notifications",
                        if (!access.notificationDeclared) "Manifest declaration unavailable" else if (access.notificationGranted) "Granted" else "Optional permission",
                        action = if (access.notificationDeclared && !access.notificationGranted) "Request" else null,
                        onAction = onRequestNotification,
                    )
                    OnboardingAccessLine(
                        "System overlay",
                        if (!access.overlayDeclared) "Manifest declaration unavailable" else if (access.overlayGranted) "Granted" else "Optional permission",
                        action = if (access.overlayDeclared && !access.overlayGranted) "Open" else null,
                        onAction = onOpenOverlay,
                    )
                    OnboardingAccessLine(
                        "Usage access",
                        if (access.usageAccessGranted) "Granted" else "Needed only for automatic detection",
                        action = if (access.usageAccessGranted) null else "Open",
                        onAction = onOpenUsageAccess,
                    )
                    OnboardingAccessLine(
                        "Shizuku",
                        access.shizukuState.name.replace('_', ' '),
                        action = if (access.shizukuState != ShizukuPermissionState.READY) "Connect" else null,
                        onAction = viewModel::requestShizuku,
                    )
                }
            }
            Button(onClick = viewModel::completeOnboarding, modifier = Modifier.fillMaxWidth()) {
                Text("Continue with current access")
            }
            Text(
                "Root remains opt-in from Settings. PacBench never substitutes estimates for denied or absent sources.",
                color = Color.White.copy(alpha = 0.55f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun OnboardingAccessLine(
    label: String,
    status: String,
    action: String?,
    onAction: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Color.White, fontWeight = FontWeight.Bold)
            Text(status, color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall)
        }
        if (action != null) OutlinedButton(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun rememberDatabaseBytes(context: Context): Long {
    val base = context.getDatabasePath(PacBenchDatabase.DEFAULT_DATABASE_NAME)
    return listOf(base, File(base.path + "-wal"), File(base.path + "-shm")).sumOf { file ->
        if (file.exists()) file.length() else 0L
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(Locale.getDefault(), "%.2f GB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024))
    bytes >= 1024L -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}
