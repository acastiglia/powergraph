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
 * Bars are scaled to the span between the points' own lowest and highest value, so spikes
 * never clip off the top. That span is floored a unit wide, leaving a flat set of readings to
 * scale against 1 rather than divide by 0. With no points at all -- nothing has arrived yet,
 * as before a sensor connects -- there is no span to scale to, so nothing is drawn.
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
    window: Duration,
    sampleInterval: Duration,
    palette: ZonePalette
) {
    if (points.isEmpty()) return
    val lowestValue = points.minOf { it.second }
    val highestValue = points.maxOf { it.second }.coerceAtLeast(lowestValue + 1.0)
    val valueSpan = highestValue - lowestValue

    val windowMs = window.inWholeMilliseconds
    val windowEnd = points.last().first + sampleInterval.inWholeMilliseconds
    val windowStart = windowEnd - windowMs
    fun xFor(timestamp: Long) =
        (width * (timestamp - windowStart).toFloat() / windowMs).roundToInt().toFloat()

    val barPaint = Paint().apply { style = Paint.Style.FILL }
    points.forEachIndexed { index, (timestamp, value) ->
        val left = xFor(timestamp)
        val right = if (index < points.lastIndex) xFor(points[index + 1].first) else xFor(windowEnd)
        val barHeight = height * ((value - lowestValue) / valueSpan).toFloat()
        barPaint.color = palette.colorFor(value)
        drawRect(left, height - barHeight, right, height.toFloat(), barPaint)
    }
}

