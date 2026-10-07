package com.anthonycastiglia.karoo.powergraph.datatype

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.roundToInt
import kotlin.time.Duration

/**
 * Which of a bitmap's corners to round, being the ones where it meets the edge of Karoo's tile.
 * An edge that meets something else -- the value beside it, or the row above it -- is left square,
 * since rounding there would clip the bars short of content they should run up against.
 */
enum class RoundedSide { LEFT, RIGHT, BOTTOM }

/**
 * Clips to the rounded corners Karoo draws around its tiles, which fall outside this bitmap
 * and are otherwise invisible to it -- without this, square-cornered bars flush against an
 * edge poke out past that rounding.
 *
 * Radii run clockwise from the top left corner, two per corner, as [Path.addRoundRect] takes
 * them.
 */
fun Canvas.clipToRoundedCorner(radius: Float, roundedSide: RoundedSide) {
    val radii = when (roundedSide) {
        RoundedSide.LEFT -> floatArrayOf(radius, radius, 0f, 0f, 0f, 0f, radius, radius)
        RoundedSide.RIGHT -> floatArrayOf(0f, 0f, radius, radius, radius, radius, 0f, 0f)
        RoundedSide.BOTTOM -> floatArrayOf(0f, 0f, 0f, 0f, radius, radius, radius, radius)
    }
    clipPath(
        Path().apply {
            addRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), radii, Path.Direction.CW)
        },
    )
}

/**
 * The value range a graph's bars are drawn against: from a fixed [floor] at the bottom up to the
 * highest reading in view, but never less than [minSpan] above the floor.
 *
 * A fixed floor keeps a bar's height meaning the same thing from one window to the next -- scaled
 * from the window's own lowest reading instead, that reading always draws at zero height, and a
 * small wobble fills the whole graph. [minSpan] does the same at the top, so a steady stretch of
 * readings doesn't stretch to full height just because nothing higher is in view.
 */
data class GraphScale(val floor: Double, val minSpan: Double) {
    init {
        require(minSpan > 0) { "minSpan ($minSpan) must be positive" }
    }

    /** The value drawn at the top of the graph, for a window whose highest reading is [highestValue]. */
    fun ceilingFor(highestValue: Double): Double = maxOf(highestValue, floor + minSpan)
}

/**
 * Bars are drawn within [plotArea], against [scale] from its floor at the plot's bottom up to
 * the highest point at its top, so spikes never clip off the top. The most recent reading ends
 * at the plot's right edge. A reading below the floor draws at zero height rather than below
 * the plot. With no points at all -- nothing has arrived yet, as before a sensor connects --
 * nothing is drawn.
 *
 * Edges snap to whole pixel columns. Two bars sharing a boundary compute it from the same
 * timestamp, so the raw floats already agree bit-for-bit, but that shared value sits at an
 * arbitrary fractional pixel position, drifting every second as the window scrolls.
 * Non-antialiased rect rasterization has to round a fractional boundary to a pixel column,
 * and that rounding isn't guaranteed stable between draws, so rounding here settles it rather
 * than leaving it to the rasterizer.
 */
fun Canvas.drawBars(
    points: List<Pair<Long, Double>>,
    plotArea: RectF,
    window: Duration,
    sampleInterval: Duration,
    scale: GraphScale,
    palette: ZonePalette
) {
    if (points.isEmpty()) return
    val ceiling = scale.ceilingFor(points.maxOf { it.second })
    val valueSpan = ceiling - scale.floor

    val windowMs = window.inWholeMilliseconds
    val windowEnd = points.last().first + sampleInterval.inWholeMilliseconds
    val windowStart = windowEnd - windowMs
    fun xFor(timestamp: Long) =
        (plotArea.left + plotArea.width() * (timestamp - windowStart).toFloat() / windowMs).roundToInt().toFloat()

    val barPaint = Paint().apply { style = Paint.Style.FILL }
    points.forEachIndexed { index, (timestamp, value) ->
        val left = xFor(timestamp)
        val right = if (index < points.lastIndex) xFor(points[index + 1].first) else xFor(windowEnd)
        val barHeight = plotArea.height() * ((value - scale.floor) / valueSpan).coerceAtLeast(0.0).toFloat()
        barPaint.color = palette.colorFor(value)
        drawRect(left, plotArea.bottom - barHeight, right, plotArea.bottom, barPaint)
    }
}

