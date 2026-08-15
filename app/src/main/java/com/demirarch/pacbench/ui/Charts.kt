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

enum class ChartLayoutMode(val label: String) {
    COMBINED("Combined"),
    SEPARATE("Separate"),
    GRID("Grid"),
}

private data class ChartViewport(val start: Float = 0f, val end: Float = 1f) {
    val span: Float get() = end - start
}

private data class ChartPoint(val timestamp: Long, val value: Double)

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
private fun MetricChart(
    samples: List<SampleData>,
    metrics: List<MetricId>,
    viewport: ChartViewport,
    crosshair: Long,
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

    Column(
        modifier = modifier
            .background(surface, RoundedCornerShape(16.dp))
            .padding(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            metrics.forEachIndexed { index, metric ->
                Text(
                    metric.displayName(),
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
                .pointerInput(samples, viewport) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val oldSpan = viewport.span
                        val newSpan = (oldSpan / zoom).coerceIn(0.02f, 1f)
                        val anchorRatio = (centroid.x / size.width).coerceIn(0f, 1f)
                        val anchor = viewport.start + oldSpan * anchorRatio
                        var start = anchor - newSpan * anchorRatio - pan.x / size.width * newSpan
                        start = start.coerceIn(0f, 1f - newSpan)
                        onViewport(ChartViewport(start, start + newSpan))
                    }
                }
                .pointerInput(samples, viewport) {
                    detectTapGestures(
                        onDoubleTap = { onViewport(ChartViewport()) },
                        onTap = { offset ->
                            val fraction = viewport.start + (offset.x / size.width).coerceIn(0f, 1f) * viewport.span
                            val target = firstTime + (duration * fraction).toLong()
                            samples.minByOrNull { kotlin.math.abs(it.timestamp - target) }?.let { onCrosshair(it.timestamp) }
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

            val visibleStart = firstTime + (duration * viewport.start).toLong()
            val visibleEnd = firstTime + (duration * viewport.end).toLong()
            metrics.forEachIndexed { index, metric ->
                val visible = series[metric].orEmpty().filter { it.timestamp in visibleStart..visibleEnd }
                if (visible.isEmpty()) return@forEachIndexed
                val reduced = downsample(visible, (widthPx / 3).coerceAtLeast(32))
                val minimum = visible.minOf(ChartPoint::value)
                val maximum = visible.maxOf(ChartPoint::value)
                val valueSpan = (maximum - minimum).takeIf { it > 0.000001 } ?: 1.0
                val timeSpan = (visibleEnd - visibleStart).coerceAtLeast(1L)
                val path = Path()
                reduced.forEachIndexed { pointIndex, point ->
                    val x = ((point.timestamp - visibleStart).toFloat() / timeSpan) * size.width
                    val y = size.height - ((point.value - minimum) / valueSpan).toFloat() * size.height
                    if (pointIndex == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(
                    path,
                    chartColors[index.mod(chartColors.size)],
                    style = Stroke(width = if (metrics.size == 1) 3f else 2.25f, cap = StrokeCap.Round),
                )
            }
            if (crosshair in visibleStart..visibleEnd) {
                val x = (crosshair - visibleStart).toFloat() / (visibleEnd - visibleStart).coerceAtLeast(1L) * size.width
                drawLine(crosshairColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2f)
                metrics.forEachIndexed { index, metric ->
                    val points = series[metric].orEmpty()
                    val point = points.minByOrNull { kotlin.math.abs(it.timestamp - crosshair) } ?: return@forEachIndexed
                    val visible = points.filter { it.timestamp in visibleStart..visibleEnd }
                    if (visible.isEmpty()) return@forEachIndexed
                    val minimum = visible.minOf(ChartPoint::value)
                    val maximum = visible.maxOf(ChartPoint::value)
                    val span = (maximum - minimum).takeIf { it > 0.000001 } ?: 1.0
                    val y = size.height - ((point.value - minimum) / span).toFloat() * size.height
                    drawCircle(chartColors[index.mod(chartColors.size)], radius = 5f, center = Offset(x, y))
                }
            }
        }
    }
}

private fun downsample(points: List<ChartPoint>, targetBuckets: Int): List<ChartPoint> {
    if (points.size <= targetBuckets * 2) return points
    val bucketSize = ceil(points.size.toDouble() / targetBuckets).toInt().coerceAtLeast(1)
    return buildList {
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
    MetricId.RAM_USED -> ramUsed?.toDouble()
    MetricId.RAM_AVAILABLE -> ramAvailable?.toDouble()
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
