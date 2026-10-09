package com.anthonycastiglia.karoo.powergraph.extension

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.KarooEvent
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine

fun KarooSystemService.streamDataFlow(dataTypeId: String): Flow<StreamState> {
    return callbackFlow {
        val listenerId = addConsumer(OnStreamState.StartStreaming(dataTypeId)) { event: OnStreamState ->
            trySendBlocking(event.state)
        }
        awaitClose {
            removeConsumer(listenerId)
        }
    }
}

inline fun <reified T : KarooEvent> KarooSystemService.consumerFlow(): Flow<T> {
    return callbackFlow {
        val listenerId = addConsumer<T> {
            trySendBlocking(it)
        }
        awaitClose {
            removeConsumer(listenerId)
        }
    }
}

/**
 * These states with every streamed value multiplied by the latest [factor], for converting a
 * stream to the units the rider sees. Waits for the first factor; states that carry no value
 * pass through unchanged.
 */
fun Flow<StreamState>.scaledBy(factor: Flow<Double>): Flow<StreamState> =
    combine(factor) { state, multiplier ->
        when (state) {
            is StreamState.Streaming -> state.copy(
                dataPoint = state.dataPoint.copy(values = state.dataPoint.values.mapValues { it.value * multiplier }),
            )
            else -> state
        }
    }
