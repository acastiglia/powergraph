package com.anthonycastiglia.karoo.powergraph.extension

import com.anthonycastiglia.karoo.powergraph.data.randomDoubles
import com.anthonycastiglia.karoo.powergraph.data.randomWalk
import com.anthonycastiglia.karoo.powergraph.datatype.GraphScale
import com.anthonycastiglia.karoo.powergraph.datatype.ScrollingGraphDataType
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.flow.map

class PowerGraphExtension : KarooExtension("powergraph", "1.0") {
    private val karooSystem by lazy { KarooSystemService(this) }

    override val types by lazy {
        listOf(
            ScrollingGraphDataType(
                extension = extension,
                typeId = "power-graph",
                dataSource = karooSystem.streamDataFlow(DataType.Type.POWER),
                previewSource = randomDoubles(min = 0.0, max = 300.0),
                scale = GraphScale(floor = 0.0, minSpan = 200.0),
                maxValueSource = karooSystem.streamDataFlow(DataType.Type.MAX_POWER),
                zonesSource = karooSystem.consumerFlow<UserProfile>().map { it.powerZones },
            ),
            ScrollingGraphDataType(
                extension = extension,
                typeId = "heart-rate-graph",
                dataSource = karooSystem.streamDataFlow(DataType.Type.HEART_RATE),
                previewSource = randomWalk(start = 120.0, min = 45.0, max = 190.0, maxStepSize = 2.0),
                scale = GraphScale(floor = 40.0, minSpan = 60.0),
                maxValueSource = null,
                zonesSource = karooSystem.consumerFlow<UserProfile>().map { it.heartRateZones },
            )
        )
    }

    override fun onCreate() {
        super.onCreate()
        karooSystem.connect()
    }

    override fun onDestroy() {
        karooSystem.disconnect()
        super.onDestroy()
    }
}
