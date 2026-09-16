package com.episode6.meetingminder.ui.settings

import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.role
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.episode6.meetingminder.R
import com.episode6.meetingminder.data.settings.AlarmSoundPool
import com.episode6.meetingminder.model.CalendarInfo
import com.episode6.meetingminder.ui.theme.MeetingMinderTheme
import java.time.Duration

/** The `Duration` options each picker in [SettingsScreen] offers (TODO.md §4.4/§5 PR-12). */
internal object SettingsOptions {
    val LeadTimeMinutes = listOf(1L, 5L, 10L, 15L, 30L)
    val SnoozeMinutes = listOf(1L, 2L, 5L, 10L)
    val AutoTimeoutMinutes = listOf(1L, 3L, 5L, 10L)
}

/**
 * Settings (TODO.md §5 PR-12; no render): lead time / snooze / auto-timeout / sound pack
 * as chip pickers, a "Test alarm" button, the "show declined events" toggle, the
 * Settings → Calendars list with a per-calendar include switch (and a "not syncing"
 * hint), and re-entry points into Permissions and Licenses.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    snackbarHostState: SnackbarHostState,
    onBackClick: () -> Unit,
    onLeadTimeSelected: (Duration) -> Unit,
    onSnoozeLengthSelected: (Duration) -> Unit,
    onAutoTimeoutSelected: (Duration) -> Unit,
    onSoundPoolSelected: (AlarmSoundPool) -> Unit,
    onTestAlarmClick: () -> Unit,
    onCalendarToggle: (CalendarInfo, Boolean) -> Unit,
    onShowDeclinedToggle: (Boolean) -> Unit,
    onBusySyncToggle: (Boolean) -> Unit,
    onBusyCalendarSelected: (CalendarInfo) -> Unit,
    onPermissionsClick: () -> Unit,
    onLicensesClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.navigate_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxWidth().padding(padding)) {
            section(R.string.settings_section_alarms)
            item {
                DurationPickerRow(
                    title = stringResource(R.string.settings_lead_time),
                    options = SettingsOptions.LeadTimeMinutes,
                    selectedMinutes = state.leadTime.toMinutes(),
                    onSelected = onLeadTimeSelected,
                )
            }
            item {
                DurationPickerRow(
                    title = stringResource(R.string.settings_snooze_length),
                    options = SettingsOptions.SnoozeMinutes,
                    selectedMinutes = state.snoozeLength.toMinutes(),
                    onSelected = onSnoozeLengthSelected,
                )
            }
            item {
                DurationPickerRow(
                    title = stringResource(R.string.settings_auto_timeout),
                    options = SettingsOptions.AutoTimeoutMinutes,
                    selectedMinutes = state.autoTimeout.toMinutes(),
                    onSelected = onAutoTimeoutSelected,
                )
            }
            item { SoundPoolRow(selected = state.soundPool, onSelected = onSoundPoolSelected) }
            item {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    OutlinedButton(onClick = onTestAlarmClick) { Text(stringResource(R.string.settings_test_alarm)) }
                }
            }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }

            section(R.string.settings_section_display)
            item {
                ToggleRow(
                    title = stringResource(R.string.settings_show_declined),
                    description = stringResource(R.string.settings_show_declined_description),
                    checked = state.showDeclined,
                    onCheckedChange = onShowDeclinedToggle,
                )
            }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }

            section(R.string.settings_section_calendars)
            items(state.calendars, key = { it.info.id }) { row ->
                CalendarRowItem(row = row, onToggle = { included -> onCalendarToggle(row.info, included) })
            }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }

            section(R.string.settings_section_busy_calendar)
            item {
                ToggleRow(
                    title = stringResource(R.string.settings_busy_sync_title),
                    description = if (state.writableCalendars.isEmpty()) {
                        stringResource(R.string.settings_busy_sync_no_writable)
                    } else {
                        stringResource(R.string.settings_busy_sync_description)
                    },
                    checked = state.busySyncEnabled,
                    enabled = state.writableCalendars.isNotEmpty(),
                    onCheckedChange = onBusySyncToggle,
                )
            }
            if (state.busySyncEnabled) {
                items(state.writableCalendars, key = { it.id }) { calendar ->
                    BusyCalendarRow(
                        calendar = calendar,
                        selected = calendar.id == state.busySyncCalendarId,
                        onSelected = { onBusyCalendarSelected(calendar) },
                    )
                }
                if (state.writableCalendars.none { it.id == state.busySyncCalendarId }) {
                    item {
                        Text(
                            stringResource(R.string.settings_busy_sync_pick),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
            }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }

            section(R.string.settings_section_about)
            item {
                SimpleRow(
                    title = stringResource(R.string.menu_permissions),
                    subtitle = state.permissionsStatus.label(),
                    onClick = onPermissionsClick,
                )
            }
            item { SimpleRow(title = stringResource(R.string.menu_licenses), onClick = onLicensesClick) }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

private fun LazyListScope.section(@StringRes title: Int) {
    item {
        Text(
            stringResource(title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).semantics { heading() },
        )
    }
}

@Composable
private fun DurationPickerRow(title: String, options: List<Long>, selectedMinutes: Long, onSelected: (Duration) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(8.dp))
        FlowRow(
            modifier = Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { minutes ->
                FilterChip(
                    selected = minutes == selectedMinutes,
                    onClick = { onSelected(Duration.ofMinutes(minutes)) },
                    label = { Text(stringResource(R.string.settings_minutes_value, minutes)) },
                    colors = SettingsChipColors(),
                    modifier = Modifier.singleChoiceChip(),
                )
            }
        }
    }
}

@Composable
private fun SoundPoolRow(selected: AlarmSoundPool, onSelected: (AlarmSoundPool) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(stringResource(R.string.settings_sound_pool), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(8.dp))
        FlowRow(
            modifier = Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AlarmSoundPool.entries.forEach { pool ->
                FilterChip(
                    selected = pool == selected,
                    onClick = { onSelected(pool) },
                    label = { Text(pool.label()) },
                    colors = SettingsChipColors(),
                    modifier = Modifier.singleChoiceChip(),
                )
            }
        }
    }
}

/**
 * The chips in a [selectableGroup] row are radio buttons in all but widget: exactly one is
 * ever selected. `FilterChip` reports `Role.Checkbox`, which TalkBack would read as
 * independent toggles; the outer semantics win, so this overrides the role.
 */
private fun Modifier.singleChoiceChip(): Modifier = semantics { role = Role.RadioButton }

/**
 * Selected chips use episode6 orange (`primaryContainer`), not M3's default lavender
 * `secondaryContainer` — `MeetingMinderTheme` defines no `secondaryContainer`, and orange
 * is reserved for chrome and selected states of non-calendar controls (AGENTS.md).
 */
@Composable
private fun SettingsChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
)

@Composable
private fun AlarmSoundPool.label(): String = when (this) {
    AlarmSoundPool.ALL -> stringResource(R.string.settings_sound_pool_all)
    AlarmSoundPool.BUNDLED_ONLY -> stringResource(R.string.settings_sound_pool_bundled)
    AlarmSoundPool.SYSTEM_ONLY -> stringResource(R.string.settings_sound_pool_system)
}

@Composable
private fun ToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        // the whole row is the switch, so TalkBack reads its title with its state
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, enabled = enabled, onValueChange = onCheckedChange)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/**
 * One writable calendar in the "Busy calendar" radio list (TODO.md §4.7): name, account
 * email and the calendar's own colour dot, exactly like [CalendarRowItem] but a single-choice
 * radio row like [SoundPoolRow]'s chips rather than an independent switch.
 */
@Composable
private fun BusyCalendarRow(calendar: CalendarInfo, selected: Boolean, onSelected: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelected)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(modifier = Modifier.size(12.dp).clip(RoundedCornerShape(50)).background(Color(calendar.color)))
        Column(modifier = Modifier.weight(1f)) {
            Text(calendar.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(calendar.accountName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        RadioButton(selected = selected, onClick = null)
    }
}

@Composable
private fun CalendarRowItem(row: CalendarRow, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = row.included, role = Role.Switch, onValueChange = onToggle)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(modifier = Modifier.size(12.dp).clip(RoundedCornerShape(50)).background(Color(row.info.color)))
        Column(modifier = Modifier.weight(1f)) {
            Text(row.info.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val subtitle = if (row.info.syncEvents) {
                row.info.accountName
            } else {
                stringResource(R.string.settings_calendar_not_syncing, row.info.accountName)
            }
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = row.included, onCheckedChange = null)
    }
}

@Composable
private fun SimpleRow(title: String, onClick: () -> Unit, subtitle: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** "Permissions" row subtitle (TODO.md §5 PR-12: "permissions status re-entry to onboarding"). */
@Composable
private fun PermissionsStatus.label(): String = when (this) {
    PermissionsStatus.AllGranted -> stringResource(R.string.settings_permissions_all_granted)
    PermissionsStatus.BackgroundRestricted -> stringResource(R.string.settings_permissions_background_restricted)
    is PermissionsStatus.MissingSome -> pluralStringResource(R.plurals.settings_permissions_missing, count, count)
}

@Preview(showBackground = true)
@Composable
internal fun SettingsScreenPreview() {
    MeetingMinderTheme {
        SettingsScreen(
            state = previewState,
            snackbarHostState = SnackbarHostState(),
            onBackClick = {},
            onLeadTimeSelected = {},
            onSnoozeLengthSelected = {},
            onAutoTimeoutSelected = {},
            onSoundPoolSelected = {},
            onTestAlarmClick = {},
            onCalendarToggle = { _, _ -> },
            onShowDeclinedToggle = {},
            onBusySyncToggle = {},
            onBusyCalendarSelected = {},
            onPermissionsClick = {},
            onLicensesClick = {},
        )
    }
}

/** Dark theme: the orange selected chips, switches, section titles and the calendar dots on the dark scheme. */
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
internal fun SettingsScreenDarkPreview() {
    MeetingMinderTheme {
        SettingsScreen(
            state = previewState.copy(permissionsStatus = PermissionsStatus.BackgroundRestricted),
            snackbarHostState = SnackbarHostState(),
            onBackClick = {},
            onLeadTimeSelected = {},
            onSnoozeLengthSelected = {},
            onAutoTimeoutSelected = {},
            onSoundPoolSelected = {},
            onTestAlarmClick = {},
            onCalendarToggle = { _, _ -> },
            onShowDeclinedToggle = {},
            onBusySyncToggle = {},
            onBusyCalendarSelected = {},
            onPermissionsClick = {},
            onLicensesClick = {},
        )
    }
}

@Preview(showBackground = true, fontScale = 1.5f)
@Composable
internal fun SettingsScreenLargeFontPreview() {
    MeetingMinderTheme {
        SettingsScreen(
            state = previewState,
            snackbarHostState = SnackbarHostState(),
            onBackClick = {},
            onLeadTimeSelected = {},
            onSnoozeLengthSelected = {},
            onAutoTimeoutSelected = {},
            onSoundPoolSelected = {},
            onTestAlarmClick = {},
            onCalendarToggle = { _, _ -> },
            onShowDeclinedToggle = {},
            onBusySyncToggle = {},
            onBusyCalendarSelected = {},
            onPermissionsClick = {},
            onLicensesClick = {},
        )
    }
}

/** Toggle on, Family selected (TODO.md §4.7): the radio list under the toggle in render. */
@Preview(showBackground = true)
@Composable
internal fun SettingsScreenBusySyncOnPreview() {
    MeetingMinderTheme {
        SettingsScreen(
            state = previewState.copy(
                busySyncEnabled = true,
                busySyncCalendarId = previewFamily.id,
                writableCalendars = listOf(previewWork, previewFamily),
            ),
            snackbarHostState = SnackbarHostState(),
            onBackClick = {},
            onLeadTimeSelected = {},
            onSnoozeLengthSelected = {},
            onAutoTimeoutSelected = {},
            onSoundPoolSelected = {},
            onTestAlarmClick = {},
            onCalendarToggle = { _, _ -> },
            onShowDeclinedToggle = {},
            onBusySyncToggle = {},
            onBusyCalendarSelected = {},
            onPermissionsClick = {},
            onLicensesClick = {},
        )
    }
}

private val previewWork = CalendarInfo(
    id = 1,
    accountName = "me@work.com",
    accountType = "com.google",
    displayName = "Work",
    color = 0xFF4285F4.toInt(),
    visible = true,
    syncEvents = true,
    ownerAccount = "me@work.com",
    isPrimary = true,
    accessLevel = 700,
    canOrganizerRespond = false,
)

private val previewFamily = CalendarInfo(
    id = 2,
    accountName = "me@personal.com",
    accountType = "com.google",
    displayName = "Family",
    color = 0xFF0B8043.toInt(),
    visible = false,
    syncEvents = false,
    ownerAccount = "family@group.calendar.google.com",
    isPrimary = false,
    accessLevel = 700,
    canOrganizerRespond = false,
)

private val previewState = SettingsUiState(
    leadTime = Duration.ofMinutes(5),
    snoozeLength = Duration.ofMinutes(2),
    autoTimeout = Duration.ofMinutes(3),
    soundPool = AlarmSoundPool.ALL,
    showDeclined = true,
    calendars = listOf(CalendarRow(previewWork, included = true), CalendarRow(previewFamily, included = false)),
    permissionsStatus = PermissionsStatus.AllGranted,
)
