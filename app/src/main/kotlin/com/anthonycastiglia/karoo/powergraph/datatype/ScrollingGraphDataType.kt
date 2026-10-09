package com.anthonycastiglia.karoo.powergraph.datatype

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.core.graphics.createBitmap
import com.anthonycastiglia.karoo.powergraph.R.id.aggregation_labels
import com.anthonycastiglia.karoo.powergraph.R.id.aggregation_stats
import com.anthonycastiglia.karoo.powergraph.R.id.graph_image
import com.anthonycastiglia.karoo.powergraph.R.id.value
import com.anthonycastiglia.karoo.powergraph.R.id.value_end
import com.anthonycastiglia.karoo.powergraph.R.id.value_start
import com.anthonycastiglia.karoo.powergraph.R.layout.view_scrolling_graph
import com.anthonycastiglia.karoo.powergraph.R.layout.view_scrolling_graph_compact
import com.anthonycastiglia.karoo.powergraph.data.Aggregation
import com.anthonycastiglia.karoo.powergraph.data.BufferedDataStream
import com.anthonycastiglia.karoo.powergraph.data.SAMPLE_INTERVAL
<<<<<<< Updated upstream
import com.anthonycastiglia.karoo.powergraph.datatype.ScrollingGraphDataType.Companion.COMPACT_ROW_SPAN_THRESHOLD
import com.anthonycastiglia.karoo.powergraph.datatype.ScrollingGraphDataType.Companion.AGGREGATION_TEXT_SIZE_FRACTION
import com.anthonycastiglia.karoo.powergraph.datatype.ScrollingGraphDataType.Companion.VALUE_FULL_SIZE_FRACTION
=======
>>>>>>> Stashed changes
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.UserProfile
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The values carried by a [StreamState] flow, skipping the states that carry none -- a sensor
 * still searching, or dropped out. Skipping rather than substituting leaves a gap in the
 * timestamps of whatever consumes them.
 */
private fun Flow<StreamState>.singleValues(): Flow<Double> =
    mapNotNull { (it as? StreamState.Streaming)?.dataPoint?.singleValue }

/**
 * Generic scrolling bar graph over any single-value [StreamState] source: one bar per sample
 * over a rolling window, with a current value and the selected [aggregations] text row above,
 * colored by [zonesSource] when [zoneColorsEnabled] allows it. [aggregationSources] holds a
 * ride-wide stat stream for each [Aggregation] the metric has, and only those can be shown;
 * [zonesSource] is optional for metrics without zones. [previewSource] is the synthetic data
 * shown in the profile editor.
 * [currentValueSmoothingSeconds] averages the displayed current value over a trailing window
 * without affecting the bars plotted from [dataSource]; zero for a metric with no smoothing.
 */
class ScrollingGraphDataType(
    extension: String,
    typeId: String,
    private val dataSource: Flow<StreamState>,
    private val previewSource: Flow<Double>,
    private val scale: GraphScale,
    private val unitLabel: String = "",
    private val aggregationSources: Map<Aggregation, Flow<StreamState>> = emptyMap(),
    private val aggregations: StateFlow<Set<Aggregation>> = MutableStateFlow(emptySet()),
    private val zonesSource: Flow<List<UserProfile.Zone>>? = null,
    private val zoneColorsEnabled: StateFlow<Boolean> = MutableStateFlow(true),
    private val currentValueSmoothingSeconds: StateFlow<Int> = MutableStateFlow(0),
) : DataTypeImpl(extension, typeId) {

    private val tag = "ScrollingGraphDataType[$typeId]"

    /**
     * Logs any exception that ends one of this field's coroutines, in place of the default of
     * rethrowing it on the worker thread -- which crashes the whole extension process, taking
     * every other field down with it. Added to every scope this class launches into.
     */
    private val failureLogger = CoroutineExceptionHandler { _, e ->
        Log.e(tag, "Coroutine failed", e)
    }

    private val extensionScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + failureLogger)

    /** Shared across view attachments so graph history survives page switches. */
    private val buffer = BufferedDataStream(dataSource.singleValues(), SAMPLE_INTERVAL, WINDOW)

    private val zones = MutableStateFlow<List<UserProfile.Zone>>(emptyList())

    init {
        extensionScope.launch { buffer.start() }
        zonesSource?.let { source ->
            extensionScope.launch { source.collect { zones.value = it } }
        }
    }

    override fun startStream(emitter: Emitter<StreamState>) {
        Log.d(tag, "start stream")
        val job = CoroutineScope(Dispatchers.IO + failureLogger).launch {
            dataSource.collect { state ->
                when (state) {
                    is StreamState.Streaming -> emitter.onNext(
                        state.copy(
                            dataPoint = DataPoint(
                                dataTypeId,
                                values = mapOf(DataType.Field.SINGLE to state.dataPoint.singleValue!!),
                            ),
                        ),
                    )
                    else -> emitter.onNext(state)
                }
            }
        }
        emitter.setCancellable {
            Log.d(tag, "stop stream")
            job.cancel()
        }
    }

    /**
     * Redraws the graph every [SAMPLE_INTERVAL] until the view is detached. Each frame is
     * rendered under its own catch, so a frame that fails is logged and skipped rather than
     * ending the loop and freezing the graph.
     */
    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        Log.d(tag, "start view with $emitter and config $config")
        emitter.onNext(UpdateGraphicConfig(showHeader = true))

        val viewScope = newViewScope()
        val compactLayout = compactLayoutFor(context, config)
        val graphSize = graphBitmapSize(config, compactLayout)
        val plot = plotFor(graphSize, compactLayout, context.resources.displayMetrics.density)
<<<<<<< Updated upstream
        val activeBuffer = bufferFor(config, viewScope)
        val rideStats = rideStatsFor(config, viewScope)
=======
        val fullSizing by lazy {
            fullTextSizing(context, config.textSize, config.viewSize.first, config.viewSize.second)
        }
        val activeBuffer = bufferFor(config, viewScope)
<<<<<<< Updated upstream
        val maxValue = maxValueFor(config, viewScope)
=======
        val rideStats = rideStatsFor(config, viewScope)
        var cachedSizing: Pair<List<Aggregation>, FullTextSizing>? = null

        fun sizingFor(shown: List<Aggregation>): FullTextSizing =
            cachedSizing?.takeIf { it.first == shown }?.second
                ?: fullTextSizing(
                    context,
                    config.textSize,
                    config.viewSize.first,
                    config.viewSize.second,
                    shown,
                ).also { cachedSizing = shown to it }
>>>>>>> Stashed changes
>>>>>>> Stashed changes

        viewScope.launch {
            while (true) {
                try {
                    val points = activeBuffer.snapshot()
                    val currentValue = smoothedCurrentValue(points)
                    val graph = drawGraph(points, graphSize, plot)
                    emitter.updateView(
                        if (compactLayout == null) {
<<<<<<< Updated upstream
                            val shown = shownAggregations()
                            val stats = if (config.preview) previewStats(points) else rideStats.value
                            val sizing = fullTextSizing(
                                context,
                                config.textSize,
                                config.viewSize.first,
                                config.viewSize.second,
                                shown,
                            )
=======
<<<<<<< Updated upstream
                            fullLayoutViews(context, graph, currentValue, maxValue.value, fullSizing)
=======
                            val shown = shownAggregations()
                            val stats = if (config.preview) previewStats(points) else rideStats.value
                            val sizing = sizingFor(shown)
>>>>>>> Stashed changes
                            val aggregationValues = shown.mapNotNull { aggregation ->
                                stats[aggregation]?.let { aggregation to it }
                            }
                            fullLayoutViews(context, graph, currentValue, aggregationValues, sizing)
<<<<<<< Updated upstream
=======
>>>>>>> Stashed changes
>>>>>>> Stashed changes
                        } else {
                            compactLayoutViews(context, graph, currentValue, compactLayout)
                        },
                    )
                } catch (e: Exception) {
                    Log.e(tag, "Failed to render frame", e)
                }
                delay(SAMPLE_INTERVAL)
            }
        }
        emitter.setCancellable {
            Log.d(tag, "stop view")
            viewScope.cancel()
        }
    }

    private fun newViewScope() = CoroutineScope(Dispatchers.IO + SupervisorJob() + failureLogger)

    /**
<<<<<<< Updated upstream
=======
<<<<<<< Updated upstream
=======
>>>>>>> Stashed changes
     * The current value to display: the latest [points] reading, or its average over
     * [currentValueSmoothingSeconds] when that's positive. Smoothing only affects this displayed value,
     * never the bars [drawGraph] plots from [points].
     */
    private fun smoothedCurrentValue(points: List<Pair<Long, Double>>): Double {
        val latest = points.lastOrNull() ?: return 0.0
<<<<<<< Updated upstream
        val window = currentValueSmoothingSeconds.value.seconds
        if (window <= Duration.ZERO) return latest.second
        val cutoff = latest.first - window.inWholeMilliseconds
=======
        val seconds = currentValueSmoothingSeconds.value
        if (seconds <= 0) return latest.second
        val cutoff = latest.first - seconds * MILLIS_PER_SECOND
>>>>>>> Stashed changes
        return points.filter { it.first >= cutoff }.map { it.second }.average()
    }

    /**
<<<<<<< Updated upstream
=======
>>>>>>> Stashed changes
>>>>>>> Stashed changes
     * The compact side-by-side layout, for a tile below [COMPACT_ROW_SPAN_THRESHOLD] with no
     * room for a header text row above the graph; null for the full layout otherwise.
     *
     * The value goes on the side matching the user's configured alignment.
     */
    private fun compactLayoutFor(context: Context, config: ViewConfig): CompactLayout? {
        if (config.gridSize.second > COMPACT_ROW_SPAN_THRESHOLD) return null
        val density = context.resources.displayMetrics.density
        val edgePaddingPx = (VALUE_EDGE_PADDING_DP * density).roundToInt()
        val graphGapPx = (VALUE_GRAPH_GAP_DP * density).roundToInt()
        val maxSlotWidthPx = (config.viewSize.first - WIDTH_INSET_PX) * COMPACT_VALUE_MAX_WIDTH_FRACTION
        val template = "$COMPACT_VALUE_DIGIT_TEMPLATE$unitLabel"
        val valueSizeSp = textSizeToFitSp(
            context,
            template,
            config.textSize.toFloat(),
            maxSlotWidthPx - edgePaddingPx - graphGapPx,
        )
        return CompactLayout(
            textOnRight = config.alignment != ViewConfig.Alignment.LEFT,
            valueSizeSp = valueSizeSp,
            valueSlotWidthPx = ceil(textWidthPx(context, template, valueSizeSp)).toInt() + edgePaddingPx + graphGapPx,
            edgePaddingPx = edgePaddingPx,
            graphGapPx = graphGapPx,
            viewHeight = config.viewSize.second,
        )
    }

    /**
     * Width and height in pixels to draw the graph's bitmap at.
     *
     * [ViewConfig.viewSize] is the full tile and only nominal -- Karoo lays out fields of the
     * same gridSize a pixel or two apart. So the bitmap is drawn at roughly the space left after
     * Karoo's header and borders (see [HEADER_HEIGHT_PX] and [WIDTH_INSET_PX]), and the
     * ImageViews stretch it to the exact size with scaleType="fitXY". In [compactLayout] it is
     * drawn at the width the value's slot leaves, so the bars don't come out coarse.
     */
    private fun graphBitmapSize(config: ViewConfig, compactLayout: CompactLayout?): Pair<Int, Int> {
        val (viewWidth, viewHeight) = config.viewSize
        val tileGraphWidth = viewWidth - WIDTH_INSET_PX
        val width = tileGraphWidth - (compactLayout?.valueSlotWidthPx ?: 0)
        val height = (viewHeight - HEADER_HEIGHT_PX).roundToInt()
        return width.coerceAtLeast(1) to height.coerceAtLeast(1)
    }

    /**
     * The buffer to draw from: the shared real-data one, or for preview a synthetic one that
     * ends with [viewScope].
     */
    private fun bufferFor(config: ViewConfig, viewScope: CoroutineScope): BufferedDataStream =
        if (config.preview) {
            BufferedDataStream(previewSource, SAMPLE_INTERVAL, WINDOW).also { preview ->
                viewScope.launch { preview.start() }
            }
        } else {
            buffer
        }

    /**
     * The selected [aggregations] this metric has a source for, in [Aggregation] declaration
     * order, so the stacked values always appear in the same order whatever the order they were
     * selected in.
     */
    private fun shownAggregations(): List<Aggregation> =
        Aggregation.entries.filter { it in aggregations.value && it in aggregationSources }

    /**
     * The latest ride-wide value of each [aggregationSources] stat seen so far, collected for as
     * long as [viewScope] lives. Empty in preview, which has no ride; see [previewStats].
     */
    private fun rideStatsFor(config: ViewConfig, viewScope: CoroutineScope): StateFlow<Map<Aggregation, Double>> {
        val stats = MutableStateFlow<Map<Aggregation, Double>>(emptyMap())
        if (config.preview) return stats
        aggregationSources.forEach { (aggregation, source) ->
            viewScope.launch {
                source.singleValues().collect { value -> stats.update { it + (aggregation to value) } }
            }
        }
        return stats
    }

    /**
     * Stand-ins for the ride-wide stats, worked out from the synthetic [points] in the window:
     * their max, their mean, and the fourth-power mean that normalized power is built on.
     */
    private fun previewStats(points: List<Pair<Long, Double>>): Map<Aggregation, Double> {
        val values = points.map { it.second }
        if (values.isEmpty()) return emptyMap()
        return mapOf(
            Aggregation.MAX to values.max(),
            Aggregation.AVERAGE to values.average(),
            Aggregation.NORMALIZED to values.map { it.pow(4) }.average().pow(0.25),
        )
    }

    /**
     * Where the bars go within a graph bitmap of [size]: inset [GRAPH_PADDING_DP] from the
     * bottom and from each side that meets the tile's border.
     *
     * Bottom corners meeting the border's rounded corner are rounded too, at the border's radius
     * less the padding, so they follow its curve; left square they'd run right up to it.
     */
    private fun plotFor(size: Pair<Int, Int>, compactLayout: CompactLayout?, density: Float): Plot {
        val padding = GRAPH_PADDING_DP * density
        val padLeft = compactLayout?.textOnRight ?: true
        val padRight = compactLayout?.textOnRight?.not() ?: true
        val area = RectF(
            if (padLeft) padding else 0f,
            0f,
            size.first - if (padRight) padding else 0f,
            size.second - padding,
        )
        val radius = ((CORNER_RADIUS_DP - GRAPH_PADDING_DP) * density).coerceAtLeast(0f)
        val bottomRight = if (padRight) radius else 0f
        val bottomLeft = if (padLeft) radius else 0f
        val radii = floatArrayOf(0f, 0f, 0f, 0f, bottomRight, bottomRight, bottomLeft, bottomLeft)
        return Plot(area, Path().apply { addRoundRect(area, radii, Path.Direction.CW) }, MIN_BAR_HEIGHT_DP * density)
    }

    private data class Plot(val area: RectF, val clip: Path, val minBarHeightPx: Float)

    private fun drawGraph(
        points: List<Pair<Long, Double>>,
        size: Pair<Int, Int>,
        plot: Plot,
    ): Bitmap = createBitmap(width = size.first, height = size.second).also { bitmap ->
        Canvas(bitmap).apply {
            clipPath(plot.clip)
            val palette = ZonePalette(if (zoneColorsEnabled.value) zones.value else emptyList())
            drawBars(points, plot.area, WINDOW, SAMPLE_INTERVAL, scale, palette, plot.minBarHeightPx)
        }
    }

    /**
<<<<<<< Updated upstream
     * The full layout: the current value on the right, as in the compact layout, and the
     * [aggregationValues] stacked one per line on the left, in a row above the graph, as a
     * column of labels beside a right-aligned column of numbers so the numbers line up. The
     * aggregation block is hidden while there are none to show, but keeps its slot so the value
     * never moves.
=======
<<<<<<< Updated upstream
     * The full layout: the current value on the right, as in the compact layout, and [maxValue]
     * on the left where there is one, in a row above the graph. The max label is hidden while
     * there's no max, but keeps its slot so the value never moves.
=======
     * The full layout: the current value on the right, as in the compact layout, and the
     * [aggregationValues] stacked one per line on the left, in a row above the graph, as a
     * column of labels beside a right-aligned column of numbers so the numbers line up. The
     * aggregation block is hidden while its stats are pending, but keeps its slot so the value
     * never moves.
>>>>>>> Stashed changes
>>>>>>> Stashed changes
     */
    private fun fullLayoutViews(
        context: Context,
        graph: Bitmap,
        currentValue: Double,
<<<<<<< Updated upstream
        aggregationValues: List<Pair<Aggregation, Double>>,
=======
        maxValue: Double?,
>>>>>>> Stashed changes
        sizing: FullTextSizing,
    ): RemoteViews = RemoteViews(context.packageName, view_scrolling_graph).apply {
        setImageViewBitmap(graph_image, graph)
        setTextViewText(value, formatValue(currentValue))
        setTextViewTextSize(value, TypedValue.COMPLEX_UNIT_SP, sizing.valueSizeSp)
<<<<<<< Updated upstream
=======
<<<<<<< Updated upstream
        setInt(max_value, "setWidth", sizing.maxSlotWidthPx)
        setViewVisibility(max_value, if (maxValue == null) View.INVISIBLE else View.VISIBLE)
        maxValue?.let {
            setTextViewText(max_value, "MAX ${formatValue(it)}")
            setTextViewTextSize(max_value, TypedValue.COMPLEX_UNIT_SP, sizing.valueSizeSp * MAX_TEXT_SIZE_FRACTION)
=======
>>>>>>> Stashed changes
        val visibility = if (aggregationValues.isEmpty()) View.INVISIBLE else View.VISIBLE
        setInt(aggregation_labels, "setWidth", sizing.aggregationLabelSlotWidthPx)
        setInt(aggregation_stats, "setWidth", sizing.aggregationStatSlotWidthPx)
        setViewPadding(
            aggregation_labels,
            sizing.aggregationStartPaddingPx,
            0,
            0,
            sizing.aggregationBottomPaddingPx,
        )
        setViewPadding(aggregation_stats, sizing.aggregationLabelGapPx, 0, 0, sizing.aggregationBottomPaddingPx)
        setViewVisibility(aggregation_labels, visibility)
        setViewVisibility(aggregation_stats, visibility)
        if (aggregationValues.isNotEmpty()) {
            setTextViewText(
                aggregation_labels,
<<<<<<< Updated upstream
                aggregationValues.joinToString("\n") { AGGREGATION_LABELS.getValue(it.first) },
=======
                aggregationValues.joinToString("\n") { AGGREGATION_SHORT_LABELS.getValue(it.first) },
>>>>>>> Stashed changes
            )
            setTextViewText(aggregation_stats, aggregationValues.joinToString("\n") { formatValue(it.second) })
            setTextViewTextSize(aggregation_labels, TypedValue.COMPLEX_UNIT_SP, sizing.aggregationSizeSp)
            setTextViewTextSize(aggregation_stats, TypedValue.COMPLEX_UNIT_SP, sizing.aggregationSizeSp)
<<<<<<< Updated upstream
=======
>>>>>>> Stashed changes
>>>>>>> Stashed changes
        }
    }

    /**
     * The compact layout: the value beside the graph, in [layout]'s fixed-width slot on its side.
     *
     * The slot is sized for [COMPACT_VALUE_DIGIT_TEMPLATE], so a value wider than that -- a
     * four-digit power reading -- is shrunk to fit for as long as it lasts, and its bottom padding
     * recomputed to keep it on the same baseline. Left at full size it wouldn't shrink on its
     * own: the TextView is limited to one line, so it would break the value onto a second line
     * it then hides, showing 1160 as "116".
     */
    private fun compactLayoutViews(
        context: Context,
        graph: Bitmap,
        currentValue: Double,
        layout: CompactLayout,
    ): RemoteViews {
        val valueId = if (layout.textOnRight) value_end else value_start
        val unusedValueId = if (layout.textOnRight) value_start else value_end
        val valueText = formatValue(currentValue)
        val valueSizeSp = textSizeToFitSp(
            context,
            valueText,
            layout.valueSizeSp,
            (layout.valueSlotWidthPx - layout.edgePaddingPx - layout.graphGapPx).toFloat(),
        )
        return RemoteViews(context.packageName, view_scrolling_graph_compact).apply {
            setImageViewBitmap(graph_image, graph)
            setViewVisibility(valueId, View.VISIBLE)
            setViewVisibility(unusedValueId, View.GONE)
            setInt(valueId, "setWidth", layout.valueSlotWidthPx)
            setTextViewText(valueId, valueText)
            setTextViewTextSize(valueId, TypedValue.COMPLEX_UNIT_SP, valueSizeSp)
            setViewPadding(
                valueId,
                if (layout.textOnRight) layout.graphGapPx else layout.edgePaddingPx,
                0,
                if (layout.textOnRight) layout.edgePaddingPx else layout.graphGapPx,
                compactValueBottomPadding(context, valueSizeSp, layout.viewHeight),
            )
        }
    }

    private fun formatValue(value: Double) = "${value.roundToInt()}$unitLabel"

    private fun textWidthPx(context: Context, text: String, textSizeSp: Float): Float =
        valuePaint(context, textSizeSp).measureText(text)

    /**
     * [textSizeSp], shrunk just enough for [text] to fit within [availableWidthPx] if it
     * wouldn't already, less a pixel of slack: a TextView rounds a line's measured width up to
     * a whole pixel, so text sized to fill the width exactly can still wrap.
     */
    private fun textSizeToFitSp(context: Context, text: String, textSizeSp: Float, availableWidthPx: Float): Float {
        val fitWidthPx = availableWidthPx - 1
        val textWidthPx = textWidthPx(context, text, textSizeSp)
        return if (textWidthPx > fitWidthPx) textSizeSp * fitWidthPx / textWidthPx else textSizeSp
    }

    private fun valuePaint(context: Context, textSizeSp: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = VALUE_TYPEFACE
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, textSizeSp, context.resources.displayMetrics)
    }

    /**
     * Bottom padding in pixels for the compact value's TextView at [textSizeSp], which is what
     * positions the digits vertically. Usually negative: the view is bottom-aligned, which puts
     * the font's *descender* against the bottom, so the padding pulls the text down by the
     * descent for the digits to sit [VALUE_BOTTOM_GAP_DP] above the bottom.
     *
     * Measured from the bottom because that's the edge Karoo's own fields hold their value
     * against, and the one place the view really ends -- [ViewConfig.viewSize] is only nominal.
     * The gap shrinks on the shortest tiles, where keeping it would cut the digits' tops off;
     * that's the one use of the nominal height, as the best estimate available.
     */
    private fun compactValueBottomPadding(context: Context, textSizeSp: Float, viewHeight: Int): Int {
        val paint = valuePaint(context, textSizeSp)
        val digitBounds = Rect()
        paint.getTextBounds(COMPACT_VALUE_DIGIT_TEMPLATE, 0, 1, digitBounds)

        val contentHeight = viewHeight - HEADER_HEIGHT_PX
        val bottomGap = VALUE_BOTTOM_GAP_DP * context.resources.displayMetrics.density
        val baselineAboveBottom = minOf(bottomGap, contentHeight - digitBounds.height())
        return (baselineAboveBottom - paint.descent()).roundToInt()
    }

    /**
     * Value on the [textOnRight] side of the tile in a slot [valueSlotWidthPx] wide, with the
     * graph filling the rest of the row. The slot fits [COMPACT_VALUE_DIGIT_TEMPLATE] at
     * [valueSizeSp] plus [edgePaddingPx] and [graphGapPx]; every metric uses the same template, so
     * stacked compact graphs end at the same x.
     *
     * [valueSizeSp] is Karoo's [ViewConfig.textSize], shrunk if the template wouldn't fit within
     * [COMPACT_VALUE_MAX_WIDTH_FRACTION] of the row. The value is a TextView in
     * view_scrolling_graph_compact.xml rather than drawn into the bitmap, so Karoo lays it out
     * at an exact size.
     */
    private data class CompactLayout(
        val textOnRight: Boolean,
        val valueSizeSp: Float,
        val valueSlotWidthPx: Int,
        val edgePaddingPx: Int,
        val graphGapPx: Int,
        val viewHeight: Int,
    )

    /**
<<<<<<< Updated upstream
     * Text size in sp for the full layout's value row, the size for each of the stacked
     * [aggregations] beside it, and the width of their slot.
     *
     * The aggregations are at [AGGREGATION_TEXT_SIZE_FRACTION] of the value's size when there's
     * one. Stacked, they shrink until the digits of the top line to the bottom line cover exactly
     * the height of the value's digits, and the bottom line sits on the value's baseline.
     *
=======
     * Text size in sp for the full layout's value row, at [MAX_TEXT_SIZE_FRACTION] of it for the
     * max label beside it, and the width of the max label's slot.
     *
>>>>>>> Stashed changes
     * Taken from Karoo's [ViewConfig.textSize] scaled by [VALUE_FULL_SIZE_FRACTION]. Karoo sizes
     * that by the tile's width, so it's scaled up further, to at most [MAX_VALUE_HEIGHT_SCALE],
     * as the content below the header grows taller than [REFERENCE_CONTENT_HEIGHT_PX]. Taller
     * tiles have room for it; the nominal height varies by a pixel or two between fields of the
     * same gridSize, which this accepts as negligible at this scale.
     *
<<<<<<< Updated upstream
     * Shrunk further if the widest value and aggregation wouldn't fit side by side. The
     * aggregations get a slot just wide enough for their widest text, and the value the rest of
     * the row. The slot is kept before the stats arrive, so the value never moves.
     */
    private fun fullTextSizing(
        context: Context,
        textSize: Int,
        viewWidth: Int,
        viewHeight: Int,
        aggregations: List<Aggregation>,
    ): FullTextSizing {
=======
     * Shrunk further if the widest value and max label wouldn't fit side by side. The max
     * label gets a slot just wide enough for its widest text, and the value the rest of the
     * row. The slot is kept even for metrics without a max, or before there is one, so the
     * value never moves.
     */
    private fun fullTextSizing(context: Context, textSize: Int, viewWidth: Int, viewHeight: Int): FullTextSizing {
>>>>>>> Stashed changes
        val metrics = context.resources.displayMetrics
        val heightScale = ((viewHeight - HEADER_HEIGHT_PX) / REFERENCE_CONTENT_HEIGHT_PX)
            .coerceIn(1f, MAX_VALUE_HEIGHT_SCALE)
        val valueSp = textSize * VALUE_FULL_SIZE_FRACTION * heightScale
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = VALUE_TYPEFACE }

        fun widthAt(text: String, sp: Float): Float {
            paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, metrics)
            return paint.measureText(text)
        }

        val valueWidth = widthAt("$CURRENT_VALUE_DIGIT_TEMPLATE$unitLabel", valueSp)
<<<<<<< Updated upstream
        val reference = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = VALUE_TYPEFACE
            this.textSize = REFERENCE_TEXT_SIZE_PX
        }
        val digitHeight = Rect().also { reference.getTextBounds("9", 0, 1, it) }.height()
        val lineHeight = reference.descent() - reference.ascent()
        val stackedFraction = digitHeight / ((aggregations.size - 1) * lineHeight + digitHeight)
        val aggregationFraction = if (aggregations.size > 1) {
            minOf(AGGREGATION_TEXT_SIZE_FRACTION, stackedFraction)
        } else {
            AGGREGATION_TEXT_SIZE_FRACTION
        }
        val aggregationSp = valueSp * aggregationFraction
        val labelWidth = aggregations.maxOfOrNull { widthAt(AGGREGATION_LABELS.getValue(it), aggregationSp) } ?: 0f
        val labelGapWidth = widthAt(" ", aggregationSp)
        val statWidth = widthAt(formatValue(TEMPLATE_STAT), aggregationSp)
        val edgePaddingPx = (VALUE_EDGE_PADDING_DP * metrics.density).roundToInt()
        val availableWidth = viewWidth - 2 * edgePaddingPx - 1
        val worstCaseWidth = valueWidth + labelWidth + labelGapWidth + statWidth
        val shrink = if (worstCaseWidth > availableWidth) availableWidth / worstCaseWidth else 1f
        val labelGapPx = ceil(labelGapWidth * shrink).toInt()
        val labelSlotWidthPx = ceil(labelWidth * shrink).toInt() + edgePaddingPx
        val statSlotWidthPx = ceil(statWidth * shrink).toInt() + labelGapPx
        val descentPerPx = reference.descent() / REFERENCE_TEXT_SIZE_PX
        fun spToPx(sp: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, metrics)
        val descentGapPx = descentPerPx * (spToPx(valueSp * shrink) - spToPx(aggregationSp * shrink))
        val shown = aggregations.isNotEmpty()
        return FullTextSizing(
            valueSizeSp = valueSp * shrink,
            aggregationSizeSp = aggregationSp * shrink,
            aggregationLabelSlotWidthPx = if (shown) labelSlotWidthPx else 0,
            aggregationStatSlotWidthPx = if (shown) statSlotWidthPx else 0,
            aggregationStartPaddingPx = edgePaddingPx,
            aggregationLabelGapPx = labelGapPx,
            aggregationBottomPaddingPx = descentGapPx.roundToInt(),
        )
    }

=======
<<<<<<< Updated upstream
        val maxWidth = widthAt("MAX $CURRENT_VALUE_DIGIT_TEMPLATE$unitLabel", valueSp * MAX_TEXT_SIZE_FRACTION)
=======
        val reference = referencePaint()
        val aggregationSp = valueSp * aggregationFraction(aggregations.size, reference)
        val labelWidth = aggregations
            .maxOfOrNull { widthAt(AGGREGATION_SHORT_LABELS.getValue(it), aggregationSp) } ?: 0f
        val labelGapWidth = widthAt(" ", aggregationSp)
        val statWidth = widthAt(formatValue(TEMPLATE_STAT), aggregationSp)
>>>>>>> Stashed changes
        val edgePaddingPx = (VALUE_EDGE_PADDING_DP * metrics.density).roundToInt()
        val availableWidth = viewWidth - 2 * edgePaddingPx - 1
        val worstCaseWidth = valueWidth + maxWidth
        val shrink = if (worstCaseWidth > availableWidth) availableWidth / worstCaseWidth else 1f
<<<<<<< Updated upstream
        val maxTextWidth = ceil(maxWidth * shrink).toInt()
        return FullTextSizing(valueSizeSp = valueSp * shrink, maxSlotWidthPx = maxTextWidth + edgePaddingPx)
    }

    /** [maxSlotWidthPx] includes the max label's edge padding. */
    private data class FullTextSizing(val valueSizeSp: Float, val maxSlotWidthPx: Int)
=======
        val labelGapPx = ceil(labelGapWidth * shrink).toInt()
        val labelSlotWidthPx = ceil(labelWidth * shrink).toInt() + edgePaddingPx
        val statSlotWidthPx = ceil(statWidth * shrink).toInt() + labelGapPx
        fun spToPx(sp: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, metrics)
        val bottomPaddingPx = descentGapPx(reference, spToPx(valueSp * shrink), spToPx(aggregationSp * shrink))
        val shown = aggregations.isNotEmpty()
        return FullTextSizing(
            valueSizeSp = valueSp * shrink,
            aggregationSizeSp = aggregationSp * shrink,
            aggregationLabelSlotWidthPx = if (shown) labelSlotWidthPx else 0,
            aggregationStatSlotWidthPx = if (shown) statSlotWidthPx else 0,
            aggregationStartPaddingPx = edgePaddingPx,
            aggregationLabelGapPx = labelGapPx,
            aggregationBottomPaddingPx = bottomPaddingPx.roundToInt(),
        )
    }

    /** The font at [REFERENCE_TEXT_SIZE_PX], whose metrics scale linearly to any other size. */
    private fun referencePaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = VALUE_TYPEFACE
        this.textSize = REFERENCE_TEXT_SIZE_PX
    }

    /**
     * The aggregation text size as a fraction of the value's: [AGGREGATION_TEXT_SIZE_FRACTION]
     * for one, and for [count] stacked, whatever makes the digits from the top line to the bottom
     * line as tall as the value's digits, if that is smaller. Measured on [reference].
     */
    private fun aggregationFraction(count: Int, reference: Paint): Float {
        if (count <= 1) return AGGREGATION_TEXT_SIZE_FRACTION
        val digitHeight = Rect().also { reference.getTextBounds("9", 0, 1, it) }.height()
        val lineHeight = reference.descent() - reference.ascent()
        val stackedFraction = digitHeight / ((count - 1) * lineHeight + digitHeight)
        return minOf(AGGREGATION_TEXT_SIZE_FRACTION, stackedFraction)
    }

    /**
     * How far the larger text's descent reaches below the smaller text's, in pixels at
     * [largerPx] and [smallerPx] sizes. Measured on [reference].
     */
    private fun descentGapPx(reference: Paint, largerPx: Float, smallerPx: Float): Float =
        reference.descent() / REFERENCE_TEXT_SIZE_PX * (largerPx - smallerPx)

>>>>>>> Stashed changes
    /**
     * The aggregations are two columns: labels, then right-aligned numbers. Each slot's width
     * includes its start padding, [aggregationStartPaddingPx] for the labels and
     * [aggregationLabelGapPx] between them and the numbers, and both are zero with none shown.
     * [aggregationBottomPaddingPx] is the gap between the value's descent and the smaller
     * aggregations', which lifts the bottom line onto the value's baseline.
     */
    private data class FullTextSizing(
        val valueSizeSp: Float,
        val aggregationSizeSp: Float,
        val aggregationLabelSlotWidthPx: Int,
        val aggregationStatSlotWidthPx: Int,
        val aggregationStartPaddingPx: Int,
        val aggregationLabelGapPx: Int,
        val aggregationBottomPaddingPx: Int,
    )
<<<<<<< Updated upstream
=======
>>>>>>> Stashed changes
>>>>>>> Stashed changes

    companion object {
        private val WINDOW = 2.minutes

        private const val MILLIS_PER_SECOND = 1000L

        /**
         * The full layout's value, as a fraction of Karoo's size for a plain numeric field of
         * the same gridSize -- smaller, since this one shares its tile with a graph. Also fits
         * the shortest tiles using this layout, where anything larger would crowd out the bars.
         */
        private const val VALUE_FULL_SIZE_FRACTION = 0.36f

        /**
         * Content height (below the header) of the shortest tile using the full layout, about
         * two rows. Taller tiles scale the value up from [VALUE_FULL_SIZE_FRACTION]'s size. An
         * estimate awaiting on-device tuning.
         */
        private const val REFERENCE_CONTENT_HEIGHT_PX = 200f

        /** Cap on the height scaling; the width check usually binds first on full-width tiles. */
        private const val MAX_VALUE_HEIGHT_SCALE = 2f

<<<<<<< Updated upstream
=======
<<<<<<< Updated upstream
        private const val MAX_TEXT_SIZE_FRACTION = 0.65f
=======
>>>>>>> Stashed changes
        /** A lone aggregation's size as a fraction of the current value's. */
        private const val AGGREGATION_TEXT_SIZE_FRACTION = 0.65f

        /**
         * Size the font is measured at to get its digit height, line height and descent, which all
         * scale linearly with text size.
         */
        private const val REFERENCE_TEXT_SIZE_PX = 100f

<<<<<<< Updated upstream
        private val AGGREGATION_LABELS = mapOf(
=======
        private val AGGREGATION_SHORT_LABELS = mapOf(
>>>>>>> Stashed changes
            Aggregation.MAX to "MAX",
            Aggregation.AVERAGE to "AVG",
            Aggregation.NORMALIZED to "NP",
        )
<<<<<<< Updated upstream
=======
>>>>>>> Stashed changes
>>>>>>> Stashed changes

        /** Sizes the full layout's header row so a change in digit count never moves anything. */
        private const val CURRENT_VALUE_DIGIT_TEMPLATE = "9999"

        private const val TEMPLATE_STAT = 9999.0

        /**
         * What the compact slot is sized for: covers every heart rate and all but sprint power.
         * Four-digit values shrink to fit instead of widening the slot.
         */
        private const val COMPACT_VALUE_DIGIT_TEMPLATE = "999"

        /**
         * The face Karoo draws its own numeric fields in ("Relative12-Regular.otf"), identified
         * by matching glyph metrics against a screenshot. Named by its "relative" alias; the
         * default sans is much wider and doesn't match.
         */
        private val VALUE_TYPEFACE: Typeface = Typeface.create("relative", Typeface.NORMAL)

        /** A profile page is a 60-unit grid ([ViewConfig.gridSize]) with up to five rows: 60/5. */
        private const val SINGLE_ROW_SPAN = 12

        /**
         * Tallest tile, in [ViewConfig.gridSize] units, that gets the compact side-by-side layout
         * (see [CompactLayout]). The standard header-row-plus-graph layout needs roughly two rows
         * for both current and max value text to stay legible; below that there's no room for a
         * separate header row at all. Guessed threshold awaiting on-device tuning.
         */
        private const val COMPACT_ROW_SPAN_THRESHOLD = SINGLE_ROW_SPAN * 2

        /**
         * Height of Karoo's header (~46px) at the top of the tile, given up from
         * [ViewConfig.viewSize] when sizing the graph -- see [graphBitmapSize]. An estimate
         * awaiting on-device tuning, since ViewConfig doesn't expose it.
         */
        private const val HEADER_HEIGHT_PX = 48f

        /**
         * Margin for the tile's borders, given up from [ViewConfig.viewSize] when sizing the
         * graph -- see [graphBitmapSize]. An estimate awaiting on-device tuning.
         */
        private const val WIDTH_INSET_PX = 12

        private const val COMPACT_VALUE_MAX_WIDTH_FRACTION = 0.45f

        /**
         * Visual margins are in dp, not shares of the tile. Karoo's own value view ends 8px
         * (~4dp) short of the tile's edge, per a view hierarchy dump.
         */
        private const val VALUE_EDGE_PADDING_DP = 4f

        private const val VALUE_GRAPH_GAP_DP = 6f

        /** Flat gap Karoo leaves below its value: ~7px on both a 188px and a 125px tile. */
        private const val VALUE_BOTTOM_GAP_DP = 3.5f

        /** Matches [VALUE_BOTTOM_GAP_DP] so the bars' foot shares a baseline with the compact digits. */
        private const val GRAPH_PADDING_DP = VALUE_BOTTOM_GAP_DP

        /** Measured from a screenshot as ~15px; ViewConfig doesn't expose it. */
        private const val CORNER_RADIUS_DP = 7.5f

        /** Keeps zero readings visible as a thin line instead of an empty gap. */
        private const val MIN_BAR_HEIGHT_DP = 1f
    }
}
