package com.episode6.meetingminder.ui.day

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.episode6.meetingminder.R
import com.episode6.meetingminder.ui.theme.MeetingMinderTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** What [DayScreen] renders. Events, selections and the FAB state join it in PR-5 / PR-6 / PR-7. */
data class DayUiState(
    val date: LocalDate,
    val isToday: Boolean,
)

private val TitleFormatter = DateTimeFormatter.ofPattern("EEEE, MMM d")

/**
 * The day view shell (render 2): date + subtitle app bar with the Today action and the
 * overflow menu. The timeline itself (PR-5) and the pager (PR-6) replace the empty
 * state in the body.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayScreen(
    state: DayUiState,
    onTodayClick: () -> Unit,
    onPermissionsClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onLicensesClick: () -> Unit,
    onCheckForUpdatesClick: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.date.format(TitleFormatter), style = MaterialTheme.typography.titleLarge)
                        Text(
                            stringResource(R.string.day_subtitle_no_meetings),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onTodayClick, enabled = !state.isToday) {
                        Icon(Icons.Outlined.Today, contentDescription = stringResource(R.string.day_today))
                    }
                    OverflowMenu(
                        onPermissionsClick = onPermissionsClick,
                        onSettingsClick = onSettingsClick,
                        onLicensesClick = onLicensesClick,
                        onCheckForUpdatesClick = onCheckForUpdatesClick,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    Icons.Outlined.CalendarMonth,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.day_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun OverflowMenu(
    onPermissionsClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onLicensesClick: () -> Unit,
    onCheckForUpdatesClick: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }) {
        Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.more_options))
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        listOf(
            R.string.menu_permissions to onPermissionsClick,
            R.string.menu_settings to onSettingsClick,
            R.string.menu_check_for_updates to onCheckForUpdatesClick,
            R.string.menu_licenses to onLicensesClick,
        ).forEach { (label, onClick) ->
            DropdownMenuItem(
                text = { Text(stringResource(label)) },
                onClick = {
                    expanded = false
                    onClick()
                },
            )
        }
    }
}

internal val PreviewDate: LocalDate = LocalDate.of(2026, 9, 14)

@Preview(showBackground = true)
@Composable
private fun DayScreenEmptyPreview() {
    MeetingMinderTheme {
        DayScreen(
            state = DayUiState(date = PreviewDate, isToday = true),
            onTodayClick = {},
            onPermissionsClick = {},
            onSettingsClick = {},
            onLicensesClick = {},
            onCheckForUpdatesClick = {},
        )
    }
}
