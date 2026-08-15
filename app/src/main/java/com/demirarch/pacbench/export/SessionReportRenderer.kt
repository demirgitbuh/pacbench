package com.demirarch.pacbench.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import com.demirarch.pacbench.data.local.PerformanceSample
import com.demirarch.pacbench.data.local.SessionWithGameAndSamples
import com.demirarch.pacbench.data.local.toSampleData
import com.demirarch.pacbench.model.MetricCalculations
import com.demirarch.pacbench.model.SampleData
import com.demirarch.pacbench.model.SessionSummary
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

internal class SessionReportRenderer {
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = INK
        textSize = 42f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val headingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = INK
        textSize = 24f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = INK
        textSize = 18f
    }
    private val mutedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MUTED
        textSize = 15f
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ACCENT
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GRID
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    fun writeBitmap(
        rows: SessionWithGameAndSamples,
        file: File,
        format: Bitmap.CompressFormat,
        quality: Int,
    ) {
        require(quality in 0..100)
        val bitmap = Bitmap.createBitmap(BITMAP_WIDTH, BITMAP_HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            drawBitmapHeader(canvas, rows)
            val samples = sortedSamples(rows).map(PerformanceSample::toSampleData)
            drawSummary(
                canvas = canvas,
                summary = MetricCalculations.sessionSummary(samples),
                durationMillis = actualDuration(rows, samples),
                thermalEventsAvailable = samples.any { it.thermalState != null },
                left = BITMAP_MARGIN,
                top = 275f,
                columnWidth = 480f,
                lineHeight = 35f,
                paint = bodyPaint,
            )
            drawChart(
                canvas,
                samples,
                RectF(BITMAP_MARGIN, 645f, BITMAP_WIDTH - BITMAP_MARGIN, 1_090f),
                bodyPaint,
                mutedPaint,
            )
            canvas.drawText(
                "Stored samples: ${samples.size} | Data quality: ${rows.session.dataQualitySummary.ifBlank { "Not provided" }}",
                BITMAP_MARGIN,
                1_155f,
                mutedPaint,
            )
            file.outputStream().buffered().use { output ->
                check(bitmap.compress(format, quality, output)) { "Bitmap report compression failed" }
            }
        } finally {
            bitmap.recycle()
        }
    }

    fun writePdf(rows: SessionWithGameAndSamples, file: File) {
        val document = PdfDocument()
        try {
            var pageNumber = 1
            val samples = sortedSamples(rows)
            val sampleData = samples.map(PerformanceSample::toSampleData)
            val summaryPage = document.startPage(
                PdfDocument.PageInfo.Builder(PDF_PORTRAIT_WIDTH, PDF_PORTRAIT_HEIGHT, pageNumber++).create(),
            )
            drawPdfSummaryPage(summaryPage.canvas, rows, sampleData)
            document.finishPage(summaryPage)

            if (samples.isNotEmpty()) {
                PDF_METRIC_COLUMNS.chunked(PDF_METRICS_PER_GROUP).forEachIndexed { groupIndex, metricColumns ->
                    samples.chunked(PDF_ROWS_PER_PAGE).forEachIndexed { chunkIndex, sampleChunk ->
                        val page = document.startPage(
                            PdfDocument.PageInfo.Builder(PDF_LANDSCAPE_WIDTH, PDF_LANDSCAPE_HEIGHT, pageNumber++).create(),
                        )
                        drawPdfTablePage(
                            canvas = page.canvas,
                            rows = rows,
                            samples = sampleChunk,
                            columns = PDF_BASE_COLUMNS + metricColumns,
                            groupIndex = groupIndex,
                            firstRowIndex = chunkIndex * PDF_ROWS_PER_PAGE,
                            totalRows = samples.size,
                        )
                        document.finishPage(page)
                    }
                }
            }
            file.outputStream().buffered().use { output -> document.writeTo(output) }
        } finally {
            document.close()
        }
    }

    private fun drawBitmapHeader(canvas: Canvas, rows: SessionWithGameAndSamples) {
        canvas.drawRect(0f, 0f, BITMAP_WIDTH.toFloat(), 12f, Paint().apply { color = ACCENT })
        canvas.drawText("PacBench Session Report", BITMAP_MARGIN, 82f, titlePaint)
        canvas.drawText(rows.game.displayName, BITMAP_MARGIN, 132f, headingPaint)
        canvas.drawText(rows.game.packageName, BITMAP_MARGIN, 165f, mutedPaint)
        canvas.drawText("Session ${rows.session.id}", BITMAP_MARGIN, 210f, bodyPaint)
        canvas.drawText(
            "Started: ${formatTimestamp(rows.session.startedAt)} | Ended: ${formatTimestampOrUnavailable(rows.session.endedAt)}",
            BITMAP_MARGIN,
            242f,
            mutedPaint,
        )
    }

    private fun drawPdfSummaryPage(
        canvas: Canvas,
        rows: SessionWithGameAndSamples,
        samples: List<SampleData>,
    ) {
        canvas.drawColor(Color.WHITE)
        val pdfTitle = scaledPaint(titlePaint, 24f)
        val pdfHeading = scaledPaint(headingPaint, 14f)
        val pdfBody = scaledPaint(bodyPaint, 9f)
        val pdfMuted = scaledPaint(mutedPaint, 7.5f)
        canvas.drawRect(0f, 0f, PDF_PORTRAIT_WIDTH.toFloat(), 6f, Paint().apply { color = ACCENT })
        canvas.drawText("PacBench Session Report", PDF_MARGIN, 45f, pdfTitle)
        canvas.drawText(rows.game.displayName, PDF_MARGIN, 72f, pdfHeading)
        canvas.drawText(rows.game.packageName, PDF_MARGIN, 88f, pdfMuted)
        canvas.drawText(
            "Session ${rows.session.id} | ${rows.session.accessMode.name} | ${samples.size} stored samples",
            PDF_MARGIN,
            112f,
            pdfBody,
        )
        canvas.drawText(
            "Started ${formatTimestamp(rows.session.startedAt)} | Ended ${formatTimestampOrUnavailable(rows.session.endedAt)}",
            PDF_MARGIN,
            130f,
            pdfMuted,
        )
        canvas.drawText(
            "Device ${rows.session.deviceManufacturer} ${rows.session.deviceModel} | Android ${rows.session.androidVersion}",
            PDF_MARGIN,
            146f,
            pdfMuted,
        )
        drawSummary(
            canvas,
            MetricCalculations.sessionSummary(samples),
            actualDuration(rows, samples),
            samples.any { it.thermalState != null },
            left = PDF_MARGIN,
            top = 178f,
            columnWidth = 180f,
            lineHeight = 19f,
            paint = pdfBody,
        )
        drawChart(
            canvas,
            samples,
            RectF(PDF_MARGIN, 405f, PDF_PORTRAIT_WIDTH - PDF_MARGIN, 700f),
            pdfBody,
            pdfMuted,
        )
        canvas.drawText(
            "Data quality: ${rows.session.dataQualitySummary.ifBlank { "Not provided" }}",
            PDF_MARGIN,
            735f,
            pdfMuted,
        )
        canvas.drawText("Null sample fields are reported as unavailable; no values are synthesized.", PDF_MARGIN, 754f, pdfMuted)
    }

    private fun drawSummary(
        canvas: Canvas,
        summary: SessionSummary,
        durationMillis: Long?,
        thermalEventsAvailable: Boolean,
        left: Float,
        top: Float,
        columnWidth: Float,
        lineHeight: Float,
        paint: Paint,
    ) {
        val values = listOf(
            "Duration" to formatDuration(durationMillis),
            "Average FPS" to formatMetric(summary.averageFps, "fps"),
            "Median FPS" to formatMetric(summary.medianFps, "fps"),
            "Minimum FPS" to formatMetric(summary.minFps, "fps"),
            "Maximum FPS" to formatMetric(summary.maxFps, "fps"),
            "1% low" to formatMetric(summary.onePercentLow, "fps"),
            "0.1% low" to formatMetric(summary.pointOnePercentLow, "fps"),
            "FPS stability" to formatMetric(summary.fpsStability, "%"),
            "Average CPU" to formatMetric(summary.averageCpu, "%"),
            "Peak CPU" to formatMetric(summary.peakCpu, "%"),
            "Average GPU" to formatMetric(summary.averageGpu, "%"),
            "Peak GPU" to formatMetric(summary.peakGpu, "%"),
            "Peak CPU temperature" to formatMetric(summary.maxCpuTemp, "C"),
            "Peak GPU temperature" to formatMetric(summary.maxGpuTemp, "C"),
            "Peak battery temperature" to formatMetric(summary.maxBatteryTemp, "C"),
            "Average power" to formatMetric(summary.averagePower, "W"),
            "Peak power" to formatMetric(summary.peakPower, "W"),
            "Battery consumed" to formatMetric(summary.batteryConsumed, "%"),
            "Thermal events" to if (thermalEventsAvailable) summary.thermalEventCount.toString() else "Unavailable",
            "P99 frametime" to formatMetric(summary.frametime?.percentile99Ms, "ms"),
        )
        val rowsPerColumn = ceil(values.size / 2.0).toInt()
        values.forEachIndexed { index, (label, value) ->
            val column = index / rowsPerColumn
            val row = index % rowsPerColumn
            canvas.drawText("$label: $value", left + column * columnWidth, top + row * lineHeight, paint)
        }
    }

    private fun drawChart(
        canvas: Canvas,
        samples: List<SampleData>,
        bounds: RectF,
        labelPaint: Paint,
        smallPaint: Paint,
    ) {
        val series = chartSeries(samples)
        canvas.drawRect(bounds, gridPaint)
        if (series == null) {
            canvas.drawText("Metric chart unavailable: no sampled metric values", bounds.left + 12f, bounds.centerY(), labelPaint)
            return
        }
        val values = series.points.map(ChartPoint::value)
        val minimum = values.minOrNull() ?: return
        val maximum = values.maxOrNull() ?: return
        val range = (maximum - minimum).takeIf { it > 0.0 }
        val points = downsample(series.points, bounds.width().toInt().coerceAtLeast(2))
        val firstTimestamp = series.points.first().timestamp
        val lastTimestamp = series.points.last().timestamp
        val timestampRange = (lastTimestamp - firstTimestamp).takeIf { it > 0L }
        val plot = RectF(bounds.left + 54f, bounds.top + 36f, bounds.right - 18f, bounds.bottom - 32f)

        canvas.drawText("${series.label} (${series.unit}) - ${series.points.size} actual readings", bounds.left + 12f, bounds.top + 25f, labelPaint)
        canvas.drawText(formatNumber(maximum), bounds.left + 8f, plot.top + 5f, smallPaint)
        canvas.drawText(formatNumber(minimum), bounds.left + 8f, plot.bottom, smallPaint)
        canvas.drawText(formatTimestamp(firstTimestamp), plot.left, bounds.bottom - 8f, smallPaint)
        val endLabel = formatTimestamp(lastTimestamp)
        canvas.drawText(endLabel, plot.right - smallPaint.measureText(endLabel), bounds.bottom - 8f, smallPaint)
        canvas.drawLine(plot.left, plot.top, plot.left, plot.bottom, gridPaint)
        canvas.drawLine(plot.left, plot.bottom, plot.right, plot.bottom, gridPaint)

        val path = Path()
        points.forEachIndexed { index, point ->
            val xFraction = timestampRange?.let {
                (point.timestamp - firstTimestamp).toDouble() / it.toDouble()
            } ?: index.toDouble() / points.lastIndex.coerceAtLeast(1).toDouble()
            val yFraction = range?.let { (point.value - minimum) / it } ?: 0.5
            val x = plot.left + plot.width() * xFraction.toFloat()
            val y = plot.bottom - plot.height() * yFraction.toFloat()
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, linePaint)
    }

    private fun drawPdfTablePage(
        canvas: Canvas,
        rows: SessionWithGameAndSamples,
        samples: List<PerformanceSample>,
        columns: List<PdfColumn>,
        groupIndex: Int,
        firstRowIndex: Int,
        totalRows: Int,
    ) {
        canvas.drawColor(Color.WHITE)
        val title = scaledPaint(headingPaint, 13f)
        val header = scaledPaint(bodyPaint, 6.5f).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD }
        val cell = scaledPaint(bodyPaint, 6f)
        val note = scaledPaint(mutedPaint, 6f)
        canvas.drawText(
            "Session ${rows.session.id} sample data - field group ${groupIndex + 1}",
            PDF_TABLE_MARGIN,
            27f,
            title,
        )
        canvas.drawText(
            "Rows ${firstRowIndex + 1}-${firstRowIndex + samples.size} of $totalRows | N/A = unavailable/not recorded",
            PDF_TABLE_MARGIN,
            42f,
            note,
        )
        val tableTop = 55f
        val tableWidth = PDF_LANDSCAPE_WIDTH - PDF_TABLE_MARGIN * 2
        val columnWidth = tableWidth / columns.size
        val rowHeight = 15f
        val tableBottom = tableTop + rowHeight * (samples.size + 1)
        canvas.drawRect(PDF_TABLE_MARGIN, tableTop, PDF_TABLE_MARGIN + tableWidth, tableBottom, gridPaint)

        columns.forEachIndexed { index, column ->
            val x = PDF_TABLE_MARGIN + index * columnWidth
            if (index > 0) canvas.drawLine(x, tableTop, x, tableBottom, gridPaint)
            drawClippedText(canvas, column.heading, x + 3f, tableTop + 10f, columnWidth - 6f, header)
        }
        canvas.drawLine(PDF_TABLE_MARGIN, tableTop + rowHeight, PDF_TABLE_MARGIN + tableWidth, tableTop + rowHeight, gridPaint)
        samples.forEachIndexed { rowIndex, sample ->
            val top = tableTop + rowHeight * (rowIndex + 1)
            columns.forEachIndexed { columnIndex, column ->
                val value = column.value(sample)?.let(::formatCell) ?: "N/A"
                drawClippedText(
                    canvas,
                    value,
                    PDF_TABLE_MARGIN + columnIndex * columnWidth + 3f,
                    top + 10f,
                    columnWidth - 6f,
                    cell,
                )
            }
            canvas.drawLine(PDF_TABLE_MARGIN, top + rowHeight, PDF_TABLE_MARGIN + tableWidth, top + rowHeight, gridPaint)
        }
    }

    private fun drawClippedText(
        canvas: Canvas,
        text: String,
        x: Float,
        baseline: Float,
        maxWidth: Float,
        paint: Paint,
    ) {
        var value = text
        if (paint.measureText(value) > maxWidth) {
            while (value.length > 1 && paint.measureText("$value...") > maxWidth) value = value.dropLast(1)
            value += "..."
        }
        canvas.drawText(value, x, baseline, paint)
    }

    private fun chartSeries(samples: List<SampleData>): ChartSeries? {
        val candidates = listOf<(SampleData) -> Double?>(
            { it.fps },
            { it.frameTime },
            { it.cpuUsage },
            { it.gpuUsage },
            { it.powerWatts },
            { it.cpuTemp },
            { it.gpuTemp },
            { it.batteryTemp },
        )
        val labels = listOf(
            "FPS" to "fps",
            "Frame time" to "ms",
            "CPU usage" to "%",
            "GPU usage" to "%",
            "Power" to "W",
            "CPU temperature" to "C",
            "GPU temperature" to "C",
            "Battery temperature" to "C",
        )
        candidates.forEachIndexed { index, accessor ->
            val points = samples.mapNotNull { sample ->
                accessor(sample)?.takeIf(Double::isFinite)?.let { ChartPoint(sample.timestamp, it) }
            }
            if (points.isNotEmpty()) return ChartSeries(labels[index].first, labels[index].second, points)
        }
        return null
    }

    private fun downsample(points: List<ChartPoint>, maximumPoints: Int): List<ChartPoint> {
        if (points.size <= maximumPoints) return points
        val step = ceil(points.size.toDouble() / maximumPoints).toInt()
        val sampled = points.filterIndexed { index, _ -> index % step == 0 }.toMutableList()
        if (sampled.lastOrNull() != points.last()) sampled += points.last()
        return sampled
    }

    private fun sortedSamples(rows: SessionWithGameAndSamples): List<PerformanceSample> =
        rows.samples.sortedWith(compareBy(PerformanceSample::timestamp, PerformanceSample::id))

    private fun scaledPaint(source: Paint, textSize: Float): Paint = Paint(source).apply { this.textSize = textSize }

    private fun formatMetric(value: Double?, unit: String): String =
        value?.takeIf(Double::isFinite)?.let { "${formatNumber(it)} $unit" } ?: "Unavailable"

    private fun formatNumber(value: Double): String = String.format(Locale.US, "%.3f", value)

    private fun formatCell(value: Any): String = when (value) {
        is Double -> if (value.isFinite()) formatNumber(value) else "N/A"
        else -> value.toString()
    }

    private fun formatDuration(value: Long?): String {
        if (value == null || value < 0L) return "Unavailable"
        val totalSeconds = value / 1_000L
        return "%02d:%02d:%02d".format(
            Locale.US,
            totalSeconds / 3_600L,
            totalSeconds % 3_600L / 60L,
            totalSeconds % 60L,
        )
    }

    private fun formatTimestamp(value: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM, Locale.getDefault()).format(Date(value))

    private fun formatTimestampOrUnavailable(value: Long?): String = value?.let(::formatTimestamp) ?: "Unavailable"

    private fun actualDuration(rows: SessionWithGameAndSamples, samples: List<SampleData>): Long? =
        rows.session.endedAt?.let { endedAt -> (endedAt - rows.session.startedAt).takeIf { it >= 0L } }
            ?: samples.takeIf { it.isNotEmpty() }?.let { values ->
                (values.last().timestamp - values.first().timestamp).takeIf { it >= 0L }
            }

    private data class ChartPoint(val timestamp: Long, val value: Double)
    private data class ChartSeries(val label: String, val unit: String, val points: List<ChartPoint>)
    private data class PdfColumn(val heading: String, val value: (PerformanceSample) -> Any?)

    private companion object {
        const val BITMAP_WIDTH = 1_600
        const val BITMAP_HEIGHT = 1_200
        const val BITMAP_MARGIN = 70f
        const val PDF_PORTRAIT_WIDTH = 595
        const val PDF_PORTRAIT_HEIGHT = 842
        const val PDF_LANDSCAPE_WIDTH = 842
        const val PDF_LANDSCAPE_HEIGHT = 595
        const val PDF_MARGIN = 38f
        const val PDF_TABLE_MARGIN = 24f
        const val PDF_ROWS_PER_PAGE = 32
        const val PDF_METRICS_PER_GROUP = 5
        val INK = 0xff17202a.toInt()
        val MUTED = 0xff66727e.toInt()
        val ACCENT = 0xff087e8b.toInt()
        val GRID = 0xffcbd3da.toInt()

        val PDF_BASE_COLUMNS = listOf(
            PdfColumn("sample_id") { it.id },
            PdfColumn("timestamp") { it.timestamp },
        )
        val PDF_METRIC_COLUMNS = listOf(
            PdfColumn("fps") { it.fps },
            PdfColumn("frame_ms") { it.frameTime },
            PdfColumn("cpu_pct") { it.cpuUsage },
            PdfColumn("cpu_mhz") { it.cpuFrequency },
            PdfColumn("cpu_c") { it.cpuTemp },
            PdfColumn("gpu_pct") { it.gpuUsage },
            PdfColumn("gpu_mhz") { it.gpuFrequency },
            PdfColumn("gpu_c") { it.gpuTemp },
            PdfColumn("ram_used") { it.ramUsed },
            PdfColumn("ram_avail") { it.ramAvailable },
            PdfColumn("batt_pct") { it.batteryLevel },
            PdfColumn("batt_c") { it.batteryTemp },
            PdfColumn("voltage_v") { it.voltage },
            PdfColumn("current_a") { it.current },
            PdfColumn("power_w") { it.powerWatts },
            PdfColumn("down_mbps") { it.downloadRate },
            PdfColumn("up_mbps") { it.uploadRate },
            PdfColumn("ping_ms") { it.ping },
            PdfColumn("thermal") { it.thermalState },
        )
    }
}
