package com.demirarch.pacbench.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import com.demirarch.pacbench.model.MetricId
import com.demirarch.pacbench.model.SampleData
import kotlin.math.ceil
import kotlin.math.max

enum class ChartLayoutMode(val label: String) {
    COMBINED("Combined"),
    SEPARATE("Separate"),
    GRID("Grid"),
}

enum class ChartScaleMode(val label: String) {
    MULTI_AXIS("Multi axis"),
    NORMALIZED("Normalized"),
    RAW("Raw scale"),
}

private data class ChartViewport(val start: Float = 0f, val end: Float = 1f) {
    val span: Float get() = end - start
}

private data class ChartPoint(val timestamp: Long, val value: Double)
private data class ChartBounds(val minimum: Double, val maximum: Double) {
    val span: Double get() = maximum - minimum
}
private data class ComparisonSeries(
    val points: List<ChartPoint>,
    val startedAt: Long,
    val durationMillis: Long,
)

private val chartColors = listOf(
    PacOrangeBright,
    Color(0xFF67D9FF),
    Color(0xFF8BE28B),
    Color(0xFFE08CFF),
    Color(0xFFFFD166),
    Color(0xFFFF718D),
)

@Composable
fun GraphDashboard(
    samples: List<SampleData>,
    metrics: List<MetricId>,
    mode: ChartLayoutMode,
    scaleMode: ChartScaleMode = ChartScaleMode.MULTI_AXIS,
    modifier: Modifier = Modifier,
) {
    var viewport by remember(samples) { mutableStateOf(ChartViewport()) }
    var crosshair by remember(samples) { mutableLongStateOf(Long.MIN_VALUE) }
    val sorted = remember(samples) { samples.sortedBy(SampleData::timestamp) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "Pinch to zoom, drag to pan, tap to inspect",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = {
                viewport = ChartViewport()
                crosshair = Long.MIN_VALUE
            }) { Text("Reset view") }
        }
        when (mode) {
            ChartLayoutMode.COMBINED -> MetricChart(
                samples = sorted,
                metrics = metrics,
                viewport = viewport,
                crosshair = crosshair,
                scaleMode = scaleMode,
                onViewport = { viewport = it },
                onCrosshair = { crosshair = it },
                height = 250,
            )
            ChartLayoutMode.SEPARATE -> metrics.forEach { metric ->
                MetricChart(
                    samples = sorted,
                    metrics = listOf(metric),
                    viewport = viewport,
                    crosshair = crosshair,
                    scaleMode = scaleMode,
                    onViewport = { viewport = it },
                    onCrosshair = { crosshair = it },
                    height = 160,
                )
            }
            ChartLayoutMode.GRID -> metrics.chunked(2).forEach { rowMetrics ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    rowMetrics.forEach { metric ->
                        MetricChart(
                            samples = sorted,
                            metrics = listOf(metric),
                            viewport = viewport,
                            crosshair = crosshair,
                            scaleMode = scaleMode,
                            onViewport = { viewport = it },
                            onCrosshair = { crosshair = it },
                            height = 155,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (rowMetrics.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        if (crosshair != Long.MIN_VALUE && sorted.isNotEmpty()) {
            val sample = sorted.minByOrNull { kotlin.math.abs(it.timestamp - crosshair) }
            if (sample != null) {
                Text(
                    formatDate(sample.timestamp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                metrics.chunked(3).forEach { metricRow ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        metricRow.forEach { metric ->
                            MetricTile(
                                label = metric.displayName(),
                                value = formatMetric(sample.value(metric), metric),
                                modifier = Modifier.weight(1f),
                                accent = chartColors[metrics.indexOf(metric).mod(chartColors.size)],
                            )
                        }
                        repeat(3 - metricRow.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
fun ComparisonOverlayChart(
    first: List<SampleData>,
    second: List<SampleData>,
    metric: MetricId = MetricId.FPS,
    firstStartedAt: Long = first.minOfOrNull(SampleData::timestamp) ?: 0L,
    firstDurationMillis: Long? = null,
    secondStartedAt: Long = second.minOfOrNull(SampleData::timestamp) ?: 0L,
    secondDurationMillis: Long? = null,
    modifier: Modifier = Modifier,
) {
    val series = remember(
        first,
        second,
        metric,
        firstStartedAt,
        firstDurationMillis,
        secondStartedAt,
        secondDurationMillis,
    ) {
        listOf(
            Triple(first, firstStartedAt, firstDurationMillis),
            Triple(second, secondStartedAt, secondDurationMillis),
        ).map { (samples, sessionStartedAt, sessionDurationMillis) ->
            val sorted = samples.sortedBy(SampleData::timestamp)
            ComparisonSeries(
                points = sorted.mapNotNull { sample ->
                    sample.value(metric)?.takeIf(Double::isFinite)?.let { ChartPoint(sample.timestamp, it) }
                },
                startedAt = sessionStartedAt,
                durationMillis = (sessionDurationMillis
                    ?: ((sorted.lastOrNull()?.timestamp ?: sessionStartedAt) - sessionStartedAt))
                    .coerceAtLeast(1L),
            )
        }
    }
    val allValues = series.flatMap { it.points }.map(ChartPoint::value)
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
    Column(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f), RoundedCornerShape(16.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                "A ${metric.displayName()}${if (series[0].points.isEmpty()) " unavailable" else ""}",
                color = PacOrangeBright,
            )
            Text(
                "B ${metric.displayName()}${if (series[1].points.isEmpty()) " unavailable" else ""}",
                color = Color(0xFF67D9FF),
            )
        }
        if (allValues.isEmpty()) {
            Text("Comparison graph unavailable for this metric.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        val bounds = chartBounds(allValues)
        val maximumDuration = series.maxOf(ComparisonSeries::durationMillis)
        Canvas(Modifier.fillMaxWidth().height(190.dp)) {
            repeat(5) { index ->
                val y = size.height * index / 4f
                drawLine(gridColor, Offset(0f, y), Offset(size.width, y))
            }
            series.forEachIndexed { index, session ->
                if (session.points.isEmpty()) return@forEachIndexed
                val reduced = downsample(session.points, (size.width / 3).toInt().coerceAtLeast(32))
                val path = Path()
                reduced.forEachIndexed { pointIndex, point ->
                    val x = (point.timestamp - session.startedAt).toFloat() / maximumDuration * size.width
                    val y = size.height - ((point.value - bounds.minimum) / bounds.span).toFloat() * size.height
                    if (pointIndex == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                val color = if (index == 0) PacOrangeBright else Color(0xFF67D9FF)
                if (reduced.size == 1) {
                    val point = reduced.single()
                    val x = (point.timestamp - session.startedAt).toFloat() / maximumDuration * size.width
                    val y = size.height - ((point.value - bounds.minimum) / bounds.span).toFloat() * size.height
                    drawCircle(color, radius = 5f, center = Offset(x, y))
                } else {
                    drawPath(path, color, style = Stroke(width = 3f, cap = StrokeCap.Round))
                }
            }
        }
    }
}

@Composable
private fun MetricChart(
    samples: List<SampleData>,
    metrics: List<MetricId>,
    viewport: ChartViewport,
    crosshair: Long,
    scaleMode: ChartScaleMode,
    onViewport: (ChartViewport) -> Unit,
    onCrosshair: (Long) -> Unit,
    height: Int,
    modifier: Modifier = Modifier,
) {
    val series = remember(samples, metrics) {
        metrics.associateWith { metric ->
            samples.mapNotNull { sample -> sample.value(metric)?.takeIf(Double::isFinite)?.let { ChartPoint(sample.timestamp, it) } }
        }
    }
    val hasData = series.values.any(List<ChartPoint>::isNotEmpty)
    var widthPx by remember { mutableStateOf(1) }
    val surface = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
    val crosshairColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
    val firstTime = samples.firstOrNull()?.timestamp ?: 0L
    val lastTime = samples.lastOrNull()?.timestamp ?: firstTime
    val duration = (lastTime - firstTime).coerceAtLeast(1L)
    val currentViewport by rememberUpdatedState(viewport)
    val visibleStart = firstTime + (duration * viewport.start).toLong()
    val visibleEnd = firstTime + (duration * viewport.end).toLong()
    val visibleSeries = series.mapValues { (_, points) ->
        points.filter { it.timestamp in visibleStart..visibleEnd }
    }
    val sharedBounds = chartBounds(visibleSeries.values.flatten().map(ChartPoint::value))
    val boundsByMetric = metrics.associateWith { metric ->
        if (scaleMode == ChartScaleMode.RAW) {
            sharedBounds
        } else {
            chartBounds(visibleSeries[metric].orEmpty().map(ChartPoint::value))
        }
    }

    Column(
        modifier = modifier
            .background(surface, RoundedCornerShape(16.dp))
            .padding(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            metrics.forEachIndexed { index, metric ->
                val bounds = boundsByMetric.getValue(metric)
                val scaleLabel = when (scaleMode) {
                    ChartScaleMode.RAW -> "shared ${formatAxis(bounds.minimum)}-${formatAxis(bounds.maximum)}"
                    ChartScaleMode.MULTI_AXIS -> "${formatAxis(bounds.minimum)}-${formatAxis(bounds.maximum)}"
                    ChartScaleMode.NORMALIZED -> "0-100%"
                }
                Text(
                    "${metric.displayName()} $scaleLabel",
                    color = chartColors[index.mod(chartColors.size)],
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        if (!hasData) {
            Text(
                "No samples contain the selected metric.",
                modifier = Modifier.height(height.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height.dp)
                .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
                .pointerInput(samples) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val activeViewport = currentViewport
                        val oldSpan = activeViewport.span
                        val newSpan = (oldSpan / zoom).coerceIn(0.02f, 1f)
                        val anchorRatio = (centroid.x / size.width).coerceIn(0f, 1f)
                        val anchor = activeViewport.start + oldSpan * anchorRatio
                        var start = anchor - newSpan * anchorRatio - pan.x / size.width * newSpan
                        start = start.coerceIn(0f, 1f - newSpan)
                        onViewport(ChartViewport(start, start + newSpan))
                    }
                }
                .pointerInput(samples) {
                    detectTapGestures(
                        onDoubleTap = { onViewport(ChartViewport()) },
                        onTap = { offset ->
                            val activeViewport = currentViewport
                            val fraction = activeViewport.start +
                                (offset.x / size.width).coerceIn(0f, 1f) * activeViewport.span
                            val target = firstTime + (duration * fraction).toLong()
                            val tapStart = firstTime + (duration * activeViewport.start).toLong()
                            val tapEnd = firstTime + (duration * activeViewport.end).toLong()
                            samples.asSequence()
                                .filter { it.timestamp in tapStart..tapEnd }
                                .minByOrNull { kotlin.math.abs(it.timestamp - target) }
                                ?.let { onCrosshair(it.timestamp) }
                        },
                    )
                },
        ) {
            repeat(5) { index ->
                val y = size.height * index / 4f
                drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            }
            repeat(7) { index ->
                val x = size.width * index / 6f
                drawLine(grid, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
            }

            metrics.forEachIndexed { index, metric ->
                val visible = visibleSeries[metric].orEmpty()
                if (visible.isEmpty()) return@forEachIndexed
                val reduced = downsample(visible, (widthPx / 3).coerceAtLeast(32))
                val bounds = boundsByMetric.getValue(metric)
                val timeSpan = (visibleEnd - visibleStart).coerceAtLeast(1L)
                val path = Path()
                reduced.forEachIndexed { pointIndex, point ->
                    val x = ((point.timestamp - visibleStart).toFloat() / timeSpan) * size.width
                    val y = size.height - ((point.value - bounds.minimum) / bounds.span).toFloat() * size.height
                    if (pointIndex == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                val color = chartColors[index.mod(chartColors.size)]
                if (reduced.size == 1) {
                    val point = reduced.single()
                    val x = ((point.timestamp - visibleStart).toFloat() / timeSpan) * size.width
                    val y = size.height - ((point.value - bounds.minimum) / bounds.span).toFloat() * size.height
                    drawCircle(color, radius = 5f, center = Offset(x, y))
                } else {
                    drawPath(
                        path,
                        color,
                        style = Stroke(width = if (metrics.size == 1) 3f else 2.25f, cap = StrokeCap.Round),
                    )
                }
            }
            if (crosshair in visibleStart..visibleEnd) {
                val x = (crosshair - visibleStart).toFloat() / (visibleEnd - visibleStart).coerceAtLeast(1L) * size.width
                drawLine(crosshairColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2f)
                metrics.forEachIndexed { index, metric ->
                    val points = visibleSeries[metric].orEmpty()
                    val point = points.firstOrNull { it.timestamp == crosshair } ?: return@forEachIndexed
                    val bounds = boundsByMetric.getValue(metric)
                    val y = size.height - ((point.value - bounds.minimum) / bounds.span).toFloat() * size.height
                    drawCircle(chartColors[index.mod(chartColors.size)], radius = 5f, center = Offset(x, y))
                }
            }
        }
    }
}

private fun chartBounds(values: List<Double>): ChartBounds {
    if (values.isEmpty()) return ChartBounds(0.0, 1.0)
    val minimum = values.min()
    val maximum = values.max()
    if (maximum - minimum > 0.000001) return ChartBounds(minimum, maximum)
    val padding = max(kotlin.math.abs(minimum) * 0.05, 1.0)
    return ChartBounds(minimum - padding, maximum + padding)
}

private fun formatAxis(value: Double): String = when {
    kotlin.math.abs(value) >= 1_000_000 -> "%.1fM".format(value / 1_000_000)
    kotlin.math.abs(value) >= 1_000 -> "%.1fk".format(value / 1_000)
    else -> "%.1f".format(value)
}

private fun downsample(points: List<ChartPoint>, targetBuckets: Int): List<ChartPoint> {
    if (points.size <= targetBuckets * 2) return points
    val bucketSize = ceil(points.size.toDouble() / targetBuckets).toInt().coerceAtLeast(1)
    val sampled = buildList {
        points.chunked(bucketSize).forEach { bucket ->
            val low = bucket.minBy(ChartPoint::value)
            val high = bucket.maxBy(ChartPoint::value)
            if (low.timestamp <= high.timestamp) {
                add(low)
                if (high != low) add(high)
            } else {
                add(high)
                if (high != low) add(low)
            }
        }
    }
    return (listOf(points.first()) + sampled + points.last()).distinct().sortedBy(ChartPoint::timestamp)
}

fun SampleData.value(metric: MetricId): Double? = when (metric) {
    MetricId.FPS -> fps
    MetricId.FRAME_TIME -> frameTime
    MetricId.CPU_USAGE -> cpuUsage
    MetricId.CPU_FREQUENCY -> cpuFrequency
    MetricId.CPU_TEMPERATURE -> cpuTemp
    MetricId.GPU_USAGE -> gpuUsage
    MetricId.GPU_FREQUENCY -> gpuFrequency
    MetricId.GPU_TEMPERATURE -> gpuTemp
    MetricId.RAM_USED -> ramUsed?.toDouble()?.div(BYTES_PER_GIB)
    MetricId.RAM_AVAILABLE -> ramAvailable?.toDouble()?.div(BYTES_PER_GIB)
    MetricId.BATTERY_LEVEL -> batteryLevel
    MetricId.BATTERY_TEMPERATURE -> batteryTemp
    MetricId.VOLTAGE -> voltage
    MetricId.CURRENT -> current
    MetricId.POWER -> powerWatts
    MetricId.DOWNLOAD_RATE -> downloadRate
    MetricId.UPLOAD_RATE -> uploadRate
    MetricId.PING -> ping
    MetricId.THERMAL_STATUS -> thermalState?.toDouble()
}

private const val BYTES_PER_GIB = 1024.0 * 1024 * 1024
