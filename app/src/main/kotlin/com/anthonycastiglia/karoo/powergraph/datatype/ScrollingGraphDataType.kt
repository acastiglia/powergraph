package com.anthonycastiglia.karoo.powergraph.datatype

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.random.Random
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
 * Generic scrolling bar graph over any single-value [StreamState] source: samples at roughly
 * 1/sec into a rolling window, drawn as one filled, borderless bar per sample with a
 * current/max text row above, colored by [zonesSource] when supplied.
 *
 * Instantiate one per metric (power, heart rate, cadence, ...) rather than subclassing --
 * [dataSource], [unitLabel] and [previewValueRange] are metric-specific; [maxValueSource] and
 * [zonesSource] are optional since not every metric has a natural "max" stat (e.g. one already
 * tracked ride-wide by Karoo) or a zone concept (e.g. cadence has neither).
 */
class ScrollingGraphDataType(
    extension: String,
    typeId: String,
    private val dataSource: Flow<StreamState>,
    private val previewValueRange: ClosedFloatingPointRange<Double>,
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

    /**
     * Scope for the coroutines that outlive any one view attachment: buffering and zone
     * collection. A [SupervisorJob] so the two fail independently -- an exception in one
     * shouldn't silently cancel the other, since they're otherwise unrelated.
     */
    private val extensionScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + failureLogger)

    /**
     * Started once, at construction, and kept running for the life of this instance --
     * independent of any specific view attachment, so graph history isn't lost across page
     * switches or between separate startView attach/detach cycles. Every non-preview startView
     * call reads from this same shared instance.
     */
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
     *
     * Everything that depends only on [config] -- layout, bitmap size, text size -- is worked
     * out once here, since it doesn't change while the view is attached.
     */
    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        Log.d(tag, "start view with $emitter and config $config")
        emitter.onNext(UpdateGraphicConfig(showHeader = true))

        val viewScope = newViewScope()
        val compactLayout = compactLayoutFor(context, config)
        val graphSize = graphBitmapSize(config, compactLayout)
        val graphSide = compactLayout?.graphSide ?: RoundedSide.BOTTOM
        val cornerRadiusPx = CORNER_RADIUS_DP * context.resources.displayMetrics.density
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
                    val graph = drawGraph(points, graphSize, cornerRadiusPx, graphSide)
                    emitter.updateView(
                        if (compactLayout == null) {
                            fullLayoutViews(context, graph, currentValue, maxValue.value, fullValueSizeSp)
                        } else {
                            compactLayoutViews(context, graph, currentValue, compactLayout, config.textSize)
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

    /**
     * Scope for one view attachment's coroutines, all cancelled together when it detaches. A
     * [SupervisorJob] so a failure in the preview buffer or max-value collection doesn't cancel
     * the render loop alongside it.
     */
    private fun newViewScope() = CoroutineScope(Dispatchers.IO + SupervisorJob() + failureLogger)

    /**
     * The compact side-by-side layout, for a tile below [COMPACT_ROW_SPAN_THRESHOLD] with no
     * room for a header text row above the graph; null for the full layout otherwise.
     *
     * The value goes on whichever side matches the user's configured alignment, mirroring the
     * sample extension's CustomSpeed, which places its own value the same way for the same reason.
     */
    private fun compactLayoutFor(context: Context, config: ViewConfig): CompactLayout? =
        if (config.gridSize.second <= COMPACT_ROW_SPAN_THRESHOLD) {
            CompactLayout(
                textOnRight = config.alignment != ViewConfig.Alignment.LEFT,
                valuePadding = compactValuePadding(context, config.textSize, config.viewSize.second),
            )
        } else {
            null
        }

    /**
     * Width and height in pixels to draw the graph's bitmap at.
     *
     * [ViewConfig.viewSize] is the full tile and only nominal at that -- Karoo reports the same
     * size for every field of a given gridSize, then lays them out a pixel or two apart. So the
     * bitmap is drawn at roughly the space left once Karoo's header and borders are taken out
     * (see [HEADER_HEIGHT_PX] and [WIDTH_INSET_PX]), and the ImageViews in both layouts stretch it
     * to the exact size with scaleType="fitXY". Stretching is harmless because the bitmap holds
     * only bars; the values are TextViews outside it, so their size never depends on how much the
     * bitmap is scaled.
     *
     * In [compactLayout] the bars only get the graph's share of the row, the value taking the
     * rest, so they're drawn at that share of the width -- drawn at the full width and squeezed
     * into half of it, they'd come out coarse.
     */
    private fun graphBitmapSize(config: ViewConfig, compactLayout: CompactLayout?): Pair<Int, Int> {
        val (viewWidth, viewHeight) = config.viewSize
        val tileGraphWidth = viewWidth - WIDTH_INSET_PX
        val width = if (compactLayout != null) {
            (tileGraphWidth * COMPACT_GRAPH_WIDTH_FRACTION).roundToInt()
        } else {
            tileGraphWidth
        }
        val height = (viewHeight - HEADER_HEIGHT_PX).roundToInt()
        return width.coerceAtLeast(1) to height.coerceAtLeast(1)
    }

    /**
     * The buffer to draw from. Preview (the profile editor) gets its own short-lived synthetic
     * buffer, started in [viewScope] so it ends with this view attachment, instead of the shared
     * real-data one -- there's no real ride to buffer in that context, and preview data has no
     * reason to persist beyond it.
     */
    private fun bufferFor(config: ViewConfig, viewScope: CoroutineScope): BufferedDataStream =
        if (config.preview) {
            BufferedDataStream(sampleDataStream(), SAMPLE_INTERVAL, WINDOW).also { preview ->
                viewScope.launch { preview.start() }
            }
        } else {
            buffer
        }

    /**
     * The max value to show beside the current one, or null while there's none: always, for a
     * metric with no [maxValueSource], and until its first reading for one with it.
     *
     * On a real ride [maxValueSource] is collected directly into it, since Karoo's own ride-wide
     * stat (e.g. MAX_POWER) is more correct than re-deriving one from this much-shorter window.
     * Preview has no real ride for it to report from, so it's left for the render loop to
     * approximate from the synthetic readings instead.
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

    /** The bars alone, at [size], clipped to the tile's rounded corners on [graphSide]. */
    private fun drawGraph(
        points: List<Pair<Long, Double>>,
        size: Pair<Int, Int>,
        cornerRadiusPx: Float,
        graphSide: RoundedSide,
    ): Bitmap = createBitmap(width = size.first, height = size.second).also { bitmap ->
        Canvas(bitmap).apply {
            clipToRoundedCorner(cornerRadiusPx, graphSide)
            drawBars(points, WINDOW, SAMPLE_INTERVAL, ZonePalette(zones.value))
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
     * The compact layout: the value beside the graph, on the side [layout] puts it.
     *
     * The value is sized at Karoo's own [textSize] for the tile, which is what Karoo sizes its
     * plain numeric fields from -- so handing the same number to a TextView reproduces them
     * exactly, with no measuring and nothing left to scale it afterwards.
     */
    private fun compactLayoutViews(
        context: Context,
        graph: Bitmap,
        currentValue: Double,
        layout: CompactLayout,
        textSize: Int,
    ): RemoteViews {
        val valueId = if (layout.textOnRight) value_end else value_start
        val unusedValueId = if (layout.textOnRight) value_start else value_end
        val padding = layout.valuePadding
        return RemoteViews(context.packageName, view_scrolling_graph_compact).apply {
            setImageViewBitmap(graph_image, graph)
            setViewVisibility(valueId, View.VISIBLE)
            setViewVisibility(unusedValueId, View.GONE)
            setTextViewText(valueId, formatValue(currentValue))
            setTextViewTextSize(valueId, TypedValue.COMPLEX_UNIT_SP, textSize.toFloat())
            setViewPadding(
                valueId,
                if (layout.textOnRight) 0 else padding.side,
                padding.top,
                if (layout.textOnRight) padding.side else 0,
                0,
            )
        }
    }

    private fun formatValue(value: Double) = "${value.roundToInt()}$unitLabel"

    /**
     * Padding for the compact value's TextView, in pixels. [top] is what positions the digits
     * vertically, and is usually negative: the view is top-aligned, and a font's ascent reaches
     * higher than its digits do, so the text has to be pulled up by at least that difference for
     * the numbers to start where the view does.
     *
     * Positioning from the top rather than using bottom gravity, even though the target is a gap
     * below the digits: gravity puts the *descender* against the bottom, and since digits have
     * none, the numbers ride up by that much and lose their tops off the top of the tile.
     * Karoo positions its own text by baseline, which a TextView won't do for us.
     */
    private data class ValuePadding(val top: Int, val side: Int)

    /**
     * Measures [ValuePadding] for text drawn at Karoo's own [ViewConfig.textSize], which is in
     * sp and so needs the display's density applied before it means anything in pixels.
     *
     * Karoo's own fields hold their value a fixed gap above the bottom of the tile whatever its
     * height, so that's what this aims for -- but never higher than the top of the space we're
     * given, since on the shortest tiles the digits fill it entirely and anything more would cut
     * their tops off. Karoo has a little more room to play with there than extensions do: on a
     * 5-row page its own digits start slightly above where our view even begins.
     */
    private fun compactValuePadding(context: Context, textSize: Int, viewHeight: Int): ValuePadding {
        val metrics = context.resources.displayMetrics
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = VALUE_TYPEFACE
            this.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, textSize.toFloat(), metrics)
        }
        val digitBounds = Rect()
        paint.getTextBounds(CURRENT_VALUE_DIGIT_TEMPLATE, 0, 1, digitBounds)

        val contentHeight = viewHeight - HEADER_HEIGHT_PX
        val bottomGap = VALUE_BOTTOM_GAP_DP * metrics.density
        val digitsTop = maxOf(contentHeight - bottomGap, digitBounds.height().toFloat())
        return ValuePadding(
            top = (digitsTop + paint.ascent()).roundToInt(),
            side = (VALUE_EDGE_PADDING_DP * metrics.density).roundToInt(),
        )
    }

    /**
     * Value on whichever side of the tile matches the user's configured alignment
     * ([textOnRight]), with the graph filling the rest of the row. Used once a field is too
     * short for a separate header row above the graph -- see [COMPACT_ROW_SPAN_THRESHOLD].
     *
     * The value itself isn't drawn into the bitmap: view_scrolling_graph_compact.xml holds it in
     * a TextView so Karoo lays it out at an exact size, leaving the bitmap to render only the bars.
     */
    private data class CompactLayout(val textOnRight: Boolean, val valuePadding: ValuePadding) {
        /** Side of the tile the graph sits against, and so the side whose corners are rounded. */
        val graphSide: RoundedSide = if (textOnRight) RoundedSide.LEFT else RoundedSide.RIGHT
    }

    /**
     * Text size in sp for the full layout's value row, at [MAX_TEXT_SIZE_FRACTION] of it for the
     * max label beside it.
     *
     * Taken from Karoo's own [ViewConfig.textSize] for the tile, scaled down by
     * [VALUE_FULL_SIZE_FRACTION] since unlike a plain numeric field this one shares the tile with
     * a graph. Using textSize rather than the tile's height keeps it steady between fields of the
     * same gridSize, whose reported viewSize varies by a pixel or two.
     *
     * Shrunk further if the widest values the metric could ever show wouldn't fit side by side.
     * Both templates are measured at once because they share the row: 4 digits covers any
     * realistic reading, and the max only takes a share when this metric has one to show.
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

    private fun sampleDataStream(): Flow<Double> = flow {
        while (true) {
            emit(Random.nextDouble(previewValueRange.start, previewValueRange.endInclusive))
            delay(SAMPLE_INTERVAL)
        }
    }

    companion object {
        private val SAMPLE_INTERVAL = 1.seconds
        private val WINDOW = 2.minutes

        /**
         * The full layout's value, as a fraction of the size Karoo would use for a plain numeric
         * field of the same gridSize -- smaller, since this one shares its tile with a graph.
         * Chosen to land where the old height-derived sizing did on the tiles it was tuned
         * against, and applies unchanged to the shortest tiles that use this layout, where
         * anything larger would crowd out the bars.
         */
        private const val VALUE_FULL_SIZE_FRACTION = 0.36f

        /** Max value is a secondary stat, shown at this fraction of current value's font size. */
        private const val MAX_TEXT_SIZE_FRACTION = 0.65f

        /**
         * Widest current-value text this metric will ever plausibly need to show (4 digits
         * comfortably covers any realistic reading for power/HR) -- used to size a fixed slot for
         * it, in both the standard header row and the compact side-by-side layout, so a change in
         * digit count (e.g. HR crossing 99 to 100) never moves anything else.
         */
        private const val CURRENT_VALUE_DIGIT_TEMPLATE = "9999"

        /**
         * The same face Karoo draws its own numeric fields in: "Relative12-Regular.otf", which
         * the device's /system/etc/fonts.xml registers as the monospace family and aliases to
         * "relative". Identified by pulling the device's fonts and matching rendered glyph
         * metrics against a screenshot -- its "1" is 0.49x the cap height, against Karoo's
         * measured 0.50, where the default sans (IBM Plex, which Karoo's fonts.xml substitutes
         * for Roboto) draws a much wider 0.68 and doesn't match at any weight or scale.
         *
         * Named by the "relative" alias rather than "monospace": both resolve to this face, but
         * this one says which typeface is actually wanted. Anything else falls back to the
         * default sans.
         */
        private val VALUE_TYPEFACE: Typeface = Typeface.create("relative", Typeface.NORMAL)

        /**
         * Height of one full-width row, in [ViewConfig.gridSize] units. A profile page is a
         * 60-unit grid, and Karoo stacks up to five full-width rows before it starts splitting
         * into columns, so a single row is 60/5 = 12 units tall.
         */
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

        /**
         * Share of the row the bars get in compact layout, the value taking the rest. Must match
         * the layout_weights in view_scrolling_graph_compact.xml, which are what actually decide
         * the split -- this only sizes the bitmap drawn for it.
         */
        private const val COMPACT_GRAPH_WIDTH_FRACTION = 0.55f

        /**
         * Gap between the compact value and the tile edge it's aligned to. Karoo's own fields
         * leave about this much; in dp because it's a visual margin, not a share of anything
         * that scales with the tile.
         */
        private const val VALUE_EDGE_PADDING_DP = 3f

        /**
         * Gap Karoo leaves between its value and the bottom of the tile. Flat, not a share of
         * the tile: measured at the same ~7px on a 188px tile as on a 125px one.
         */
        private const val VALUE_BOTTOM_GAP_DP = 3.5f

        /**
         * Estimated corner radius of the tile itself, in dp -- ViewConfig has no field for it, so
         * this is a guess to be tuned on-device. A fixed dp value (converted to pixels via the
         * device's real density in startView) rather than a fraction of the tile's own size,
         * since real UI corner radii are a fixed physical size, not a percentage of their
         * container -- deriving it from height instead would make the mismatch worse on smaller
         * tiles, where a fixed radius is a bigger fraction of the tile.
         */
        private const val CORNER_RADIUS_DP = 12f
    }
}
