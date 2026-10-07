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

    /**
     * Zones are ascending by [UserProfile.Zone.min], so a value belongs to the last one whose
     * floor it has reached -- falling back to the lowest zone for anything below the first floor,
     * which the rider can reach by coasting below their own zone 1.
     */
    fun colorFor(value: Double): Int {
        if (zones.isEmpty()) return UNZONED_COLOR
        val zoneIndex = zones.indexOfLast { value >= it.min }.coerceAtLeast(0)
        return TrainingZone.entries.getOrElse(zoneIndex) { TrainingZone.entries.last() }.color
    }

    /**
     * Training zones low to high, matching common cycling zone conventions, each matched to the
     * rider's configured zone at the same index. Zone count varies by rider (typically 6-7);
     * zones beyond the last entry here reuse its color.
     */
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
        /** For a metric with no zones, or a rider who hasn't configured any. */
        private val UNZONED_COLOR = Color.DKGRAY
    }
}
