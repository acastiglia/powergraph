package com.anthonycastiglia.karoo.powergraph.datatype

import android.graphics.Color
import io.hammerhead.karooext.models.UserProfile

/**
 * The color a value is drawn in, by which of the rider's training [zones] it falls into.
 *
 * Built from the rider's own configured zones rather than fixed thresholds, since those are
 * personal to them and can change mid-ride; a metric with no zone concept at all (cadence, say)
 * passes an empty list and gets a flat color throughout.
 */
class ZonePalette(private val zones: List<UserProfile.Zone>) {

    /** Falls back to the lowest zone below the first floor (coasting under zone 1). */
    fun colorFor(value: Double): Int {
        if (zones.isEmpty()) return UNZONED_COLOR
        val zoneIndex = zones.indexOfLast { value >= it.min }.coerceAtLeast(0)
        return TrainingZone.entries.getOrElse(zoneIndex) { TrainingZone.entries.last() }.color
    }

    /** Matched by index to the rider's zones; zones beyond the last entry reuse its color. */
    private enum class TrainingZone(val color: Int) {
        RECOVERY(Color.GRAY),
        ENDURANCE(Color.rgb(0, 120, 215)),
        TEMPO(Color.rgb(0, 150, 80)),
        THRESHOLD(Color.rgb(230, 190, 0)),
        VO2_MAX(Color.rgb(230, 120, 0)),
        ANAEROBIC(Color.RED),
        NEUROMUSCULAR(Color.rgb(120, 0, 120)),
    }

    companion object {
        private val UNZONED_COLOR = Color.DKGRAY
    }
}
