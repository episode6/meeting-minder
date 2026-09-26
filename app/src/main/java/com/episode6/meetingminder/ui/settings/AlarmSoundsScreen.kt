package com.episode6.meetingminder.ui.settings

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.episode6.meetingminder.R
import com.episode6.meetingminder.alarm.AlarmSound
import com.episode6.meetingminder.ui.theme.MeetingMinderTheme

/**
 * Settings → Alarm sounds (TODO.md §4.4): every sound the randomised alert can draw from,
 * in three groups — the device's alarm ringtones, the bundled OGGs and the siren — each
 * with a checkbox per sound and a tri-state checkbox on the group's header that checks or
 * unchecks the whole group at once. The list waits for [AlarmSoundsUiState.loaded] rather
 * than flashing empty groups.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmSoundsScreen(
    state: AlarmSoundsUiState,
    onBackClick: () -> Unit,
    onSoundToggle: (soundId: String, enabled: Boolean) -> Unit,
    onGroupToggle: (AlarmSoundGroup, enabled: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.alarm_sounds_title)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.navigate_back))
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxWidth().padding(padding)) {
            item {
                Text(
                    stringResource(R.string.alarm_sounds_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            if (state.loaded) {
                AlarmSoundGroup.entries.forEachIndexed { index, group ->
                    if (index > 0) item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
                    soundGroup(group, state, onSoundToggle, onGroupToggle)
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

private fun LazyListScope.soundGroup(
    group: AlarmSoundGroup,
    state: AlarmSoundsUiState,
    onSoundToggle: (String, Boolean) -> Unit,
    onGroupToggle: (AlarmSoundGroup, Boolean) -> Unit,
) {
    val choices = state.choices(group)
    item(key = "group:${group.name}") {
        val groupState = groupState(choices)
        GroupHeader(
            title = group.title(),
            subtitle = stringResource(R.string.alarm_sounds_group_count, choices.count { it.enabled }, choices.size),
            state = groupState,
            // a tap on a partly checked group checks the rest, as a "select all" does
            onToggle = { onGroupToggle(group, groupState != ToggleableState.On) },
        )
    }
    items(choices, key = { it.id }) { choice ->
        SoundRow(
            name = if (choice.id == AlarmSound.SIREN_ID) stringResource(R.string.alarm_sound_siren) else choice.name,
            description = if (choice.id == AlarmSound.SIREN_ID) stringResource(R.string.alarm_sounds_siren_description) else null,
            checked = choice.enabled,
            onCheckedChange = { onSoundToggle(choice.id, it) },
        )
    }
}

@Composable
private fun AlarmSoundGroup.title(): String = when (this) {
    AlarmSoundGroup.SYSTEM -> stringResource(R.string.alarm_sounds_group_system)
    AlarmSoundGroup.BUNDLED -> stringResource(R.string.alarm_sounds_group_bundled)
    AlarmSoundGroup.SIREN -> stringResource(R.string.alarm_sounds_group_siren)
}

/** A group's title, its "n of m on" count and the tri-state checkbox; the whole row is the checkbox, so TalkBack reads title, count and state together. */
@Composable
private fun GroupHeader(title: String, subtitle: String, state: ToggleableState, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .triStateToggleable(state = state, role = Role.Checkbox, onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { heading() },
            )
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TriStateCheckbox(state = state, onClick = null)
    }
}

@Composable
private fun SoundRow(name: String, description: String?, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Checkbox(checked = checked, onCheckedChange = null)
    }
}

@Preview(showBackground = true)
@Composable
internal fun AlarmSoundsScreenPreview() {
    MeetingMinderTheme {
        AlarmSoundsScreen(state = previewSounds, onBackClick = {}, onSoundToggle = { _, _ -> }, onGroupToggle = { _, _ -> })
    }
}

/** Dark theme: the orange group titles and checked boxes on the dark scheme. */
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
internal fun AlarmSoundsScreenDarkPreview() {
    MeetingMinderTheme {
        AlarmSoundsScreen(state = previewSounds, onBackClick = {}, onSoundToggle = { _, _ -> }, onGroupToggle = { _, _ -> })
    }
}

/** A device ringtone unchecked (its group indeterminate), the bundled group unchecked whole, the siren left on. */
private val previewSounds = AlarmSoundsUiState(
    loaded = true,
    system = listOf(
        SoundChoice("system:content://media/internal/audio/media/1", "Cesium", enabled = true),
        SoundChoice("system:content://media/internal/audio/media/2", "Krypton", enabled = false),
        SoundChoice("system:content://media/internal/audio/media/3", "Neon", enabled = true),
    ),
    bundled = listOf(
        SoundChoice("bundled:Argon", "Argon", enabled = false),
        SoundChoice("bundled:Carbon", "Carbon", enabled = false),
        SoundChoice("bundled:Fire Drill", "Fire Drill", enabled = false),
    ),
    sirenEnabled = true,
)
