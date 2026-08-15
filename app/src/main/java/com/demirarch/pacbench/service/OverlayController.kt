package com.demirarch.pacbench.service

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextPaint
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.demirarch.pacbench.model.HudPreset
import com.demirarch.pacbench.model.HudWidget
import com.demirarch.pacbench.model.HudWidgetType
import com.demirarch.pacbench.model.MetricId
import com.demirarch.pacbench.model.MetricReading
import com.demirarch.pacbench.model.MetricSnapshot
import com.demirarch.pacbench.model.MetricStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.ArrayDeque
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class OverlayState(
    val visible: Boolean = false,
    val locked: Boolean = true,
    val failureReason: String? = null,
)

@Singleton
class OverlayController @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService(WindowManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow(OverlayState())
    val state: StateFlow<OverlayState> = mutableState.asStateFlow()

    private var overlayView: HudOverlayView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    fun show(preset: HudPreset, snapshot: MetricSnapshot, locked: Boolean = preset.lockedByDefault) {
        if (!Settings.canDrawOverlays(appContext)) {
            mutableState.value = OverlayState(
                visible = false,
                locked = true,
                failureReason = "Display-over-other-apps permission is unavailable",
            )
            return
        }
        mainHandler.post {
            removeView()
            val manager = windowManager
            if (manager == null) {
                mutableState.value = OverlayState(false, true, "WindowManager is unavailable")
                return@post
            }
            val view = HudOverlayView(appContext, preset, snapshot)
            val params = createLayoutParams(locked)
            runCatching { manager.addView(view, params) }.fold(
                onSuccess = {
                    overlayView = view
                    layoutParams = params
                    mutableState.value = OverlayState(visible = true, locked = locked)
                },
                onFailure = { error ->
                    mutableState.value = OverlayState(
                        visible = false,
                        locked = true,
                        failureReason = error.message ?: "HUD overlay could not be attached",
                    )
                },
            )
        }
    }

    fun update(snapshot: MetricSnapshot) {
        mainHandler.post { overlayView?.updateSnapshot(snapshot) }
    }

    fun setLocked(locked: Boolean) {
        mainHandler.post {
            val view = overlayView ?: return@post
            val params = layoutParams ?: return@post
            params.flags = overlayFlags(locked)
            params.alpha = if (locked) LOCKED_WINDOW_ALPHA else 1f
            runCatching { windowManager?.updateViewLayout(view, params) }.fold(
                onSuccess = { mutableState.value = mutableState.value.copy(locked = locked) },
                onFailure = { error ->
                    mutableState.value = mutableState.value.copy(
                        failureReason = error.message ?: "HUD interaction mode could not be changed",
                    )
                },
            )
        }
    }

    fun hide() {
        mainHandler.post {
            removeView()
            mutableState.value = OverlayState()
        }
    }

    private fun removeView() {
        overlayView?.let { view -> runCatching { windowManager?.removeViewImmediate(view) } }
        overlayView = null
        layoutParams = null
    }

    private fun createLayoutParams(locked: Boolean) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        overlayFlags(locked),
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        alpha = if (locked) LOCKED_WINDOW_ALPHA else 1f
    }

    private fun overlayFlags(locked: Boolean): Int =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            (if (locked) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0)

    private companion object {
        // Android permits touch pass-through for a single non-touchable overlay below 0.8 opacity.
        const val LOCKED_WINDOW_ALPHA = 0.79f
    }
}

private class HudOverlayView(
    context: Context,
    private val preset: HudPreset,
    initialSnapshot: MetricSnapshot,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val graphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(102, 230, 190)
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 122, 0)
        style = Paint.Style.FILL
    }
    private val graphPath = Path()
    private val histories = mutableMapOf<MetricId, ArrayDeque<TimedValue>>()
    private var snapshot = initialSnapshot

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        record(initialSnapshot)
    }

    fun updateSnapshot(value: MetricSnapshot) {
        snapshot = value
        record(value)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        if (width <= 0 || height <= 0) return
        val scale = min(
            width / preset.canvasWidth.coerceAtLeast(1f),
            height / preset.canvasHeight.coerceAtLeast(1f),
        )
        preset.widgets.asSequence()
            .filter(HudWidget::visible)
            .sortedBy(HudWidget::layer)
            .forEach { widget -> drawWidget(canvas, widget, scale) }
    }

    private fun drawWidget(canvas: Canvas, widget: HudWidget, scale: Float) {
        if (widget.type == HudWidgetType.SPACER) return
        val maximumMargin = (min(widget.width, widget.height) * scale / 2f - 1f).coerceAtLeast(0f)
        val margin = (widget.margin * scale).coerceAtMost(maximumMargin)
        val left = widget.x * scale + margin
        val top = widget.y * scale + margin
        val right = ((widget.x + widget.width) * scale - margin).coerceAtMost(width.toFloat())
        val bottom = ((widget.y + widget.height) * scale - margin).coerceAtMost(height.toFloat())
        if (right <= left || bottom <= top) return

        backgroundPaint.color = Color.argb(
            (widget.backgroundOpacity * 255).toInt(),
            13,
            18,
            24,
        )
        backgroundPaint.style = Paint.Style.FILL
        canvas.drawRoundRect(
            left,
            top,
            right,
            bottom,
            widget.cornerRadius * scale,
            widget.cornerRadius * scale,
            backgroundPaint,
        )

        if (widget.type == HudWidgetType.DIVIDER) {
            canvas.drawRect(left, (top + bottom) / 2f, right, (top + bottom) / 2f + density, accentPaint)
            return
        }

        val padding = widget.padding * scale
        val contentLeft = left + padding
        val contentRight = right - padding
        val contentTop = top + padding
        textPaint.color = Color.argb((widget.textOpacity * 255).toInt(), 245, 248, 250)
        textPaint.textSize = widget.fontSize * scale
        textPaint.typeface = if (widget.fontWeight >= 600) {
            android.graphics.Typeface.DEFAULT_BOLD
        } else {
            android.graphics.Typeface.DEFAULT
        }

        val readings = widget.metrics.ifEmpty { listOf(MetricId.FPS) }
        val lineHeight = textPaint.fontSpacing.coerceAtLeast(1f)
        readings.forEachIndexed { index, metric ->
            val rowMode = widget.type == HudWidgetType.MULTI_METRIC_ROW
            val x = if (rowMode) contentLeft + (contentRight - contentLeft) * index / readings.size else contentLeft
            val y = contentTop - textPaint.fontMetrics.top + if (rowMode) 0f else index * lineHeight
            if (y > bottom - padding) return@forEachIndexed
            val reading = snapshot[metric]
            val threshold = widget.warningThreshold
            val readingValue = reading?.value
            textPaint.color = if (
                threshold != null && reading?.status == MetricStatus.AVAILABLE &&
                readingValue != null && readingValue >= threshold
            ) {
                Color.rgb(255, 102, 102)
            } else {
                Color.argb((widget.textOpacity * 255).toInt(), 245, 248, 250)
            }
            val text = formatReading(metric, reading, widget)
            val availableWidth = if (rowMode) {
                ((contentRight - contentLeft) / readings.size - padding).coerceAtLeast(0f)
            } else {
                (contentRight - contentLeft).coerceAtLeast(0f)
            }
            val clipped = TextUtils.ellipsize(text, textPaint, availableWidth, TextUtils.TruncateAt.END)
            canvas.drawText(clipped, 0, clipped.length, x, y, textPaint)
        }

        if (widget.type == HudWidgetType.HORIZONTAL_BAR || widget.type == HudWidgetType.VERTICAL_BAR) {
            val metric = readings.first()
            val maximum = barMaximum(metric)
            val value = snapshot[metric]?.value?.coerceIn(0.0, maximum) ?: return
            val fraction = (value / maximum).toFloat()
            if (widget.type == HudWidgetType.HORIZONTAL_BAR) {
                val barTop = bottom - padding - 4f * density
                canvas.drawRoundRect(contentLeft, barTop, contentLeft + (contentRight - contentLeft) * fraction, bottom - padding, 2f * density, 2f * density, accentPaint)
            } else {
                val barLeft = right - padding - 5f * density
                val barTop = bottom - padding - (bottom - contentTop - padding) * fraction
                canvas.drawRoundRect(barLeft, barTop, right - padding, bottom - padding, 2f * density, 2f * density, accentPaint)
            }
        }

        if (widget.type == HudWidgetType.MINI_GRAPH || widget.type == HudWidgetType.FRAMETIME_GRAPH) {
            widget.metrics.firstOrNull()?.let { metric ->
                drawGraph(
                    canvas,
                    metric,
                    widget.graphHistorySeconds,
                    contentLeft,
                    contentRight,
                    top + (bottom - top) * 0.58f,
                    bottom - padding,
                )
            }
        }
    }

    private fun drawGraph(
        canvas: Canvas,
        metric: MetricId,
        historySeconds: Int,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
    ) {
        val cutoff = snapshot.timestampMillis - historySeconds * 1_000L
        val values = histories[metric]?.filter { it.timestampMillis >= cutoff }.orEmpty()
        if (values.size < 2 || right <= left || bottom <= top) return
        val minimum = values.minOf { it.value }
        val maximum = values.maxOf { it.value }
        val range = (maximum - minimum).takeIf { it > 0.0 }
        graphPath.reset()
        val duration = (values.last().timestampMillis - values.first().timestampMillis).coerceAtLeast(1L)
        values.forEachIndexed { index, timedValue ->
            val x = if (duration == 1L && values.size > 1) {
                left + (right - left) * index / values.lastIndex.toFloat()
            } else {
                left + (right - left) *
                    (timedValue.timestampMillis - values.first().timestampMillis).toFloat() / duration.toFloat()
            }
            val normalized = range?.let { (timedValue.value - minimum) / it } ?: 0.5
            val y = bottom - (bottom - top) * normalized.toFloat()
            if (index == 0) graphPath.moveTo(x, y) else graphPath.lineTo(x, y)
        }
        canvas.drawPath(graphPath, graphPaint)
    }

    private fun formatReading(metric: MetricId, reading: MetricReading?, widget: HudWidget): String {
        val label = metric.name.lowercase().split('_').joinToString(" ") { word ->
            word.replaceFirstChar { character -> character.titlecase(Locale.US) }
        }
        if (reading?.status != MetricStatus.AVAILABLE || reading.value == null) {
            val status = reading?.status?.name?.replace('_', ' ') ?: "NOT SAMPLED"
            return "$label: Unavailable [$status]"
        }
        val value = String.format(Locale.US, "%.${widget.decimalPrecision}f", reading.value)
        val unit = widget.metricUnit ?: metric.defaultUnit
        val valueWithUnit = if (widget.showUnit && unit.isNotBlank()) "$value $unit" else value
        return if (widget.showLabel) "$label: $valueWithUnit" else valueWithUnit
    }

    private fun record(value: MetricSnapshot) {
        val longestHistoryMillis = preset.widgets
            .filter { it.type == HudWidgetType.MINI_GRAPH || it.type == HudWidgetType.FRAMETIME_GRAPH }
            .maxOfOrNull { it.graphHistorySeconds }
            ?.times(1_000L)
            ?: 30_000L
        value.readings.forEach { reading ->
            val metricValue = reading.value
            if (reading.status == MetricStatus.AVAILABLE && metricValue != null && metricValue.isFinite()) {
                histories.getOrPut(reading.metric, ::ArrayDeque)
                    .addLast(TimedValue(value.timestampMillis, metricValue))
            }
        }
        val cutoff = value.timestampMillis - longestHistoryMillis
        histories.values.forEach { history ->
            while (history.firstOrNull()?.timestampMillis?.let { it < cutoff } == true) history.removeFirst()
            while (history.size > MAX_HISTORY_POINTS) history.removeFirst()
        }
    }

    private data class TimedValue(val timestampMillis: Long, val value: Double)

    private fun barMaximum(metric: MetricId): Double = when (metric) {
        MetricId.CPU_USAGE, MetricId.GPU_USAGE, MetricId.BATTERY_LEVEL -> 100.0
        MetricId.FPS -> 240.0
        MetricId.FRAME_TIME, MetricId.CPU_TEMPERATURE, MetricId.GPU_TEMPERATURE,
        MetricId.BATTERY_TEMPERATURE, MetricId.PING,
        -> 120.0
        MetricId.THERMAL_STATUS -> 6.0
        MetricId.VOLTAGE -> 5.0
        MetricId.POWER -> 30.0
        MetricId.CPU_FREQUENCY, MetricId.GPU_FREQUENCY -> 5_000.0
        MetricId.CURRENT -> 10.0
        MetricId.RAM_USED, MetricId.RAM_AVAILABLE -> 16.0
        MetricId.DOWNLOAD_RATE, MetricId.UPLOAD_RATE -> 1_000.0
    }

    private companion object {
        const val MAX_HISTORY_POINTS = 6_000
    }
}
