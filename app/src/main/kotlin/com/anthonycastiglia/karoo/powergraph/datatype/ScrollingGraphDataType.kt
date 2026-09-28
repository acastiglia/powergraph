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
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.UserProfile
import io.hammerhead.karooext.models.ViewConfig
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
import java.util.concurrent.ConcurrentLinkedDeque
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.minutes

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

    /**
     * Buffered view of a flow of values, each stamped with the time it arrived, sampled at
     * [samplingInterval] and trimmed to a trailing [bufferWindow]. Call [start] once (it suspends
     * for as long as [source] keeps emitting); any number of consumers can call [snapshot]
     * concurrently to get the current buffered values.
     *
     * Takes plain values rather than whatever carried them, leaving callers to map and filter
     * their own source upstream -- unpacking on read would repeat that work over the same
     * unchanged entries every time a reader redraws, which happens far more often than values
     * arrive. Anything a caller filters out simply leaves a gap in the timestamps.
     *
     * Backed by [ConcurrentLinkedDeque] rather than an immutable list rebuilt on every accepted
     * value: [start] is the only writer, and its `addLast`/`pollFirst` calls are O(1) with no
     * bulk reallocation, and safe to run concurrently with [snapshot] without an explicit lock --
     * [ConcurrentLinkedDeque] handles that internally. [snapshot] still does one copy into a
     * [List], since callers need indexed access a deque can't provide, but that's the only copy
     * anywhere in this class, and it happens on read rather than write.
     */
    private class BufferedDataStream(
        private val source: Flow<Double>,
        private val samplingInterval: Duration,
        private val bufferWindow: Duration,
    ) {
        private val buffer = ConcurrentLinkedDeque<Pair<Long, Double>>()
        private var lastSampleAt = 0L

        suspend fun start() {
            source.collect { value ->
                val now = System.currentTimeMillis()
                if (now - lastSampleAt >= samplingInterval.inWholeMilliseconds) {
                    lastSampleAt = now
                    buffer.addLast(now to value)
                    while ((buffer.peekFirst()?.first ?: now).let { now - it > bufferWindow.inWholeMilliseconds }) {
                        buffer.pollFirst()
                    }
                }
            }
        }

        /** Read-only snapshot of the buffered readings, oldest first. */
        fun snapshot(): List<Pair<Long, Double>> = buffer.toList()
    }

    private val tag = "ScrollingGraphDataType[$typeId]"

    // Buffering starts once, at construction, and keeps running for the life of this
    // instance -- independent of any specific view attachment, so graph history isn't lost
    // across page switches or between separate startView attach/detach cycles. Every
    // non-preview startView call reads from this same shared instance.
    //
    // SupervisorJob so the two coroutines launched below (buffering and zone collection) fail
    // independently -- an exception in one shouldn't silently cancel the other, since they're
    // otherwise unrelated.
    private val extensionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val buffer = BufferedDataStream(dataSource.singleValues(), SAMPLE_INTERVAL, WINDOW)

    // Zone thresholds for per-bar coloring, kept live rather than fetched once in case they
    // change while this instance is running. Left empty (flat color, see ZonePalette) when this
    // metric has no zone concept and zonesSource wasn't supplied.
    private val zones = MutableStateFlow<List<UserProfile.Zone>>(emptyList())

    init {
        extensionScope.launch { buffer.start() }
        zonesSource?.let { source ->
            extensionScope.launch { source.collect { zones.value = it } }
        }
    }

    override fun startStream(emitter: Emitter<StreamState>) {
        Log.d(tag, "start stream")
        val job = CoroutineScope(Dispatchers.IO).launch {
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

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        Log.d(tag, "start view with $emitter and config $config")
        emitter.onNext(UpdateGraphicConfig(showHeader = true))

        val viewScope = CoroutineScope(Dispatchers.IO)

        // config.viewSize is the full tile and only nominal at that -- Karoo reports the same
        // size for every field of a given gridSize, then lays them out a pixel or two apart. The
        // bitmap is drawn to fit inside the smallest that tile is actually laid out at, minus
        // Karoo's header, so it is never scaled to fit -- see HEADER_HEIGHT_PX and WIDTH_INSET_PX.
        val (viewWidth, viewHeight) = config.viewSize
        val tileGraphSize = (viewWidth - WIDTH_INSET_PX) to (viewHeight - HEADER_HEIGHT_PX).roundToInt()

        // Corner radius in pixels for this device's actual density -- see CORNER_RADIUS_DP.
        val cornerRadiusPx = CORNER_RADIUS_DP * context.resources.displayMetrics.density

        // Below a single stacked row's worth of height there's no room for a header text row
        // above the graph (see COMPACT_ROW_SPAN_THRESHOLD) -- switch to a compact side-by-side
        // layout instead, with the value on whichever side matches the user's configured
        // alignment (mirroring the sample extension's CustomSpeed, which places its own value
        // the same way for the same reason).
        val compactLayout = if (config.gridSize.second <= COMPACT_ROW_SPAN_THRESHOLD) {
            CompactLayout(
                textOnRight = config.alignment != ViewConfig.Alignment.LEFT,
                valuePadding = compactValuePadding(context, config.textSize, viewHeight),
            )
        } else {
            null
        }

        // Text size for the full layout's value row, fixed for this attachment: it depends
        // only on the tile's gridSize, which doesn't change while the view is attached.
        val fullValueSizeSp = if (compactLayout == null) fullValueTextSizeSp(context, config.textSize, viewWidth) else 0f

        // In compact layout the bars only get the graph's share of the row, the value taking the
        // rest, so they're drawn at that share of the width -- the ImageView stretches them to
        // whatever it actually gets, which bars tolerate but would leave coarse if drawn at the
        // full width and squeezed into half of it.
        val graphViewSize = if (compactLayout != null) {
            (tileGraphSize.first * COMPACT_GRAPH_WIDTH_FRACTION).roundToInt() to tileGraphSize.second
        } else {
            tileGraphSize
        }

        // Preview (profile editor) gets its own short-lived synthetic buffer scoped to this
        // view attachment, instead of the shared real-data one -- there's no real ride to
        // buffer in that context, and preview data has no reason to persist beyond it.
        val activeBuffer = if (config.preview) {
            BufferedDataStream(sampleDataStream(), SAMPLE_INTERVAL, WINDOW).also { preview ->
                viewScope.launch { preview.start() }
            }
        } else {
            buffer
        }

        // Max value: null when this metric has no maxValueSource (never show a max stat) or,
        // for one that does, until it's populated below. On a real ride, maxValueSource is
        // read directly (e.g. Karoo's own ride-wide MAX_POWER, more correct than re-deriving
        // it from our own much-shorter window); in preview there's no real ride for it to
        // report from, so the render loop below approximates it from the buffer instead.
        val maxValue = MutableStateFlow<Double?>(null)
        if (maxValueSource != null && !config.preview) {
            viewScope.launch {
                maxValueSource.singleValues().collect { maxValue.value = it }
            }
        }

        viewScope.launch {
            while (true) {
                val points = activeBuffer.snapshot()
                val currentValue = points.lastOrNull()?.second ?: 0.0
                // Preview has no real ride for maxValueSource to report from -- approximate it
                // from the synthetic buffer instead, whenever this metric wants a max at all.
                if (config.preview && maxValueSource != null) {
                    maxValue.value = maxOf(maxValue.value ?: 0.0, currentValue)
                }
                val bitmap = createBitmap(
                    width = graphViewSize.first.coerceAtLeast(1),
                    height = graphViewSize.second.coerceAtLeast(1)
                )
                val canvas = Canvas(bitmap)
                canvas.clipToRoundedCorner(cornerRadiusPx, compactLayout?.graphSide ?: RoundedSide.BOTTOM)
                canvas.drawBars(
                    points,
                    WINDOW,
                    SAMPLE_INTERVAL,
                    ZonePalette(zones.value)
                )

                val valueText = "${currentValue.roundToInt()}$unitLabel"
                emitter.updateView(
                    if (compactLayout == null) {
                        RemoteViews(context.packageName, view_scrolling_graph).apply {
                            setImageViewBitmap(graph_image, bitmap)
                            setTextViewText(value, valueText)
                            setTextViewTextSize(value, TypedValue.COMPLEX_UNIT_SP, fullValueSizeSp)
                            // Space is only given up to the max label while there's one to show:
                            // a metric without a maxValueSource never has one, and one with it
                            // has nothing to report until its first reading arrives.
                            setViewVisibility(max_value, if (maxValue.value == null) View.GONE else View.VISIBLE)
                            maxValue.value?.let {
                                setTextViewText(max_value, "MAX ${it.roundToInt()}$unitLabel")
                                setTextViewTextSize(
                                    max_value,
                                    TypedValue.COMPLEX_UNIT_SP,
                                    fullValueSizeSp * MAX_TEXT_SIZE_FRACTION,
                                )
                            }
                        }
                    } else {
                        // Karoo sizes its own value text from config.textSize, so handing the
                        // same number to a TextView reproduces it exactly -- no measuring, and
                        // nothing left to scale it afterwards.
                        val valueId = if (compactLayout.textOnRight) value_end else value_start
                        val unusedValueId = if (compactLayout.textOnRight) value_start else value_end
                        val padding = compactLayout.valuePadding
                        RemoteViews(context.packageName, view_scrolling_graph_compact).apply {
                            setImageViewBitmap(graph_image, bitmap)
                            setViewVisibility(valueId, View.VISIBLE)
                            setViewVisibility(unusedValueId, View.GONE)
                            setTextViewText(valueId, "${currentValue.roundToInt()}$unitLabel")
                            setTextViewTextSize(valueId, TypedValue.COMPLEX_UNIT_SP, config.textSize.toFloat())
                            setViewPadding(
                                valueId,
                                if (compactLayout.textOnRight) 0 else padding.side,
                                padding.top,
                                if (compactLayout.textOnRight) padding.side else 0,
                                0,
                            )
                        }
                    },
                )
                delay(SAMPLE_INTERVAL)
            }
        }
        emitter.setCancellable {
            Log.d(tag, "stop view")
            viewScope.cancel() // cancels both the render loop and, if preview, its buffering coroutine
        }
    }

    /**
     * Value on whichever side of the tile matches the user's configured alignment
     * ([textOnRight]), with the graph filling the rest of the row. Used once a field is too
     * short for a separate header row above the graph -- see [COMPACT_ROW_SPAN_THRESHOLD].
     *
     * The value itself isn't drawn here: view_scrolling_graph_compact.xml holds it in a TextView
     * so Karoo lays it out at an exact size, leaving this to render only the bars.
     */
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
        // The full layout's value, as a fraction of the size Karoo would use for a plain
        // numeric field of the same gridSize -- smaller, since this one shares its tile with a
        // graph. Chosen to land where the old height-derived sizing did on the tiles it was
        // tuned against, and applies unchanged to the shortest tiles that use this layout,
        // where anything larger would crowd out the bars.
        private const val VALUE_FULL_SIZE_FRACTION = 0.36f

        // Max value is a secondary stat, shown at this fraction of current value's font size.
        private const val MAX_TEXT_SIZE_FRACTION = 0.65f

        // Widest current-value text this metric will ever plausibly need to show (4 digits
        // comfortably covers any realistic reading for power/HR) -- used to size a fixed slot
        // for it, in both the standard header row and the compact side-by-side layout, so a
        // change in digit count (e.g. HR crossing 99 to 100) never moves anything else.
        private const val CURRENT_VALUE_DIGIT_TEMPLATE = "9999"

        // Matched to Karoo's own numeric fields by measuring screenshots: their digits are
        // ~0.61x as wide as they are tall, with a stroke width ~0.12x their cap height -- a
        // light weight, not the bold they first appear to be (they only read as heavy because
        // they're large).
        //
        // This device resolves the weight families but not the condensed one: "sans-serif-black"
        // measured visibly heavier and wider than bold, while "sans-serif-condensed" came out
        // identical to it, i.e. no condensed face is installed to fall back to. So the weight
        // comes from the family name and the narrowing from textScaleX.
        //
        // The same face Karoo draws its own numeric fields in: "Relative12-Regular.otf", which
        // the device's /system/etc/fonts.xml registers as the monospace family and aliases to
        // "relative". Identified by pulling the device's fonts and matching rendered glyph
        // metrics against a screenshot -- its "1" is 0.49x the cap height, against Karoo's
        // measured 0.50, where the default sans (IBM Plex, which Karoo's fonts.xml substitutes
        // for Roboto) draws a much wider 0.68 and doesn't match at any weight or scale.
        //
        // Named by the "relative" alias rather than "monospace": both resolve to this face, but
        // this one says which typeface is actually wanted. Anything else falls back to the
        // default sans, i.e. what this drew before.
        private val VALUE_TYPEFACE: Typeface = Typeface.create("relative", Typeface.NORMAL)

        // A profile page is a 60-unit grid (see ViewConfig.gridSize); Karoo stacks up to five
        // full-width rows before it starts splitting into columns, so a single row is 60/5 = 12
        // units tall. The standard header-row-plus-graph layout needs roughly twice that for
        // both current and max value text to stay legible -- below it, there's no room for a
        // separate header row at all, so rendering switches to the compact side-by-side layout
        // instead (see CompactLayout). Guessed threshold awaiting on-device tuning.
        private const val SINGLE_ROW_SPAN = 12
        private const val COMPACT_ROW_SPAN_THRESHOLD = SINGLE_ROW_SPAN * 2

        // How much of config.viewSize to give up so the bitmap fits its ImageView without being
        // scaled: the header's ~46px at the top, and a margin for the tile's borders.
        //
        // Both are over-estimates on purpose, because view_scrolling_graph.xml's ImageView uses
        // scaleType="center" -- the bitmap is drawn at its own size, centred, and whatever
        // doesn't fit is cropped. Undersizing costs a pixel or two of slack around the bars;
        // oversizing crops them, and crops the value's margin along with it.
        //
        // Any scaleType that fits instead of cropping is what to avoid here. Karoo reports one
        // nominal viewSize per gridSize but lays the tiles out a pixel or two apart, so a bitmap
        // scaled to fit is scaled by a different amount per row -- identical bitmaps for two
        // fields measured 74px and 72px tall on screen. There's no way to correct for that from
        // here, since the real height isn't in ViewConfig; drawing 1:1 sidesteps it, and leaves
        // the text the size it was drawn, in every row. Karoo's own fields avoid the problem the
        // same way, by drawing text into the tile rather than scaling a picture of it.
        private const val HEADER_HEIGHT_PX = 48f
        private const val WIDTH_INSET_PX = 12

        // Share of the row the bars get in compact layout, the value taking the rest. Must match
        // the layout_weights in view_scrolling_graph_compact.xml, which are what actually decide
        // the split -- this only sizes the bitmap drawn for it.
        private const val COMPACT_GRAPH_WIDTH_FRACTION = 0.55f

        // Gap between the compact value and the tile edge it's aligned to. Karoo's own
        // fields leave about this much; in dp because it's a visual margin, not a share of
        // anything that scales with the tile.
        private const val VALUE_EDGE_PADDING_DP = 3f

        // Gap Karoo leaves between its value and the bottom of the tile. Flat, not a share
        // of the tile: measured at the same ~7px on a 188px tile as on a 125px one.
        private const val VALUE_BOTTOM_GAP_DP = 3.5f

        // Estimated corner radius of the tile itself, in dp -- ViewConfig has no field for
        // it, so this is a guess to be tuned on-device. A fixed dp value (converted to pixels
        // via the device's real density in startView) rather than a fraction of the tile's
        // own size, since real UI corner radii are a fixed physical size, not a percentage of
        // their container -- deriving it from height instead would make the mismatch worse on
        // smaller tiles, where a fixed radius is a bigger fraction of the tile.
        private const val CORNER_RADIUS_DP = 12f
    }
}
