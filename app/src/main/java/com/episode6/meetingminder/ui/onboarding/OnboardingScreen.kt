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
import androidx.compose.foundation.shape.CircleShape
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

/** What [OnboardingScreen] renders; the rest of [PermissionState][com.episode6.meetingminder.permissions.PermissionState] stubs as "coming soon" until PR-8/8b/10/13. */
data class OnboardingUiState(
    val calendarGranted: Boolean,
) {
    /** Only the calendar row is wired up yet, so it's also the only thing gating Continue. */
    val canContinue: Boolean get() = calendarGranted
}

private data class StubRow(@StringRes val title: Int, @StringRes val description: Int)

private val StubRows = listOf(
    StubRow(R.string.onboarding_notifications_title, R.string.onboarding_notifications_description),
    StubRow(R.string.onboarding_alarms_title, R.string.onboarding_alarms_description),
    StubRow(R.string.onboarding_full_screen_title, R.string.onboarding_full_screen_description),
    StubRow(R.string.onboarding_battery_title, R.string.onboarding_battery_description),
)

/**
 * The permissions checklist (render 1). Only the calendar row is live in PR-4: it drives
 * the actual runtime-permission request (launched from `Navigation.kt`, the wiring
 * layer) and switches to "Open settings" once Android stops showing the dialog after two
 * denials. The remaining rows are stubbed "coming soon" until the PRs that add their
 * permissions (PR-8, PR-8b, PR-10, PR-13) make them live too.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    state: OnboardingUiState,
    calendarPermanentlyDenied: Boolean,
    canNavigateBack: Boolean,
    onAllowCalendarClick: () -> Unit,
    onOpenSettingsClick: () -> Unit,
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
            PermissionRow(
                title = stringResource(R.string.onboarding_calendar_title),
                description = stringResource(R.string.onboarding_calendar_description),
                granted = state.calendarGranted,
                actionLabel = when {
                    state.calendarGranted -> null
                    calendarPermanentlyDenied -> stringResource(R.string.onboarding_open_settings)
                    else -> stringResource(R.string.onboarding_allow)
                },
                onAction = when {
                    state.calendarGranted -> null
                    calendarPermanentlyDenied -> onOpenSettingsClick
                    else -> onAllowCalendarClick
                },
            )
            HorizontalDivider()
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
            Spacer(Modifier.weight(1f))
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

@Preview(showBackground = true)
@Composable
internal fun OnboardingScreenStartPreview() {
    MeetingMinderTheme {
        OnboardingScreen(
            state = OnboardingUiState(calendarGranted = false),
            calendarPermanentlyDenied = false,
            canNavigateBack = false,
            onAllowCalendarClick = {},
            onOpenSettingsClick = {},
            onContinueClick = {},
            onBackClick = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
internal fun OnboardingScreenGrantedPreview() {
    MeetingMinderTheme {
        OnboardingScreen(
            state = OnboardingUiState(calendarGranted = true),
            calendarPermanentlyDenied = false,
            canNavigateBack = true,
            onAllowCalendarClick = {},
            onOpenSettingsClick = {},
            onContinueClick = {},
            onBackClick = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
internal fun OnboardingScreenPermanentlyDeniedPreview() {
    MeetingMinderTheme {
        OnboardingScreen(
            state = OnboardingUiState(calendarGranted = false),
            calendarPermanentlyDenied = true,
            canNavigateBack = false,
            onAllowCalendarClick = {},
            onOpenSettingsClick = {},
            onContinueClick = {},
            onBackClick = {},
        )
    }
}
