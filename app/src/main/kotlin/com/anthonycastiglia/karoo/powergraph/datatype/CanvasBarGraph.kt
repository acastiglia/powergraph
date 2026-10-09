package com.anthonycastiglia.karoo.powergraph.datatype

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.roundToInt
import kotlin.time.Duration

/**
 * The value range a graph's bars are drawn against: from a fixed [floor] up to the highest
 * reading in view, but never less than [minSpan] above the floor.
 *
 * Fixed bounds keep a bar's height meaning the same thing from one window to the next, so a
 * small wobble doesn't fill the whole graph.
 */
data class GraphScale(val floor: Double, val minSpan: Double) {
    init {
        require(minSpan > 0) { "minSpan ($minSpan) must be positive" }
    }

    fun ceilingFor(highestValue: Double): Double = maxOf(highestValue, floor + minSpan)
}

/**
 * Draws one bar per point within [plotArea], against [scale], with the most recent reading at
 * the plot's right edge. Edges snap to whole pixels so shared boundaries don't flicker as the
 * window scrolls.
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

