package com.episode6.meetingminder.ui.onboarding

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.episode6.meetingminder.R
import com.episode6.meetingminder.ui.theme.MeetingMinderTheme

/**
 * What [OnboardingScreen] renders: the four required rows (TODO.md §4.5). The
 * battery-optimisation row stubs as "coming soon" until PR-13.
 */
data class OnboardingUiState(
    val calendarGranted: Boolean,
    val notificationsGranted: Boolean = false,
    val exactAlarmsGranted: Boolean = false,
    val fullScreenAlarmsGranted: Boolean = false,
) {
    /** Every required row is granted; the optional rows never gate Continue. */
    val canContinue: Boolean get() = calendarGranted && notificationsGranted && exactAlarmsGranted && fullScreenAlarmsGranted
}

/** The live rows of the checklist, each with its own request flow in `Navigation.kt`. */
enum class OnboardingRow(@param:StringRes internal val title: Int, @param:StringRes internal val description: Int) {
    Calendar(R.string.onboarding_calendar_title, R.string.onboarding_calendar_description),
    Notifications(R.string.onboarding_notifications_title, R.string.onboarding_notifications_description),
    ExactAlarms(R.string.onboarding_alarms_title, R.string.onboarding_alarms_description),
    FullScreenAlarms(R.string.onboarding_full_screen_title, R.string.onboarding_full_screen_description),
}

private data class StubRow(@StringRes val title: Int, @StringRes val description: Int)

private val StubRows = listOf(
    StubRow(R.string.onboarding_battery_title, R.string.onboarding_battery_description),
)

/**
 * The permissions checklist (render 1). Each live row's button is "Allow" (a runtime
 * dialog, or the special-access page for exact alarms), or "Open settings" for the rows
 * in [settingsOnlyRows]: those Android will no longer show a dialog for (two denials),
 * or that only system Settings can change on this OS version (notifications on 12/12L, a
 * silenced channel). Requests are launched from `Navigation.kt`, the wiring layer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    state: OnboardingUiState,
    settingsOnlyRows: Set<OnboardingRow>,
    canNavigateBack: Boolean,
    onAllowClick: (OnboardingRow) -> Unit,
    onOpenSettingsClick: (OnboardingRow) -> Unit,
    onContinueClick: () -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            if (canNavigateBack) {
                TopAppBar(
                    title = {},
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(
                                Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(R.string.navigate_back),
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp)) {
            Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                if (!canNavigateBack) Spacer(Modifier.height(24.dp))
                Text(stringResource(R.string.onboarding_heading), style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.onboarding_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                OnboardingRow.entries.forEach { row ->
                    val granted = state.granted(row)
                    val settingsOnly = row in settingsOnlyRows
                    PermissionRow(
                        title = stringResource(row.title),
                        description = stringResource(row.description),
                        granted = granted,
                        actionLabel = when {
                            granted -> null
                            settingsOnly -> stringResource(R.string.onboarding_open_settings)
                            else -> stringResource(R.string.onboarding_allow)
                        },
                        onAction = when {
                            granted -> null
                            settingsOnly -> ({ onOpenSettingsClick(row) })
                            else -> ({ onAllowClick(row) })
                        },
                    )
                    HorizontalDivider()
                }
                StubRows.forEach { row ->
                    PermissionRow(
                        title = stringResource(row.title),
                        description = stringResource(row.description),
                        granted = false,
                        actionLabel = stringResource(R.string.onboarding_coming_soon),
                        onAction = null,
                    )
                    HorizontalDivider()
                }
            }
            Button(
                onClick = onContinueClick,
                enabled = state.canContinue,
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            ) {
                Text(stringResource(R.string.onboarding_continue))
            }
        }
    }
}

private fun OnboardingUiState.granted(row: OnboardingRow): Boolean = when (row) {
    OnboardingRow.Calendar -> calendarGranted
    OnboardingRow.Notifications -> notificationsGranted
    OnboardingRow.ExactAlarms -> exactAlarmsGranted
    OnboardingRow.FullScreenAlarms -> fullScreenAlarmsGranted
}

@Composable
private fun PermissionRow(
    title: String,
    description: String,
    granted: Boolean,
    actionLabel: String?,
    onAction: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PermissionStatusIcon(granted)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when {
            granted -> Text(
                stringResource(R.string.onboarding_granted),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            onAction != null && actionLabel != null -> Button(onClick = onAction) { Text(actionLabel) }
            actionLabel != null -> Text(
                actionLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PermissionStatusIcon(granted: Boolean) {
    val containerColor = if (granted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    Box(
        modifier = Modifier.size(40.dp).clip(CircleShape).background(containerColor),
        contentAlignment = Alignment.Center,
    ) {
        if (granted) {
            Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

/** First launch: nothing granted, every live row offers "Allow". */
@Preview(showBackground = true)
@Composable
internal fun OnboardingScreenStartPreview() {
    MeetingMinderTheme {
        OnboardingScreen(
            state = OnboardingUiState(calendarGranted = false),
            settingsOnlyRows = emptySet(),
            canNavigateBack = false,
            onAllowClick = {},
            onOpenSettingsClick = {},
            onContinueClick = {},
            onBackClick = {},
        )
    }
}

/** Reached from the overflow menu with every required row granted: Continue enabled. */
@Preview(showBackground = true)
@Composable
internal fun OnboardingScreenGrantedPreview() {
    MeetingMinderTheme {
        OnboardingScreen(
            state = OnboardingUiState(
                calendarGranted = true,
                notificationsGranted = true,
                exactAlarmsGranted = true,
                fullScreenAlarmsGranted = true,
            ),
            settingsOnlyRows = emptySet(),
            canNavigateBack = true,
            onAllowClick = {},
            onOpenSettingsClick = {},
            onContinueClick = {},
            onBackClick = {},
        )
    }
}

/** Render 1's mid-way state: calendar and notifications granted, exact and full-screen alarms still to allow. */
@Preview(showBackground = true)
@Composable
internal fun OnboardingScreenPartlyGrantedPreview() {
    MeetingMinderTheme {
        OnboardingScreen(
            state = OnboardingUiState(calendarGranted = true, notificationsGranted = true, exactAlarmsGranted = false),
            settingsOnlyRows = emptySet(),
            canNavigateBack = false,
            onAllowClick = {},
            onOpenSettingsClick = {},
            onContinueClick = {},
            onBackClick = {},
        )
    }
}

/** Calendar denied twice: its button is "Open settings"; notifications on 12/12L is settings-only too. */
@Preview(showBackground = true)
@Composable
internal fun OnboardingScreenPermanentlyDeniedPreview() {
    MeetingMinderTheme {
        OnboardingScreen(
            state = OnboardingUiState(calendarGranted = false),
            settingsOnlyRows = setOf(OnboardingRow.Calendar, OnboardingRow.Notifications),
            canNavigateBack = false,
            onAllowClick = {},
            onOpenSettingsClick = {},
            onContinueClick = {},
            onBackClick = {},
        )
    }
}
