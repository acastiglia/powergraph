package com.anthonycastiglia.karoo.powergraph.data

import androidx.annotation.StringRes
import com.anthonycastiglia.karoo.powergraph.R.string.field_heart_rate
import com.anthonycastiglia.karoo.powergraph.R.string.field_power
import com.anthonycastiglia.karoo.powergraph.datatype.GraphScale
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.flow.Flow

/**
 * One scrolling graph data field: everything that differs from one field to the next, as plain
 * data, so the extension and the settings screen can each build their parts from [GRAPH_FIELDS].
 *
 * Karoo streams are named by their [DataType.Type] ids rather than held as flows, since the
 * settings screen has no Karoo connection to open them; the extension resolves them.
 *
 * [typeId] must match the field's declaration in extension_info.xml. [settingsKey] prefixes the
 * field's preference keys. [aggregationDataTypes] holds a ride-wide stat stream for each
 * [Aggregation] the metric has, and only those are offered or shown. [zones] picks the field's
 * zones from the rider's profile, null for a metric without zones. [smoothing] is null for a
 * field whose current value is never smoothed.
 */
class GraphField(
    val typeId: String,
    val settingsKey: String,
    @StringRes val label: Int,
    val dataType: String,
    val aggregationDataTypes: Map<Aggregation, String>,
    val zones: ((UserProfile) -> List<UserProfile.Zone>)?,
    val smoothing: SmoothingOptions?,
    val scale: GraphScale,
    val previewSource: Flow<Double>,
)

/** The current-value smoothing windows a field offers, in seconds, and the one used until another is picked. */
data class SmoothingOptions(val seconds: List<Int>, val defaultSeconds: Int)

/** Every graph field this extension provides, in the order the settings screen lists them. */
val GRAPH_FIELDS = listOf(
    GraphField(
        typeId = "power-graph",
        settingsKey = "power",
        label = field_power,
        dataType = DataType.Type.POWER,
        aggregationDataTypes = mapOf(
            Aggregation.MAX to DataType.Type.MAX_POWER,
            Aggregation.AVERAGE to DataType.Type.AVERAGE_POWER,
            Aggregation.NORMALIZED to DataType.Type.NORMALIZED_POWER,
        ),
        zones = { it.powerZones },
        smoothing = SmoothingOptions(seconds = listOf(1, 3, 10, 30), defaultSeconds = 3),
        scale = GraphScale(floor = 0.0, minSpan = 200.0),
        previewSource = randomDoubles(min = 0.0, max = 300.0),
    ),
    GraphField(
        typeId = "heart-rate-graph",
        settingsKey = "heart_rate",
        label = field_heart_rate,
        dataType = DataType.Type.HEART_RATE,
        aggregationDataTypes = mapOf(
            Aggregation.MAX to DataType.Type.MAX_HR,
            Aggregation.AVERAGE to DataType.Type.AVERAGE_HR,
        ),
        zones = { it.heartRateZones },
        smoothing = null,
        scale = GraphScale(floor = 40.0, minSpan = 60.0),
        previewSource = randomWalk(start = 120.0, min = 45.0, max = 190.0, maxStepSize = 2.0),
    )
)
