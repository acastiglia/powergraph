package com.anthonycastiglia.karoo.powergraph.data

import kotlinx.coroutines.flow.Flow
import java.util.concurrent.ConcurrentLinkedDeque
import kotlin.time.Duration

/**
 * Samples [source] at [samplingInterval], stamping each value with its arrival time and keeping
 * the trailing [bufferWindow]. [start] suspends while [source] emits; [snapshot] is safe to call
 * concurrently. Values the caller filters out upstream leave gaps in the timestamps.
 */
class BufferedDataStream(
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