package com.m57.hermescontrol.ui.settings.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.applock.PrivateExposedDropdownMenu
import com.m57.hermescontrol.data.config.AutoSwitchSource
import com.m57.hermescontrol.data.config.ConnectionProfile
import com.m57.hermescontrol.ui.settings.SectionCard
import com.m57.hermescontrol.ui.settings.SettingsUiState
import java.text.DateFormat
import java.util.Date
import com.m57.hermescontrol.ui.common.ExpressiveButton as Button
import com.m57.hermescontrol.ui.common.ExpressiveOutlinedButton as OutlinedButton
import com.m57.hermescontrol.ui.common.ExpressiveSwitch as Switch

/**
 * Home LAN / Remote presets plus live auto-switch status. Uses the same
 * [SectionCard], [OutlinedTextField] and expressive controls as the rest of
 * Settings; no new design language is introduced.
 */
@Composable
internal fun AutoSwitchSection(
    state: SettingsUiState,
    onEnabledChange: (Boolean) -> Unit,
    onHomeAddressChange: (String) -> Unit,
    onRemoteAddressChange: (String) -> Unit,
    onHomeProfileChange: (String?) -> Unit,
    onRemoteProfileChange: (String?) -> Unit,
    onSave: () -> Unit,
    onCheckNow: () -> Unit,
) {
    SectionCard {
        Text(
            text = stringResource(R.string.autoswitch_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.autoswitch_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.autoswitch_enable),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.autoswitch_enable_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = state.autoSwitch.enabled,
                onCheckedChange = onEnabledChange,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(modifier = Modifier.height(12.dp))

        AddressField(
            label = stringResource(R.string.autoswitch_home_address),
            hint = stringResource(R.string.autoswitch_home_hint),
            value = state.autoSwitchHomeDraft,
            error = state.autoSwitchHomeError,
            onValueChange = onHomeAddressChange,
        )
        Spacer(modifier = Modifier.height(8.dp))
        AutoSwitchProfileDropdown(
            label = stringResource(R.string.autoswitch_home_profile),
            profiles = state.profiles,
            selectedId = state.autoSwitchHomeProfileId,
            onSelect = onHomeProfileChange,
        )

        Spacer(modifier = Modifier.height(12.dp))

        AddressField(
            label = stringResource(R.string.autoswitch_remote_address),
            hint = stringResource(R.string.autoswitch_remote_hint),
            value = state.autoSwitchRemoteDraft,
            error = state.autoSwitchRemoteError,
            onValueChange = onRemoteAddressChange,
        )
        Spacer(modifier = Modifier.height(8.dp))
        AutoSwitchProfileDropdown(
            label = stringResource(R.string.autoswitch_remote_profile),
            profiles = state.profiles,
            selectedId = state.autoSwitchRemoteProfileId,
            onSelect = onRemoteProfileChange,
        )

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.autoswitch_scheme_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(12.dp))
        AutoSwitchStatusCard(state)

        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onSave,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.autoswitch_save))
            }
            OutlinedButton(
                onClick = onCheckNow,
                enabled = !state.autoSwitchChecking,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.autoswitch_check_now))
            }
        }

        if (state.autoSwitchSaved) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.autoswitch_saved),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.autoswitch_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AddressField(
    label: String,
    hint: String,
    value: String,
    error: String?,
    onValueChange: (String) -> Unit,
) {
    val supportingText: (@Composable () -> Unit)? =
        if (error != null) {
            { Text(text = error, color = MaterialTheme.colorScheme.error) }
        } else {
            null
        }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(hint) },
        supportingText = supportingText,
        singleLine = true,
        isError = error != null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutoSwitchProfileDropdown(
    label: String,
    profiles: List<ConnectionProfile>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = profiles.firstOrNull { it.id == selectedId }?.name
    val display = selectedName ?: stringResource(R.string.autoswitch_profile_auto)

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = display,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            singleLine = true,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        PrivateExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.autoswitch_profile_auto)) },
                onClick = {
                    onSelect(null)
                    expanded = false
                },
            )
            profiles.forEach { profile ->
                DropdownMenuItem(
                    text = { Text(profile.name) },
                    onClick = {
                        onSelect(profile.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun AutoSwitchStatusCard(state: SettingsUiState) {
    val sourceLabel =
        when (state.autoSwitchActiveSource) {
            AutoSwitchSource.HOME -> stringResource(R.string.autoswitch_source_home)
            AutoSwitchSource.REMOTE -> stringResource(R.string.autoswitch_source_remote)
            AutoSwitchSource.MANUAL -> stringResource(R.string.autoswitch_source_manual)
        }
    val status = state.autoSwitchStatus
    Card(
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.autoswitch_status_title),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = stringResource(R.string.autoswitch_status_connection, sourceLabel),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text =
                    if (status.lastCheckAtMs > 0L) {
                        stringResource(R.string.autoswitch_last_check, formatCheckTime(status.lastCheckAtMs))
                    } else {
                        stringResource(R.string.autoswitch_last_check_never)
                    },
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text =
                    status.lastResult?.let {
                        stringResource(R.string.autoswitch_last_result, it)
                    } ?: stringResource(R.string.autoswitch_no_result),
                style = MaterialTheme.typography.bodySmall,
            )
            status.lastError?.let { error ->
                Text(
                    text = stringResource(R.string.autoswitch_error, error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (state.autoSwitchChecking) {
                Text(
                    text = stringResource(R.string.autoswitch_checking),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

private fun formatCheckTime(epochMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epochMs))
