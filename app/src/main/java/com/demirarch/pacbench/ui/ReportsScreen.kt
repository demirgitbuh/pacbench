package com.demirarch.pacbench.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.demirarch.pacbench.data.local.SessionListItem
import com.demirarch.pacbench.data.local.SessionStatus
import com.demirarch.pacbench.export.SessionExportFormat
import com.demirarch.pacbench.model.AccessMode
import com.demirarch.pacbench.model.AnalysisFinding
import com.demirarch.pacbench.model.Confidence
import com.demirarch.pacbench.model.MetricCalculations
import com.demirarch.pacbench.model.MetricId
import com.demirarch.pacbench.model.RuleBasedAnalyzer
import com.demirarch.pacbench.model.SampleData
import java.util.Locale

@Composable
fun ReportsScreen(viewModel: PacBenchViewModel, modifier: Modifier = Modifier) {
    var accessFilterName by rememberSaveable { mutableStateOf<String?>(null) }
    var minimumFps by rememberSaveable { mutableStateOf<Double?>(null) }
    var maximumTemperature by rememberSaveable { mutableStateOf<Double?>(null) }
    var minimumDurationMinutes by rememberSaveable { mutableIntStateOf(0) }
    var newestFirst by rememberSaveable { mutableStateOf(true) }
    val accessFilter = accessFilterName?.let { name -> AccessMode.entries.firstOrNull { it.name == name } }
    val selected by viewModel.selectedSession.collectAsStateWithLifecycle()
    if (selected != null) {
        SessionDetailScreen(
            detail = selected!!,
            onBack = viewModel::closeSession,
            onDelete = { viewModel.deleteSession(selected!!.rows.session.id) },
            onExport = { format -> viewModel.exportSession(selected!!.rows.session.id, format) },
            modifier = modifier,
        )
        return
    }

    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val query by viewModel.reportQuery.collectAsStateWithLifecycle()
    val status by viewModel.reportStatus.collectAsStateWithLifecycle()
    val range by viewModel.reportRange.collectAsStateWithLifecycle()
    val comparisonIds by viewModel.comparisonIds.collectAsStateWithLifecycle()
    val comparison by viewModel.comparisonSessions.collectAsStateWithLifecycle()
    val visibleSessions = remember(
        sessions,
        accessFilter,
        minimumFps,
        maximumTemperature,
        minimumDurationMinutes,
        newestFirst,
    ) {
        sessions.asSequence()
            .filter { accessFilter == null || it.session.accessMode == accessFilter }
            .filter { minimumFps == null || (it.effectiveAverageFps ?: Double.NEGATIVE_INFINITY) >= minimumFps!! }
            .filter { maximumTemperature == null || it.effectiveMaxTemperature?.let { value -> value <= maximumTemperature!! } == true }
            .filter { it.effectiveDurationMillis >= minimumDurationMinutes * 60_000L }
            .let { sequence -> if (newestFirst) sequence.sortedByDescending { it.session.startedAt } else sequence.sortedBy { it.session.startedAt } }
            .toList()
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            PageHeader(
                eyebrow = "Room archive",
                title = "Reports",
                detail = "Stored sessions only. Missing readings remain missing.",
            )
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { viewModel.reportQuery.value = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Search game or package") },
                singleLine = true,
            )
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(selected = status == null, onClick = { viewModel.reportStatus.value = null }, label = { Text("Any status") })
                    SessionStatus.entries.forEach { value ->
                        FilterChip(
                            selected = status == value,
                            onClick = { viewModel.reportStatus.value = value },
                            label = { Text(value.name.lowercase().replaceFirstChar(Char::titlecase)) },
                        )
                    }
                }
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ReportRange.entries.forEach { value ->
                        FilterChip(
                            selected = range == value,
                            onClick = { viewModel.reportRange.value = value },
                            label = { Text(value.label) },
                        )
                    }
                }
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(accessFilter == null, { accessFilterName = null }, label = { Text("Any access") })
                    AccessMode.entries.forEach { mode ->
                        FilterChip(accessFilter == mode, { accessFilterName = mode.name }, label = { Text(mode.name) })
                    }
                }
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf<Double?>(null, 30.0, 60.0, 90.0).forEach { value ->
                        FilterChip(minimumFps == value, { minimumFps = value }, label = { Text(value?.let { "Average FPS >= ${it.toInt()}" } ?: "Any FPS") })
                    }
                    listOf<Double?>(null, 60.0, 70.0, 80.0).forEach { value ->
                        FilterChip(maximumTemperature == value, { maximumTemperature = value }, label = { Text(value?.let { "Temp <= ${it.toInt()}C" } ?: "Any temp") })
                    }
                }
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(0, 5, 15, 30).forEach { minutes ->
                        FilterChip(minimumDurationMinutes == minutes, { minimumDurationMinutes = minutes }, label = { Text(if (minutes == 0) "Any duration" else ">= $minutes min") })
                    }
                    FilterChip(newestFirst, { newestFirst = true }, label = { Text("Newest") })
                    FilterChip(!newestFirst, { newestFirst = false }, label = { Text("Oldest") })
                }
            }
        }
        if (comparisonIds.isNotEmpty()) {
            item {
                ComparisonPanel(comparison, onClear = viewModel::clearComparison)
            }
        }
        if (visibleSessions.isEmpty()) {
            item {
                EmptyState(
                    title = "No matching sessions",
                    detail = "Record a game from Games, or change the current search and filters.",
                )
            }
        } else {
            items(visibleSessions, key = { it.session.id }) { item ->
                SessionCard(
                    item = item,
                    comparisonSelected = item.session.id in comparisonIds,
                    onOpen = { viewModel.openSession(item.session.id) },
                    onCompare = { viewModel.toggleComparison(item.session.id) },
                )
            }
        }
    }
}

@Composable
private fun SessionCard(
    item: SessionListItem,
    comparisonSelected: Boolean,
    onOpen: () -> Unit,
    onCompare: () -> Unit,
) {
    val statusColor = when (item.session.status) {
        SessionStatus.COMPLETED -> PacGood
        SessionStatus.RUNNING -> PacWarn
        SessionStatus.INTERRUPTED -> PacBad
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        onClick = onOpen,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(item.gameDisplayName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        item.gamePackageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                StatusPill(item.session.status.name, statusColor)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricTile("Started", formatDate(item.session.startedAt), Modifier.weight(1f))
                MetricTile(
                    "Duration",
                    item.session.endedAt?.let { formatDuration(it - item.session.startedAt) } ?: "Active",
                    Modifier.weight(1f),
                )
                MetricTile("Samples", item.sampleCount.toString(), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricTile("Average FPS", item.effectiveAverageFps?.let { "%.1f".format(it) } ?: "N/A", Modifier.weight(1f))
                MetricTile("1% low", item.session.onePercentLow?.let { "%.1f".format(it) } ?: "N/A", Modifier.weight(1f))
                MetricTile("Max temp", item.effectiveMaxTemperature?.let { "%.1f C".format(it) } ?: "N/A", Modifier.weight(1f))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = comparisonSelected, onCheckedChange = { onCompare() })
                Text("Use in comparison", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                Text(
                    item.session.accessMode.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun ComparisonPanel(sessions: List<SessionDetailUi>, onClear: () -> Unit) {
    val availableMetrics = remember(sessions) {
        MetricId.entries.filter { metric -> sessions.any { detail -> detail.samples.any { it.value(metric) != null } } }
    }
    var selectedMetricName by rememberSaveable(sessions.map { it.rows.session.id }) {
        mutableStateOf(
            availableMetrics.firstOrNull { it == MetricId.FPS }?.name
                ?: availableMetrics.firstOrNull()?.name,
        )
    }
    val selectedMetric = availableMetrics.firstOrNull { it.name == selectedMetricName }
        ?: availableMetrics.firstOrNull()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Session comparison", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (sessions.size == 2) "Delta is second session minus first session." else "Select one more session.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onClear) { Text("Clear") }
            }
            sessions.forEachIndexed { index, detail ->
                Text(
                    "${index + 1}. ${detail.rows.game.displayName} | ${formatDate(detail.rows.session.startedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (sessions.size == 2) {
                val first = MetricCalculations.sessionSummary(sessions[0].samples)
                val second = MetricCalculations.sessionSummary(sessions[1].samples)
                HorizontalDivider()
                DeltaRow("Average FPS", first.averageFps, second.averageFps, "fps")
                DeltaRow("1% low", first.onePercentLow, second.onePercentLow, "fps")
                DeltaRow("Average CPU", first.averageCpu, second.averageCpu, "%")
                DeltaRow("Average GPU", first.averageGpu, second.averageGpu, "%")
                DeltaRow("Peak power", first.peakPower, second.peakPower, "W")
                DeltaRow("Max battery temp", first.maxBatteryTemp, second.maxBatteryTemp, "C")
                if (availableMetrics.isNotEmpty()) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        availableMetrics.forEach { metric ->
                            FilterChip(
                                selected = metric == selectedMetric,
                                onClick = { selectedMetricName = metric.name },
                                label = { Text(metric.displayName()) },
                            )
                        }
                    }
                }
                selectedMetric?.let { metric ->
                    val firstSession = sessions[0].rows.session
                    val secondSession = sessions[1].rows.session
                    ComparisonOverlayChart(
                        first = sessions[0].samples,
                        second = sessions[1].samples,
                        metric = metric,
                        firstStartedAt = firstSession.startedAt,
                        firstDurationMillis = firstSession.durationMillis
                            ?: firstSession.endedAt?.minus(firstSession.startedAt),
                        secondStartedAt = secondSession.startedAt,
                        secondDurationMillis = secondSession.durationMillis
                            ?: secondSession.endedAt?.minus(secondSession.startedAt),
                    )
                }
            }
        }
    }
}

@Composable
private fun DeltaRow(label: String, first: Double?, second: Double?, unit: String) {
    val delta = if (first != null && second != null) second - first else null
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f))
        Text(
            delta?.let { String.format(Locale.getDefault(), "%+.1f %s", it, unit) } ?: "Unavailable",
            style = MaterialTheme.typography.bodySmall,
            color = if (delta == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun SessionDetailScreen(
    detail: SessionDetailUi,
    onBack: () -> Unit,
    onDelete: () -> Unit,
    onExport: (SessionExportFormat) -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by remember(detail.rows.session.id) { mutableIntStateOf(0) }
    var confirmDelete by remember { mutableStateOf(false) }
    var graphScaleName by rememberSaveable(detail.rows.session.id) {
        mutableStateOf(ChartScaleMode.MULTI_AXIS.name)
    }
    val tabs = listOf("Summary", "Graphs", "Analysis", "Quality")

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onBack) { Text("Back") }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(detail.rows.game.displayName, style = MaterialTheme.typography.headlineMedium)
                    Text(
                        formatDate(detail.rows.session.startedAt),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DestructiveButton("Delete", onClick = { confirmDelete = true })
            }
        }
        item {
            TabRow(selectedTabIndex = tab) {
                tabs.forEachIndexed { index, label ->
                    Tab(selected = tab == index, onClick = { tab = index }, text = { Text(label) })
                }
            }
        }
        item {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Export", modifier = Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.labelLarge)
                SessionExportFormat.entries.forEach { format ->
                    OutlinedButton(onClick = { onExport(format) }) { Text(format.name) }
                }
            }
        }
        when (tab) {
            0 -> item { SummaryTab(detail) }
            1 -> item {
                GraphsTab(
                    detail = detail,
                    scaleMode = ChartScaleMode.valueOf(graphScaleName),
                    onScaleMode = { graphScaleName = it.name },
                )
            }
            2 -> item { AnalysisTab(detail.samples) }
            else -> item { QualityTab(detail) }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this session?") },
            text = { Text("The session and all of its stored samples will be permanently removed.") },
            confirmButton = { TextButton(onClick = onDelete) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SummaryTab(detail: SessionDetailUi) {
    val summary = remember(detail.rows.session.id, detail.samples) {
        MetricCalculations.sessionSummary(detail.samples)
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SummaryTile("Average FPS", summary.averageFps, "fps", Modifier.weight(1f))
            SummaryTile("1% low", summary.onePercentLow, "fps", Modifier.weight(1f))
            SummaryTile("0.1% low", summary.pointOnePercentLow, "fps", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SummaryTile("Average CPU", summary.averageCpu, "%", Modifier.weight(1f))
            SummaryTile("Average GPU", summary.averageGpu, "%", Modifier.weight(1f))
            SummaryTile("Peak power", summary.peakPower, "W", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SummaryTile("Max CPU temp", summary.maxCpuTemp, "C", Modifier.weight(1f))
            SummaryTile("Max GPU temp", summary.maxGpuTemp, "C", Modifier.weight(1f))
            SummaryTile("Battery used", summary.batteryConsumed, "%", Modifier.weight(1f))
        }
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("Session identity", style = MaterialTheme.typography.titleMedium)
                DetailLine("Package", detail.rows.game.packageName)
                DetailLine("Access", detail.rows.session.accessMode.name)
                DetailLine("Status", detail.rows.session.status.name)
                DetailLine(
                    "Duration",
                    formatDuration(
                        detail.rows.session.durationMillis
                            ?: detail.rows.session.endedAt?.minus(detail.rows.session.startedAt)
                            ?: summary.durationMillis,
                    ),
                )
                DetailLine("Samples", detail.samples.size.toString())
                DetailLine("Device", "${detail.rows.session.deviceManufacturer} ${detail.rows.session.deviceModel}")
                DetailLine("Android", detail.rows.session.androidVersion)
                detail.rows.session.notes?.let { DetailLine("Notes", it) }
            }
        }
        val frametime = summary.frametime
        if (frametime != null) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("Frame pacing", style = MaterialTheme.typography.titleMedium)
                    DetailLine("Median", "%.2f ms".format(frametime.medianMs))
                    DetailLine("P95 / P99", "%.2f / %.2f ms".format(frametime.percentile95Ms, frametime.percentile99Ms))
                    DetailLine("Std. deviation", "%.2f ms".format(frametime.standardDeviationMs))
                    DetailLine("Jank / spikes", "${frametime.jankCount} / ${frametime.spikeCount}")
                }
            }
        }
    }
}

@Composable
private fun SummaryTile(label: String, value: Double?, unit: String, modifier: Modifier = Modifier) {
    MetricTile(
        label,
        value?.let { String.format(Locale.getDefault(), "%.1f %s", it, unit) } ?: "Unavailable",
        modifier,
    )
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.width(120.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun GraphsTab(
    detail: SessionDetailUi,
    scaleMode: ChartScaleMode,
    onScaleMode: (ChartScaleMode) -> Unit,
) {
    val availableMetrics = remember(detail.samples) {
        MetricId.entries.filter { metric -> detail.samples.any { it.value(metric) != null } }
    }
    var selectedMetrics by remember(detail.rows.session.id) {
        mutableStateOf(
            availableMetrics.filter { it in DEFAULT_GRAPH_METRICS }.take(4).toSet()
                .ifEmpty { availableMetrics.take(4).toSet() },
        )
    }
    var modeName by rememberSaveable(detail.rows.session.id) { mutableStateOf(ChartLayoutMode.COMBINED.name) }
    val mode = ChartLayoutMode.valueOf(modeName)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (availableMetrics.isEmpty()) {
            EmptyState("No graphable readings", "This session contains rows, but every metric value is unavailable.")
            return@Column
        }
        Text("Layout", style = MaterialTheme.typography.titleMedium)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ChartLayoutMode.entries.forEach { value ->
                FilterChip(selected = mode == value, onClick = { modeName = value.name }, label = { Text(value.label) })
            }
        }
        Text("Scale", style = MaterialTheme.typography.titleMedium)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ChartScaleMode.entries.forEach { value ->
                FilterChip(selected = scaleMode == value, onClick = { onScaleMode(value) }, label = { Text(value.label) })
            }
        }
        Text("Metrics", style = MaterialTheme.typography.titleMedium)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            availableMetrics.forEach { metric ->
                FilterChip(
                    selected = metric in selectedMetrics,
                    onClick = {
                        selectedMetrics = if (metric in selectedMetrics) selectedMetrics - metric else selectedMetrics + metric
                    },
                    label = { Text(metric.displayName()) },
                )
            }
        }
        if (selectedMetrics.isEmpty()) {
            EmptyState("No metrics selected", "Select at least one available metric above.")
        } else {
            GraphDashboard(
                samples = detail.samples,
                metrics = availableMetrics.filter(selectedMetrics::contains),
                mode = mode,
                scaleMode = scaleMode,
            )
        }
    }
}

@Composable
private fun AnalysisTab(samples: List<SampleData>) {
    val findings = remember(samples) { RuleBasedAnalyzer.analyze(samples) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Deterministic rules only; unavailable metrics do not create findings.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (findings.isEmpty()) {
            EmptyState(
                "No rule matched",
                if (samples.size < 3) "At least three stored samples are required." else "Available samples did not satisfy an analysis rule.",
            )
        } else {
            findings.forEach { FindingCard(it) }
        }
    }
}

@Composable
private fun FindingCard(finding: AnalysisFinding) {
    val color = when (finding.confidence) {
        Confidence.HIGH -> PacBad
        Confidence.MEDIUM -> PacWarn
        Confidence.LOW -> MaterialTheme.colorScheme.primary
    }
    Surface(shape = RoundedCornerShape(16.dp), color = color.copy(alpha = 0.1f)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    finding.type.name.replace('_', ' '),
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.Bold,
                )
                StatusPill(finding.confidence.name, color)
            }
            Text(finding.reason)
            Text(
                "${formatDate(finding.startTimestamp)} | ${finding.metrics.joinToString { it.displayName() }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun QualityTab(detail: SessionDetailUi) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("Collector summary", style = MaterialTheme.typography.titleMedium)
                Text(
                    detail.rows.session.dataQualitySummary.ifBlank { "No data-quality summary was stored." },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        MetricId.entries.forEach { metric ->
            val count = detail.samples.count { it.value(metric) != null }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(metric.displayName(), Modifier.weight(1f))
                Text(
                    "$count / ${detail.samples.size} available",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (count > 0) PacGood else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
        }
    }
}

private val DEFAULT_GRAPH_METRICS = setOf(
    MetricId.FPS,
    MetricId.FRAME_TIME,
    MetricId.CPU_USAGE,
    MetricId.GPU_USAGE,
)
