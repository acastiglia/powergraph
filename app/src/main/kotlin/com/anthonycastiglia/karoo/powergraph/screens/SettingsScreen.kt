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
import com.anthonycastiglia.karoo.powergraph.data.PowerGraphSettings
import com.anthonycastiglia.karoo.powergraph.theme.AppTheme

private val POWER_SMOOTHING_SECONDS = listOf(1, 3, 10, 30)

private val AGGREGATION_SETTING_LABEL_IDS = mapOf(
    Aggregation.MAX to R.string.aggregation_max,
    Aggregation.AVERAGE to R.string.aggregation_average,
    Aggregation.NORMALIZED to R.string.aggregation_normalized,
)

/**
 * Settings for the data fields, read from and written straight through to [PowerGraphSettings]:
 * there's no separate screen-local state to keep in sync, and nothing to lose on rotation.
 */
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { PowerGraphSettings(context, scope) }
    val powerSmoothingSeconds by settings.powerSmoothingSeconds.collectAsState()
    val powerAggregations by settings.powerAggregations.collectAsState()
    val heartRateAggregations by settings.heartRateAggregations.collectAsState()
    val powerZoneColors by settings.powerZoneColors.collectAsState()
    val heartRateZoneColors by settings.heartRateZoneColors.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        SettingsSection(
            title = R.string.setting_power_smoothing,
            description = R.string.setting_power_smoothing_description,
        ) {
            POWER_SMOOTHING_SECONDS.forEach { seconds ->
                ChoiceRow(
                    label = stringResource(R.string.seconds_format, seconds),
                    selected = seconds == powerSmoothingSeconds,
                    onSelect = { settings.setPowerSmoothingSeconds(seconds) },
                )
            }
        }

        SettingsSection(
            title = R.string.setting_aggregations,
            description = R.string.setting_aggregations_description,
        ) {
            AggregationChoices(
                fieldLabel = R.string.field_power,
                options = Aggregation.entries,
                selected = powerAggregations,
                onChange = settings::setPowerAggregations,
            )
            AggregationChoices(
                fieldLabel = R.string.field_heart_rate,
                options = Aggregation.entries - Aggregation.NORMALIZED,
                selected = heartRateAggregations,
                onChange = settings::setHeartRateAggregations,
            )
        }

        SettingsSection(
            title = R.string.setting_zone_colors,
            description = R.string.setting_zone_colors_description,
        ) {
            SwitchRow(
                label = stringResource(R.string.field_power),
                checked = powerZoneColors,
                onCheckedChange = settings::setPowerZoneColors,
            )
            SwitchRow(
                label = stringResource(R.string.field_heart_rate),
                checked = heartRateZoneColors,
                onCheckedChange = settings::setHeartRateZoneColors,
            )
        }
    }
}

/** A field's labeled group of checkboxes for the [options] it has, reporting the new set to [onChange]. */
@Composable
private fun AggregationChoices(
    @StringRes fieldLabel: Int,
    options: List<Aggregation>,
    selected: Set<Aggregation>,
    onChange: (Set<Aggregation>) -> Unit,
) {
    Text(
        text = stringResource(fieldLabel),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onBackground,
    )
    options.forEach { aggregation ->
        CheckRow(
            label = stringResource(AGGREGATION_SETTING_LABEL_IDS.getValue(aggregation)),
            checked = aggregation in selected,
            onCheckedChange = { checked -> onChange(if (checked) selected + aggregation else selected - aggregation) },
        )
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
