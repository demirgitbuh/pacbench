package com.demirarch.pacbench.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.zIndex
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.demirarch.pacbench.model.BuiltInHudPresets
import com.demirarch.pacbench.model.HudAlignment
import com.demirarch.pacbench.model.HudPreset
import com.demirarch.pacbench.model.HudWidget
import com.demirarch.pacbench.model.HudWidgetType
import com.demirarch.pacbench.model.MetricId
import com.demirarch.pacbench.model.MetricSnapshot
import kotlin.math.roundToInt

@Composable
fun HudScreen(viewModel: PacBenchViewModel, modifier: Modifier = Modifier) {
    val presets by viewModel.presets.collectAsStateWithLifecycle()
    val query by viewModel.presetQuery.collectAsStateWithLifecycle()
    val recording by viewModel.recording.collectAsStateWithLifecycle()
    val access by viewModel.access.collectAsStateWithLifecycle()
    val activePresetId by viewModel.activeHudPresetId.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            PageHeader(
                eyebrow = "Overlay layout",
                title = "HUD",
                detail = "Design persisted presets with actual metric identities.",
                action = { Button(onClick = viewModel::newHudPreset) { Text("New preset") } },
            )
        }
        item {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.38f),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Runtime status", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (recording?.active == true) "Sampling ${recording?.gameName}" else "No active sampler",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        StatusPill(
                            if (recording?.active == true) "LIVE" else "IDLE",
                            if (recording?.active == true) PacGood else MaterialTheme.colorScheme.outline,
                        )
                    }
                    Text(
                        when {
                            !access.overlayDeclared -> "System overlay is unavailable: SYSTEM_ALERT_WINDOW is not declared in the current app manifest. Preset design and storage still work."
                            !access.overlayGranted -> "System overlay permission is not granted. Preset design and storage still work."
                            else -> "Overlay permission is granted. Record + launch can render the selected service preset over the game."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (recording?.active != true) {
                        OutlinedButton(
                            onClick = viewModel::startAutomaticMonitoring,
                            enabled = access.usageAccessGranted,
                        ) { Text("Start automatic detection") }
                    }
                    val latest = recording?.latest
                    if (latest != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(MetricId.FPS, MetricId.CPU_USAGE, MetricId.GPU_USAGE).forEach { metric ->
                                MetricTile(
                                    metric.displayName(),
                                    latest[metric]?.value?.let { formatLiveMetric(it, metric) } ?: "Unavailable",
                                    Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { viewModel.presetQuery.value = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Search presets") },
                singleLine = true,
            )
        }
        if (presets.isEmpty()) {
            item {
                EmptyState(
                    title = "No presets found",
                    detail = "Create a blank custom HUD or clear the current search.",
                    actionLabel = "New preset",
                    onAction = viewModel::newHudPreset,
                )
            }
        } else {
            items(presets, key = HudPreset::id) { preset ->
                PresetCard(
                    preset = preset,
                    active = preset.id == activePresetId,
                    onUse = { viewModel.useHudPreset(preset) },
                    onEdit = { viewModel.openHudPreset(preset) },
                    onDelete = { viewModel.deleteHudPreset(preset) },
                )
            }
        }
    }
}

@Composable
private fun PresetCard(
    preset: HudPreset,
    active: Boolean,
    onUse: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val builtIn = BuiltInHudPresets.all.any { it.id == preset.id }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(preset.name, style = MaterialTheme.typography.titleLarge)
                    Text(
                        "${preset.widgets.size} widgets | ${preset.canvasWidth.roundToInt()} x ${preset.canvasHeight.roundToInt()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusPill(
                    if (active) "ACTIVE" else if (builtIn) "BUILT-IN" else "CUSTOM",
                    if (active) PacGood else if (builtIn) PacOrangeBright else MaterialTheme.colorScheme.outline,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onUse, enabled = !active) { Text(if (active) "In use" else "Use") }
                Button(onClick = onEdit, modifier = Modifier.weight(1f)) {
                    Text(if (builtIn) "Edit a copy" else "Open designer")
                }
                if (!builtIn) {
                    OutlinedButton(onClick = onDelete) { Text("Delete") }
                }
            }
        }
    }
}

@Composable
fun HudDesignerScreen(viewModel: PacBenchViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.editor.collectAsStateWithLifecycle()
    val recording by viewModel.recording.collectAsStateWithLifecycle()
    val editor = state ?: return

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        DesignerToolbar(editor, viewModel)
        HorizontalDivider()
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 900.dp
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    HudCanvas(
                        state = editor,
                        latest = recording?.latest,
                        viewModel = viewModel,
                        modifier = Modifier.weight(1f).fillMaxHeight().padding(16.dp),
                    )
                    VerticalDivider(Modifier.fillMaxHeight().width(1.dp))
                    PropertyPanel(
                        state = editor,
                        viewModel = viewModel,
                        modifier = Modifier.width(360.dp).fillMaxHeight(),
                    )
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    HudCanvas(
                        state = editor,
                        latest = recording?.latest,
                        viewModel = viewModel,
                        modifier = Modifier.fillMaxWidth().height(370.dp).padding(12.dp),
                    )
                    HorizontalDivider()
                    PropertyPanel(
                        state = editor,
                        viewModel = viewModel,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun DesignerToolbar(state: HudEditorUiState, viewModel: PacBenchViewModel) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = viewModel::closeEditor) { Text("Close") }
        Text("HUD DESIGNER", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        OutlinedButton(onClick = viewModel::undoEditor, enabled = state.canUndo) { Text("Undo") }
        OutlinedButton(onClick = viewModel::redoEditor, enabled = state.canRedo) { Text("Redo") }
        OutlinedButton(onClick = viewModel::duplicateSelectedWidget, enabled = state.selectedWidget != null) { Text("Duplicate") }
        OutlinedButton(onClick = viewModel::deleteSelectedWidget, enabled = state.selectedWidget != null) { Text("Delete") }
        OutlinedButton(onClick = viewModel::resetEditor) { Text("Reset") }
        Button(onClick = viewModel::saveEditor) { Text("Save preset") }
    }
}

@Composable
private fun HudCanvas(
    state: HudEditorUiState,
    latest: MetricSnapshot?,
    viewModel: PacBenchViewModel,
    modifier: Modifier = Modifier,
) {
    val preset = state.preset
    BoxWithConstraints(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF080808)),
        contentAlignment = Alignment.Center,
    ) {
        val ratio = preset.canvasWidth / preset.canvasHeight
        val availableRatio = maxWidth.value / maxHeight.value
        val canvasWidth = if (availableRatio > ratio) maxHeight * ratio else maxWidth
        val canvasHeight = canvasWidth / ratio
        val density = LocalDensity.current
        val widthPx = with(density) { canvasWidth.toPx() }
        val scale = widthPx / preset.canvasWidth

        Box(
            modifier = Modifier
                .width(canvasWidth)
                .height(canvasHeight)
                .border(1.dp, PacOrange.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                .background(Color(0xFF101010))
                .clickable { viewModel.selectWidget(null) },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                if (state.snapToGrid && preset.gridSize > 1f) {
                    val step = preset.gridSize * scale
                    var x = step
                    while (x < size.width) {
                        drawLine(PacGrid.copy(alpha = 0.55f), Offset(x, 0f), Offset(x, size.height), 1f)
                        x += step
                    }
                    var y = step
                    while (y < size.height) {
                        drawLine(PacGrid.copy(alpha = 0.55f), Offset(0f, y), Offset(size.width, y), 1f)
                        y += step
                    }
                }
                if (preset.showSafeArea) {
                    drawRect(
                        PacWarn.copy(alpha = 0.75f),
                        topLeft = Offset(size.width * 0.05f, size.height * 0.05f),
                        size = androidx.compose.ui.geometry.Size(size.width * 0.9f, size.height * 0.9f),
                        style = Stroke(width = 2f),
                    )
                }
            }
            preset.widgets.sortedBy(HudWidget::layer).forEach { widget ->
                val selected = state.selectedWidgetId == widget.id
                val widgetWidth = with(density) { (widget.width * scale).toDp() }
                val widgetHeight = with(density) { (widget.height * scale).toDp() }
                val cornerRadius = with(density) { (widget.cornerRadius * scale).toDp() }
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (widget.x * scale).roundToInt(),
                                (widget.y * scale).roundToInt(),
                            )
                        }
                        .size(widgetWidth, widgetHeight)
                        .zIndex(widget.layer.toFloat())
                        .alpha(if (widget.visible) 1f else 0.25f)
                        .clip(RoundedCornerShape(cornerRadius.coerceAtLeast(2.dp)))
                        .background(Color.Black.copy(alpha = widget.backgroundOpacity))
                        .border(
                            width = if (selected) 2.dp else 1.dp,
                            color = if (selected) PacOrangeBright else Color.White.copy(alpha = 0.18f),
                            shape = RoundedCornerShape(cornerRadius.coerceAtLeast(2.dp)),
                        )
                        .clickable { viewModel.selectWidget(widget.id) }
                        .pointerInput(widget.id, widget.locked, scale) {
                            detectDragGestures(
                                onDragStart = {
                                    viewModel.selectWidget(widget.id)
                                    viewModel.beginWidgetGesture()
                                },
                                onDragEnd = viewModel::commitWidgetGesture,
                                onDragCancel = viewModel::commitWidgetGesture,
                            ) { change, amount ->
                                change.consume()
                                viewModel.moveWidget(widget.id, amount.x / scale, amount.y / scale)
                            }
                        },
                ) {
                    HudWidgetPreview(widget, latest, scale)
                    if (selected) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(22.dp)
                                .background(if (widget.locked) PacBad else PacOrangeBright, CircleShape)
                                .pointerInput(widget.id, widget.locked, scale) {
                                    detectDragGestures(
                                        onDragStart = { viewModel.beginWidgetGesture() },
                                        onDragEnd = viewModel::commitWidgetGesture,
                                        onDragCancel = viewModel::commitWidgetGesture,
                                    ) { change, amount ->
                                        change.consume()
                                        viewModel.resizeWidget(widget.id, amount.x / scale, amount.y / scale)
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(if (widget.locked) "L" else "+", color = Color.Black, fontWeight = FontWeight.Black)
                        }
                    }
                }
            }
        }
        Text(
            "${preset.canvasWidth.roundToInt()} x ${preset.canvasHeight.roundToInt()} model units",
            modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.6f),
        )
    }
}

@Composable
private fun HudWidgetPreview(widget: HudWidget, latest: MetricSnapshot?, scale: Float) {
    val metric = widget.metrics.firstOrNull() ?: MetricId.FPS
    val reading = latest?.get(metric)
    val value = reading?.value?.let { raw ->
        val number = String.format(java.util.Locale.getDefault(), "%.${widget.decimalPrecision}f", raw)
        val unit = widget.metricUnit ?: metric.defaultUnit
        if (widget.showUnit && unit.isNotBlank()) "$number $unit" else number
    } ?: "No sample"
    val alignment = when (widget.alignment) {
        HudAlignment.START -> TextAlign.Start
        HudAlignment.CENTER -> TextAlign.Center
        HudAlignment.END -> TextAlign.End
    }
    val horizontal = when (widget.alignment) {
        HudAlignment.START -> Alignment.Start
        HudAlignment.CENTER -> Alignment.CenterHorizontally
        HudAlignment.END -> Alignment.End
    }
    Column(
        modifier = Modifier.fillMaxSize().padding((widget.padding * scale).coerceAtMost(14f).dp),
        horizontalAlignment = horizontal,
        verticalArrangement = Arrangement.Center,
    ) {
        if (widget.showLabel) {
            Text(
                metric.displayName().uppercase(),
                color = PacOrangeBright.copy(alpha = widget.textOpacity),
                fontSize = (9f * scale.coerceAtMost(1.4f)).coerceAtLeast(7f).sp,
                maxLines = 1,
            )
        }
        Text(
            if (widget.type == HudWidgetType.SPACER || widget.type == HudWidgetType.DIVIDER) widget.type.name else value,
            modifier = Modifier.fillMaxWidth(),
            textAlign = alignment,
            color = Color.White.copy(alpha = widget.textOpacity),
            fontSize = (widget.fontSize * scale).coerceIn(8f, 30f).sp,
            fontWeight = if (widget.fontWeight >= 700) FontWeight.Bold else FontWeight.Medium,
            maxLines = if (widget.type == HudWidgetType.MULTI_METRIC_COLUMN) 3 else 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PropertyPanel(
    state: HudEditorUiState,
    viewModel: PacBenchViewModel,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Canvas", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = state.preset.name,
            onValueChange = viewModel::renameEditor,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Preset name") },
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.preset.canvasWidth > state.preset.canvasHeight,
                onClick = { viewModel.setEditorOrientation(false) },
                label = { Text("Landscape") },
            )
            FilterChip(
                selected = state.preset.canvasHeight > state.preset.canvasWidth,
                onClick = { viewModel.setEditorOrientation(true) },
                label = { Text("Portrait") },
            )
        }
        ToggleLine("Safe area", state.preset.showSafeArea, viewModel::setSafeArea)
        ToggleLine("Lock overlay by default", state.preset.lockedByDefault, viewModel::setPresetLocked)
        ToggleLine("Snap to grid", state.snapToGrid, viewModel::setSnapToGrid)
        PropertySlider(
            label = "Grid size",
            value = state.preset.gridSize,
            range = 1f..32f,
            valueText = "${state.preset.gridSize.roundToInt()}",
            onChange = viewModel::setGridSize,
        )
        Button(onClick = { viewModel.addWidget() }, modifier = Modifier.fillMaxWidth()) { Text("Add metric widget") }
        HorizontalDivider()

        val widget = state.selectedWidget
        if (widget == null) {
            Text("Widget properties", style = MaterialTheme.typography.titleLarge)
            EmptyState("Nothing selected", "Tap a widget on the canvas, or add a new metric widget.")
            return@Column
        }

        Text("Widget properties", style = MaterialTheme.typography.titleLarge)
        Text(widget.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = viewModel::duplicateSelectedWidget, Modifier.weight(1f)) { Text("Duplicate") }
            OutlinedButton(onClick = viewModel::deleteSelectedWidget, Modifier.weight(1f)) { Text("Delete") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { viewModel.moveSelectedLayer(-1) }, Modifier.weight(1f)) { Text("Layer down") }
            OutlinedButton(onClick = { viewModel.moveSelectedLayer(1) }, Modifier.weight(1f)) { Text("Layer up") }
        }
        ToggleLine("Locked", widget.locked) { value -> viewModel.updateSelectedWidget { it.copy(locked = value) } }
        ToggleLine("Visible", widget.visible) { value -> viewModel.updateSelectedWidget { it.copy(visible = value) } }
        ToggleLine("Show label", widget.showLabel) { value -> viewModel.updateSelectedWidget { it.copy(showLabel = value) } }
        ToggleLine("Show unit", widget.showUnit) { value -> viewModel.updateSelectedWidget { it.copy(showUnit = value) } }

        Text("Type", style = MaterialTheme.typography.titleMedium)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            HudWidgetType.entries.forEach { type ->
                FilterChip(
                    selected = widget.type == type,
                    onClick = { viewModel.updateSelectedWidget { it.copy(type = type) } },
                    label = { Text(type.name.replace('_', ' ')) },
                )
            }
        }
        Text("Primary metric", style = MaterialTheme.typography.titleMedium)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            MetricId.entries.forEach { metric ->
                FilterChip(
                    selected = widget.metrics.firstOrNull() == metric,
                    onClick = { viewModel.updateSelectedWidget { it.copy(metrics = listOf(metric)) } },
                    label = { Text(metric.displayName()) },
                )
            }
        }
        Text("Alignment", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            HudAlignment.entries.forEach { alignment ->
                FilterChip(
                    selected = widget.alignment == alignment,
                    onClick = { viewModel.updateSelectedWidget { it.copy(alignment = alignment) } },
                    label = { Text(alignment.name) },
                )
            }
        }
        PropertySlider(
            "Font size",
            widget.fontSize,
            8f..48f,
            "${widget.fontSize.roundToInt()} sp",
        ) { value -> viewModel.updateSelectedWidget { it.copy(fontSize = value) } }
        PropertySlider(
            "Padding",
            widget.padding,
            0f..24f,
            "${widget.padding.roundToInt()}",
        ) { value -> viewModel.updateSelectedWidget { it.copy(padding = value) } }
        PropertySlider(
            "Corner radius",
            widget.cornerRadius,
            0f..32f,
            "${widget.cornerRadius.roundToInt()}",
        ) { value -> viewModel.updateSelectedWidget { it.copy(cornerRadius = value) } }
        PropertySlider(
            "Background opacity",
            widget.backgroundOpacity,
            0f..1f,
            "${(widget.backgroundOpacity * 100).roundToInt()}%",
        ) { value -> viewModel.updateSelectedWidget { it.copy(backgroundOpacity = value) } }
        PropertySlider(
            "Text opacity",
            widget.textOpacity,
            0.1f..1f,
            "${(widget.textOpacity * 100).roundToInt()}%",
        ) { value -> viewModel.updateSelectedWidget { it.copy(textOpacity = value) } }
        PropertySlider(
            "Decimal precision",
            widget.decimalPrecision.toFloat(),
            0f..3f,
            widget.decimalPrecision.toString(),
            steps = 2,
        ) { value -> viewModel.updateSelectedWidget { it.copy(decimalPrecision = value.roundToInt()) } }
        Text("Refresh interval", style = MaterialTheme.typography.titleMedium)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            listOf(100L, 250L, 500L, 1_000L, 2_000L).forEach { interval ->
                FilterChip(
                    selected = widget.refreshIntervalMillis == interval,
                    onClick = { viewModel.updateSelectedWidget { it.copy(refreshIntervalMillis = interval) } },
                    label = { Text("$interval ms") },
                )
            }
        }
        Text(
            "Position ${widget.x.roundToInt()}, ${widget.y.roundToInt()} | Size ${widget.width.roundToInt()} x ${widget.height.roundToInt()} | Layer ${widget.layer}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ToggleLine(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun PropertySlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    steps: Int = 0,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(label, Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
    }
}
