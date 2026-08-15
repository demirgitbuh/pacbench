package com.demirarch.pacbench.model

import kotlinx.serialization.Serializable

@Serializable
enum class HudWidgetType {
    TEXT_METRIC,
    COMPACT_METRIC,
    METRIC_WITH_UNIT,
    MINI_GRAPH,
    FRAMETIME_GRAPH,
    HORIZONTAL_BAR,
    VERTICAL_BAR,
    MULTI_METRIC_ROW,
    MULTI_METRIC_COLUMN,
    SPACER,
    DIVIDER,
    CONTAINER,
}

@Serializable
enum class HudAlignment { START, CENTER, END }

@Serializable
data class HudWidget(
    val id: String,
    val type: HudWidgetType,
    val metrics: List<MetricId> = listOf(MetricId.FPS),
    val x: Float = 16f,
    val y: Float = 16f,
    val width: Float = 116f,
    val height: Float = 44f,
    val fontSize: Float = 16f,
    val fontWeight: Int = 600,
    val alignment: HudAlignment = HudAlignment.START,
    val padding: Float = 8f,
    val margin: Float = 0f,
    val cornerRadius: Float = 8f,
    val backgroundOpacity: Float = 0.78f,
    val textOpacity: Float = 1f,
    val metricUnit: String? = null,
    val decimalPrecision: Int = 1,
    val refreshIntervalMillis: Long = 500,
    val showLabel: Boolean = true,
    val showUnit: Boolean = true,
    val graphHistorySeconds: Int = 30,
    val warningThreshold: Double? = null,
    val visible: Boolean = true,
    val locked: Boolean = false,
    val layer: Int = 0,
) {
    init {
        require(id.isNotBlank()) { "Widget ID must not be blank" }
        require(x.isFinite() && y.isFinite() && x >= 0f && y >= 0f) { "Widget position is invalid" }
        require(width.isFinite() && height.isFinite() && width > 0f && height > 0f) { "Widget size is invalid" }
        require(fontSize.isFinite() && fontSize in 6f..96f) { "Widget font size is invalid" }
        require(fontWeight in 100..900) { "Widget font weight is invalid" }
        require(padding.isFinite() && padding in 0f..96f) { "Widget padding is invalid" }
        require(margin.isFinite() && margin in 0f..96f) { "Widget margin is invalid" }
        require(cornerRadius.isFinite() && cornerRadius in 0f..256f) { "Widget corner radius is invalid" }
        require(decimalPrecision in 0..3) { "Widget decimal precision is invalid" }
        require(backgroundOpacity in 0f..1f && textOpacity in 0f..1f)
        require(refreshIntervalMillis in 100L..60_000L) { "Widget refresh interval is invalid" }
        require(graphHistorySeconds in 1..600) { "Widget graph history is invalid" }
        require(warningThreshold == null || warningThreshold.isFinite()) { "Widget warning threshold is invalid" }
    }
}

@Serializable
data class HudPreset(
    val schemaVersion: Int = 1,
    val id: String,
    val name: String,
    val widgets: List<HudWidget>,
    val canvasWidth: Float = 800f,
    val canvasHeight: Float = 360f,
    val gridSize: Float = 8f,
    val showSafeArea: Boolean = true,
    val lockedByDefault: Boolean = true,
) {
    init {
        require(schemaVersion == 1) { "Unsupported HUD schema $schemaVersion" }
        require(id.isNotBlank()) { "Preset ID must not be blank" }
        require(canvasWidth.isFinite() && canvasWidth in 64f..10_000f) { "Canvas width is invalid" }
        require(canvasHeight.isFinite() && canvasHeight in 64f..10_000f) { "Canvas height is invalid" }
        require(gridSize.isFinite() && gridSize in 1f..256f) { "Grid size is invalid" }
        require(widgets.size <= 200) { "HUD contains too many widgets" }
        require(widgets.map(HudWidget::id).distinct().size == widgets.size) { "Widget IDs must be unique" }
        require(widgets.all { it.x + it.width <= canvasWidth && it.y + it.height <= canvasHeight }) {
            "Widget bounds exceed the HUD canvas"
        }
    }
}

object BuiltInHudPresets {
    val all: List<HudPreset> = listOf(
        preset("minimal", "Minimal", listOf(MetricId.FPS, MetricId.BATTERY_TEMPERATURE)),
        preset("competitive", "Competitive", listOf(MetricId.FPS, MetricId.FRAME_TIME, MetricId.PING)),
        preset("thermal", "Thermal", listOf(MetricId.CPU_TEMPERATURE, MetricId.GPU_TEMPERATURE, MetricId.THERMAL_STATUS)),
        preset("benchmark", "Benchmark", listOf(MetricId.FPS, MetricId.CPU_USAGE, MetricId.GPU_USAGE, MetricId.POWER)),
        preset("detailed", "Detailed", listOf(MetricId.FPS, MetricId.FRAME_TIME, MetricId.CPU_USAGE, MetricId.GPU_USAGE, MetricId.RAM_USED, MetricId.POWER)),
    )

    private fun preset(id: String, name: String, metrics: List<MetricId>) = HudPreset(
        id = id,
        name = name,
        widgets = metrics.mapIndexed { index, metric ->
            HudWidget(
                id = "$id-${metric.name.lowercase()}",
                type = if (metric == MetricId.FRAME_TIME) HudWidgetType.FRAMETIME_GRAPH else HudWidgetType.METRIC_WITH_UNIT,
                metrics = listOf(metric),
                x = 16f + (index % 3) * 128f,
                y = 16f + (index / 3) * 56f,
                layer = index,
            )
        },
    )
}
