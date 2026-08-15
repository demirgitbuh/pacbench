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
        require(width > 0 && height > 0)
        require(decimalPrecision in 0..3)
        require(backgroundOpacity in 0f..1f && textOpacity in 0f..1f)
        require(refreshIntervalMillis >= 100)
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
)

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
