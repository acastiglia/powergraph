package com.anthonycastiglia.karoo.powergraph.data

import kotlinx.coroutines.flow.Flow
import java.util.concurrent.ConcurrentLinkedDeque
import kotlin.time.Duration

/**
 * Buffered view of a flow of values, each stamped with the time it arrived, sampled at
 * [samplingInterval] and trimmed to a trailing [bufferWindow]. Call [start] once (it suspends
 * for as long as [source] keeps emitting); any number of consumers can call [snapshot]
 * concurrently to get the current buffered values.
 *
 * Takes plain values rather than whatever carried them, leaving callers to map and filter
 * their own source upstream -- unpacking on read would repeat that work over the same
 * unchanged entries every time a reader redraws, which happens far more often than values
 * arrive. Anything a caller filters out simply leaves a gap in the timestamps.
 *
 * Backed by [java.util.concurrent.ConcurrentLinkedDeque] rather than an immutable list rebuilt on every accepted
 * value: [start] is the only writer, and its `addLast`/`pollFirst` calls are O(1) with no
 * bulk reallocation, and safe to run concurrently with [snapshot] without an explicit lock --
 * [java.util.concurrent.ConcurrentLinkedDeque] handles that internally. [snapshot] still does one copy into a
 * [List], since callers need indexed access a deque can't provide, but that's the only copy
 * anywhere in this class, and it happens on read rather than write.
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