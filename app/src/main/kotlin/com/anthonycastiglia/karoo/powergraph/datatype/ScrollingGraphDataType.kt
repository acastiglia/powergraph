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
import com.anthonycastiglia.karoo.powergraph.R.id.graph_image
import com.anthonycastiglia.karoo.powergraph.R.id.max_value
import com.anthonycastiglia.karoo.powergraph.R.id.value
import com.anthonycastiglia.karoo.powergraph.R.id.value_end
import com.anthonycastiglia.karoo.powergraph.R.id.value_start
import com.anthonycastiglia.karoo.powergraph.R.layout.view_scrolling_graph
import com.anthonycastiglia.karoo.powergraph.R.layout.view_scrolling_graph_compact
import com.anthonycastiglia.karoo.powergraph.data.BufferedDataStream
import com.anthonycastiglia.karoo.powergraph.data.SAMPLE_INTERVAL
import com.anthonycastiglia.karoo.powergraph.datatype.ScrollingGraphDataType.Companion.COMPACT_ROW_SPAN_THRESHOLD
import com.anthonycastiglia.karoo.powergraph.datatype.ScrollingGraphDataType.Companion.MAX_TEXT_SIZE_FRACTION
import com.anthonycastiglia.karoo.powergraph.datatype.ScrollingGraphDataType.Companion.VALUE_FULL_SIZE_FRACTION
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
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.minutes

/**
 * The values carried by a [StreamState] flow, skipping the states that carry none -- a sensor
 * still searching, or dropped out. Skipping rather than substituting leaves a gap in the
 * timestamps of whatever consumes them.
 */
private fun Flow<StreamState>.singleValues(): Flow<Double> =
    mapNotNull { (it as? StreamState.Streaming)?.dataPoint?.singleValue }

/**
 * Generic scrolling bar graph over any single-value [StreamState] source: one bar per sample
 * over a rolling window, with a current/max text row above, colored by [zonesSource] when
 * supplied. [maxValueSource] and [zonesSource] are optional for metrics without a max stat or
 * zones. [previewSource] is the synthetic data shown in the profile editor.
 */
class ScrollingGraphDataType(
    extension: String,
    typeId: String,
    private val dataSource: Flow<StreamState>,
    private val previewSource: Flow<Double>,
    private val scale: GraphScale,
    private val unitLabel: String = "",
    private val maxValueSource: Flow<StreamState>? = null,
    private val zonesSource: Flow<List<UserProfile.Zone>>? = null,
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
        val fullValueSizeSp by lazy { fullValueTextSizeSp(context, config.textSize, config.viewSize.first) }
        val activeBuffer = bufferFor(config, viewScope)
        val maxValue = maxValueFor(config, viewScope)

        viewScope.launch {
            while (true) {
                try {
                    val points = activeBuffer.snapshot()
                    val currentValue = points.lastOrNull()?.second ?: 0.0
                    if (config.preview && maxValueSource != null) {
                        maxValue.value = maxOf(maxValue.value ?: 0.0, currentValue)
                    }
                    val graph = drawGraph(points, graphSize, plot)
                    emitter.updateView(
                        if (compactLayout == null) {
                            fullLayoutViews(context, graph, currentValue, maxValue.value, fullValueSizeSp)
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
     * The max value to show beside the current one, or null while there's none. On a real ride
     * it comes from Karoo's ride-wide [maxValueSource] rather than the short buffered window;
     * preview has no ride, so the render loop approximates it from the synthetic readings.
     */
    private fun maxValueFor(config: ViewConfig, viewScope: CoroutineScope): MutableStateFlow<Double?> {
        val maxValue = MutableStateFlow<Double?>(null)
        if (maxValueSource != null && !config.preview) {
            viewScope.launch {
                maxValueSource.singleValues().collect { maxValue.value = it }
            }
        }
        return maxValue
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
            drawBars(points, plot.area, WINDOW, SAMPLE_INTERVAL, scale, ZonePalette(zones.value), plot.minBarHeightPx)
        }
    }

    /**
     * The full layout: the current value, and [maxValue] where there is one, in a row above the
     * graph. The max label only takes space while there's a max to show, giving up its half of
     * the row to the value otherwise.
     */
    private fun fullLayoutViews(
        context: Context,
        graph: Bitmap,
        currentValue: Double,
        maxValue: Double?,
        valueSizeSp: Float,
    ): RemoteViews = RemoteViews(context.packageName, view_scrolling_graph).apply {
        setImageViewBitmap(graph_image, graph)
        setTextViewText(value, formatValue(currentValue))
        setTextViewTextSize(value, TypedValue.COMPLEX_UNIT_SP, valueSizeSp)
        setViewVisibility(max_value, if (maxValue == null) View.GONE else View.VISIBLE)
        maxValue?.let {
            setTextViewText(max_value, "MAX ${formatValue(it)}")
            setTextViewTextSize(max_value, TypedValue.COMPLEX_UNIT_SP, valueSizeSp * MAX_TEXT_SIZE_FRACTION)
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
     * Text size in sp for the full layout's value row, at [MAX_TEXT_SIZE_FRACTION] of it for the
     * max label beside it.
     *
     * Taken from Karoo's [ViewConfig.textSize] scaled by [VALUE_FULL_SIZE_FRACTION], rather than
     * from the tile's height, which varies by a pixel or two between fields of the same gridSize.
     * Shrunk further if the widest values the metric could show wouldn't fit side by side.
     */
    private fun fullValueTextSizeSp(context: Context, textSize: Int, viewWidth: Int): Float {
        val metrics = context.resources.displayMetrics
        val valueSp = textSize * VALUE_FULL_SIZE_FRACTION
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = VALUE_TYPEFACE }

        fun widthAt(text: String, sp: Float): Float {
            paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, metrics)
            return paint.measureText(text)
        }

        val valueWidth = widthAt("$CURRENT_VALUE_DIGIT_TEMPLATE$unitLabel", valueSp)
        val maxWidth = if (maxValueSource != null) {
            widthAt("MAX $CURRENT_VALUE_DIGIT_TEMPLATE$unitLabel", valueSp * MAX_TEXT_SIZE_FRACTION)
        } else {
            0f
        }
        val worstCaseWidth = valueWidth + maxWidth
        return if (worstCaseWidth > viewWidth) valueSp * viewWidth / worstCaseWidth else valueSp
    }

    companion object {
        private val WINDOW = 2.minutes

        /**
         * The full layout's value, as a fraction of Karoo's size for a plain numeric field of
         * the same gridSize -- smaller, since this one shares its tile with a graph. Also fits
         * the shortest tiles using this layout, where anything larger would crowd out the bars.
         */
        private const val VALUE_FULL_SIZE_FRACTION = 0.36f

        private const val MAX_TEXT_SIZE_FRACTION = 0.65f

        /** Sizes the full layout's header row so a change in digit count never moves anything. */
        private const val CURRENT_VALUE_DIGIT_TEMPLATE = "9999"

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

        /** Visual margins are in dp, not shares of the tile. Karoo's own fields leave about 3dp. */
        private const val VALUE_EDGE_PADDING_DP = 3f

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
