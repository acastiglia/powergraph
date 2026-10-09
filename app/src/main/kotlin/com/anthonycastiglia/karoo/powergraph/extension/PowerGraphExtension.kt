package com.anthonycastiglia.karoo.powergraph.extension

import com.anthonycastiglia.karoo.powergraph.data.GRAPH_FIELDS
import com.anthonycastiglia.karoo.powergraph.data.GraphField
import com.anthonycastiglia.karoo.powergraph.data.PowerGraphSettings
import com.anthonycastiglia.karoo.powergraph.datatype.ScrollingGraphDataType
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class PowerGraphExtension : KarooExtension("powergraph", "1.0") {
    private val karooSystem by lazy { KarooSystemService(this) }
    private val settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val settings by lazy { PowerGraphSettings(this, settingsScope) }

    override val types by lazy { GRAPH_FIELDS.map(::dataTypeFor) }

    /**
     * [field]'s data type, with its Karoo stream ids opened as flows, converted to the displayed
     * units where [GraphField.displayFactor] says so, and its settings wired in.
     */
    private fun dataTypeFor(field: GraphField): ScrollingGraphDataType {
        val fieldSettings = settings.forField(field)
        fun displayStream(dataType: String): Flow<StreamState> {
            val stream = karooSystem.streamDataFlow(dataType)
            val displayFactor = field.displayFactor ?: return stream
            return stream.scaledBy(karooSystem.consumerFlow<UserProfile>().map { displayFactor(it) })
        }
        return ScrollingGraphDataType(
            extension = extension,
            typeId = field.typeId,
            dataSource = displayStream(field.dataType),
            previewSource = field.previewSource,
            scale = field.scale,
            aggregationSources = field.aggregationDataTypes.mapValues { (_, dataType) -> displayStream(dataType) },
            aggregations = fieldSettings.aggregations,
            zonesSource = field.zones?.let { zones -> karooSystem.consumerFlow<UserProfile>().map { zones(it) } },
            zoneColorsEnabled = fieldSettings.zoneColors,
            currentValueSmoothingSeconds = fieldSettings.smoothingSeconds,
        )
    }

    override fun onCreate() {
        super.onCreate()
        karooSystem.connect()
    }

    override fun onDestroy() {
        karooSystem.disconnect()
        settingsScope.cancel()
        super.onDestroy()
    }
}
