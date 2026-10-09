package com.anthonycastiglia.karoo.powergraph.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.stateIn

/** A statistic shown alongside the current value in the full-size layout. */
enum class Aggregation { MAX, AVERAGE, NORMALIZED }

/**
 * Persisted settings for the data fields, one [FieldSettings] per [GRAPH_FIELDS] entry, all
 * built up front. The [SharedPreferences] stay the source of truth; the flows are kept live in
 * [scope].
 */
class PowerGraphSettings(context: Context, scope: CoroutineScope) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val byField = GRAPH_FIELDS.associate { it.settingsKey to FieldSettings(prefs, it, scope) }

    init {
        require(byField.size == GRAPH_FIELDS.size) { "Every GraphField needs its own settingsKey" }
    }

    /** [field]'s settings; [field] must be one of [GRAPH_FIELDS]. */
    fun forField(field: GraphField): FieldSettings = byField.getValue(field.settingsKey)

    private companion object {
        const val PREFS_NAME = "powergraph_settings"
    }
}

/**
 * One [field]'s settings, each exposed as a [StateFlow] whose value is always current and
 * updates on every change -- including a change from another instance in this process, such as
 * the settings screen updating a value a running data field reads. The flows are read-only;
 * callers write through the setters. Keys are prefixed with [GraphField.settingsKey].
 *
 * [smoothingSeconds] stays zero for a field without [GraphField.smoothing].
 */
class FieldSettings internal constructor(
    private val prefs: SharedPreferences,
    field: GraphField,
    scope: CoroutineScope,
) {
    private val smoothingKey = "${field.settingsKey}_smoothing_seconds"
    private val zoneColorsKey = "${field.settingsKey}_zone_colors"
    private val aggregationsKey = "${field.settingsKey}_aggregations"

    val smoothingSeconds: StateFlow<Int> = field.smoothing?.let { options ->
        prefs.stateFlowOf(smoothingKey, scope) { getInt(smoothingKey, options.defaultSeconds) }
    } ?: MutableStateFlow(0)

    val zoneColors: StateFlow<Boolean> = prefs.stateFlowOf(zoneColorsKey, scope) {
        getBoolean(zoneColorsKey, DEFAULT_ZONE_COLORS)
    }

    val aggregations: StateFlow<Set<Aggregation>> = prefs.stateFlowOf(aggregationsKey, scope) {
        getStringSet(aggregationsKey, null)
            ?.mapNotNullTo(mutableSetOf()) { stored -> Aggregation.entries.find { it.name == stored } }
            ?: DEFAULT_AGGREGATIONS
    }

    fun setSmoothingSeconds(seconds: Int) = prefs.edit { putInt(smoothingKey, seconds) }

    fun setZoneColors(enabled: Boolean) = prefs.edit { putBoolean(zoneColorsKey, enabled) }

    fun setAggregations(aggregations: Set<Aggregation>) =
        prefs.edit { putStringSet(aggregationsKey, aggregations.mapTo(mutableSetOf()) { it.name }) }

    private companion object {
        const val DEFAULT_ZONE_COLORS = true
        val DEFAULT_AGGREGATIONS = setOf(Aggregation.MAX)
    }
}

/**
 * Wraps a preference key as a [StateFlow] kept live in [scope]: starts at [read] and re-reads
 * whenever [key] changes, including through another [SharedPreferences] instance in this
 * process, such as the settings screen writing while the extension reads.
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
