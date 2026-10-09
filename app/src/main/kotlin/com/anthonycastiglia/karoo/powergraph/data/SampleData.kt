package com.anthonycastiglia.karoo.powergraph.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

val SAMPLE_INTERVAL = 1.seconds

/** Uniformly random values between [min] and [max], for a preview shaped like power. */
fun randomDoubles(min: Double, max: Double): Flow<Double> {
    require(min < max) { "min ($min) must be less than max ($max)" }
    return flow {
        while (true) {
            emit(Random.nextDouble(min, max))
            delay(SAMPLE_INTERVAL)
        }
    }
}

/** Values drifting by at most [maxStepSize] per sample within [min]..[max], for a preview shaped like heart rate. */
fun randomWalk(start: Double, min: Double, max: Double, maxStepSize: Double): Flow<Double> {
    require(min <= max) { "min ($min) must not exceed max ($max)" }
    require(start in min..max) { "start ($start) must be within $min..$max" }
    require(maxStepSize > 0) { "maxStepSize ($maxStepSize) must be positive" }
    return flow {
        var current = start
        while (true) {
            emit(current)
            delay(SAMPLE_INTERVAL)
            current = (current + Random.nextDouble(-maxStepSize, maxStepSize)).coerceIn(min, max)
        }
    }
}
