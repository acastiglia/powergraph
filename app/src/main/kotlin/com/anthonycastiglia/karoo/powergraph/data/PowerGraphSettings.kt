package com.anthonycastiglia.karoo.powergraph.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.stateIn

/** A statistic shown alongside the current value in the full-size layout. */
enum class Aggregation { MAX, AVERAGE, NORMALIZED }

/**
 * Persisted settings for the data fields, each exposed as a [StateFlow] whose value is always
 * current and updates on every change -- including a change from another instance of this
 * class, such as the settings screen updating a value a running data field reads. The
 * [SharedPreferences] stay the source of truth; the flows are kept live in [scope] and are
 * read-only to callers, who write through the setters.
 *
 * One screen for all fields: power smoothing applies to the power field only, while zone colors and
 * aggregations are set separately for power and heart rate.
 */
class PowerGraphSettings(context: Context, scope: CoroutineScope) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val powerSmoothingSeconds: StateFlow<Int> = prefs.stateFlowOf(KEY_POWER_SMOOTHING_SECONDS, scope) {
        getInt(KEY_POWER_SMOOTHING_SECONDS, DEFAULT_POWER_SMOOTHING_SECONDS)
    }

    val powerZoneColors: StateFlow<Boolean> = prefs.stateFlowOf(KEY_POWER_ZONE_COLORS, scope) {
        getBoolean(KEY_POWER_ZONE_COLORS, DEFAULT_ZONE_COLORS)
    }

    val heartRateZoneColors: StateFlow<Boolean> = prefs.stateFlowOf(KEY_HEART_RATE_ZONE_COLORS, scope) {
        getBoolean(KEY_HEART_RATE_ZONE_COLORS, DEFAULT_ZONE_COLORS)
    }

    val powerAggregations: StateFlow<Set<Aggregation>> = prefs.stateFlowOf(KEY_POWER_AGGREGATIONS, scope) {
        getAggregations(KEY_POWER_AGGREGATIONS)
    }

    val heartRateAggregations: StateFlow<Set<Aggregation>> =
        prefs.stateFlowOf(KEY_HEART_RATE_AGGREGATIONS, scope) { getAggregations(KEY_HEART_RATE_AGGREGATIONS) }

    fun setPowerSmoothingSeconds(seconds: Int) = prefs.edit { putInt(KEY_POWER_SMOOTHING_SECONDS, seconds) }

    fun setPowerZoneColors(enabled: Boolean) = prefs.edit { putBoolean(KEY_POWER_ZONE_COLORS, enabled) }

    fun setHeartRateZoneColors(enabled: Boolean) = prefs.edit { putBoolean(KEY_HEART_RATE_ZONE_COLORS, enabled) }

    fun setPowerAggregations(aggregations: Set<Aggregation>) =
        prefs.putAggregations(KEY_POWER_AGGREGATIONS, aggregations)

    fun setHeartRateAggregations(aggregations: Set<Aggregation>) =
        prefs.putAggregations(KEY_HEART_RATE_AGGREGATIONS, aggregations)

    companion object {
        private const val PREFS_NAME = "powergraph_settings"
        private const val KEY_POWER_SMOOTHING_SECONDS = "power_smoothing_seconds"
        private const val KEY_POWER_ZONE_COLORS = "power_zone_colors"
        private const val KEY_HEART_RATE_ZONE_COLORS = "heart_rate_zone_colors"
        private const val KEY_POWER_AGGREGATIONS = "power_aggregations"
        private const val KEY_HEART_RATE_AGGREGATIONS = "heart_rate_aggregations"

        const val DEFAULT_POWER_SMOOTHING_SECONDS = 3
        const val DEFAULT_ZONE_COLORS = true
        val DEFAULT_AGGREGATIONS = setOf(Aggregation.MAX)
    }
}

/**
 * Wraps a preference key as a [StateFlow] kept live in [scope]: starts at [read] and re-reads
 * whenever [key] changes -- in this process or, since [SharedPreferences] is shared by file
 * name, another one.
 */
private fun <T> SharedPreferences.stateFlowOf(
    key: String,
    scope: CoroutineScope,
    read: SharedPreferences.() -> T,
): StateFlow<T> = changesOf(key, read).stateIn(scope, SharingStarted.Eagerly, read())

private fun <T> SharedPreferences.changesOf(key: String, read: SharedPreferences.() -> T): Flow<T> = callbackFlow {
    val listener = SharedPreferences.OnSharedPreferenceChangeListener { changedPrefs, changedKey ->
        if (changedKey == key) trySend(changedPrefs.read())
    }
    registerOnSharedPreferenceChangeListener(listener)
    send(read())
    awaitClose { unregisterOnSharedPreferenceChangeListener(listener) }
}

private fun SharedPreferences.getAggregations(key: String): Set<Aggregation> =
    getStringSet(key, null)
        ?.mapNotNullTo(mutableSetOf()) { stored -> Aggregation.entries.find { it.name == stored } }
        ?: PowerGraphSettings.DEFAULT_AGGREGATIONS

private fun SharedPreferences.putAggregations(key: String, aggregations: Set<Aggregation>) =
    edit { putStringSet(key, aggregations.mapTo(mutableSetOf()) { it.name }) }
