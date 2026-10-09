package com.anthonycastiglia.karoo.powergraph.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.anthonycastiglia.karoo.powergraph.R
import com.anthonycastiglia.karoo.powergraph.data.Aggregation
import com.anthonycastiglia.karoo.powergraph.data.FieldSettings
import com.anthonycastiglia.karoo.powergraph.data.GRAPH_FIELDS
import com.anthonycastiglia.karoo.powergraph.data.GraphField
import com.anthonycastiglia.karoo.powergraph.data.PowerGraphSettings
import com.anthonycastiglia.karoo.powergraph.theme.AppTheme

private val AGGREGATION_SETTING_LABEL_IDS = mapOf(
    Aggregation.MAX to R.string.aggregation_max,
    Aggregation.AVERAGE to R.string.aggregation_average,
    Aggregation.NORMALIZED to R.string.aggregation_normalized,
)

/**
 * Settings for the data fields, one section per [GRAPH_FIELDS] entry, read from and written
 * straight through to [PowerGraphSettings]: there's no separate screen-local state to keep in
 * sync, and nothing to lose on rotation.
 */
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { PowerGraphSettings(context, scope) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        GRAPH_FIELDS.forEach { field -> FieldSection(field, settings.forField(field)) }
    }
}

/**
 * [field]'s settings under its name: each setting appears only where the field has it --
 * smoothing with [GraphField.smoothing], aggregations with [GraphField.aggregationDataTypes],
 * zone colors with [GraphField.zones]. Aggregations are listed in [Aggregation] order, the order
 * the field stacks them in.
 */
@Composable
private fun FieldSection(field: GraphField, settings: FieldSettings) {
    val smoothingSeconds by settings.smoothingSeconds.collectAsState()
    val aggregations by settings.aggregations.collectAsState()
    val zoneColors by settings.zoneColors.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = stringResource(field.label),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )

        field.smoothing?.let { smoothing ->
            SettingsSection(
                title = R.string.setting_smoothing,
                description = R.string.setting_smoothing_description,
            ) {
                smoothing.seconds.forEach { seconds ->
                    ChoiceRow(
                        label = stringResource(R.string.seconds_format, seconds),
                        selected = seconds == smoothingSeconds,
                        onSelect = { settings.setSmoothingSeconds(seconds) },
                    )
                }
            }
        }

        if (field.aggregationDataTypes.isNotEmpty()) {
            SettingsSection(
                title = R.string.setting_aggregations,
                description = R.string.setting_aggregations_description,
            ) {
                Aggregation.entries.filter { it in field.aggregationDataTypes }.forEach { aggregation ->
                    CheckRow(
                        label = stringResource(AGGREGATION_SETTING_LABEL_IDS.getValue(aggregation)),
                        checked = aggregation in aggregations,
                        onCheckedChange = { checked ->
                            settings.setAggregations(if (checked) aggregations + aggregation else aggregations - aggregation)
                        },
                    )
                }
            }
        }

        if (field.zones != null) {
            SettingsSection(
                title = R.string.setting_zone_colors,
                description = R.string.setting_zone_colors_description,
            ) {
                SwitchRow(
                    label = stringResource(R.string.zone_colors_enabled),
                    checked = zoneColors,
                    onCheckedChange = settings::setZoneColors,
                )
            }
        }
    }
}

/** A titled group of controls, with an optional [description] under the title explaining the setting. */
@Composable
private fun SettingsSection(
    @StringRes title: Int,
    @StringRes description: Int? = null,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            description?.let {
                Text(
                    text = stringResource(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        content()
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(text = label, modifier = Modifier.padding(start = 12.dp), color = MaterialTheme.colorScheme.onBackground)
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, onValueChange = onCheckedChange, role = Role.Checkbox),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(text = label, modifier = Modifier.padding(start = 12.dp), color = MaterialTheme.colorScheme.onBackground)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, onValueChange = onCheckedChange, role = Role.Switch),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, color = MaterialTheme.colorScheme.onBackground)
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Preview(
    widthDp = 256,
    heightDp = 426,
)
@Composable
fun DefaultPreview() {
    AppTheme {
        SettingsScreen()
    }
}
