package com.episode6.meetingminder.ui.onboarding

import android.content.res.Configuration
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.episode6.meetingminder.R
import com.episode6.meetingminder.permissions.SleepyManufacturer
import com.episode6.meetingminder.ui.theme.MeetingMinderTheme

/**
 * What [OnboardingScreen] renders (TODO.md §4.5): the four required rows, the optional
 * battery-optimisation row while it's still worth asking for, a warning row while Android
 * restricts the app in the background, and the phone-maker card on devices known to put
 * apps to sleep.
 */
data class OnboardingUiState(
    val calendarGranted: Boolean,
    val notificationsGranted: Boolean = false,
    val exactAlarmsGranted: Boolean = false,
    val fullScreenAlarmsGranted: Boolean = false,
    /** The optional "Ignore battery optimization" row is only shown while this is false. */
    val batteryOptimizationIgnored: Boolean = false,
    /** Shows the "Background use restricted" warning row. */
    val backgroundRestricted: Boolean = false,
    /** Shows the instructions card for this phone maker (row 6 of §4.5); null on other phones. */
    val sleepyManufacturer: SleepyManufacturer? = null,
) {
    /** Every required row is granted; the optional rows never gate Continue. */
    val canContinue: Boolean get() = calendarGranted && notificationsGranted && exactAlarmsGranted && fullScreenAlarmsGranted

    /** The rows to show, in order: every required row, then each optional row while it has something to ask. */
    val rows: List<OnboardingRow>
        get() = OnboardingRow.entries.filter { row ->
            when (row) {
                OnboardingRow.BatteryOptimization -> !batteryOptimizationIgnored
                OnboardingRow.BackgroundRestricted -> backgroundRestricted
                else -> true
            }
        }

    fun granted(row: OnboardingRow): Boolean = when (row) {
        OnboardingRow.Calendar -> calendarGranted
        OnboardingRow.Notifications -> notificationsGranted
        OnboardingRow.ExactAlarms -> exactAlarmsGranted
        OnboardingRow.FullScreenAlarms -> fullScreenAlarmsGranted
        OnboardingRow.BatteryOptimization -> batteryOptimizationIgnored
        OnboardingRow.BackgroundRestricted -> !backgroundRestricted
    }
}

/**
 * The rows of the checklist, each with its own request flow in `Navigation.kt`.
 * [warning] rows aren't a grant to ask for but a problem to fix in system Settings, so
 * they always offer "Open settings".
 */
enum class OnboardingRow(
    @param:StringRes internal val title: Int,
    @param:StringRes internal val description: Int,
    internal val warning: Boolean = false,
) {
    Calendar(R.string.onboarding_calendar_title, R.string.onboarding_calendar_description),
    Notifications(R.string.onboarding_notifications_title, R.string.onboarding_notifications_description),
    ExactAlarms(R.string.onboarding_alarms_title, R.string.onboarding_alarms_description),
    FullScreenAlarms(R.string.onboarding_full_screen_title, R.string.onboarding_full_screen_description),
    BatteryOptimization(R.string.onboarding_battery_title, R.string.onboarding_battery_description),
    BackgroundRestricted(R.string.onboarding_restricted_title, R.string.onboarding_restricted_description, warning = true),
}

/**
 * The permissions checklist (render 1). Each row's button is "Allow" (a runtime dialog, or
 * the special-access page), or "Open settings" for the rows in [settingsOnlyRows] — those
 * Android will no longer show a dialog for (two denials), or that only system Settings can
 * change on this OS version (notifications on 12/12L, a silenced channel) — and for warning
 * rows. Requests are launched from `Navigation.kt`, the wiring layer; so is the phone-maker
 * guide ([onManufacturerGuideClick]), which opens in the browser.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    state: OnboardingUiState,
    settingsOnlyRows: Set<OnboardingRow>,
    canNavigateBack: Boolean,
    onAllowClick: (OnboardingRow) -> Unit,
    onOpenSettingsClick: (OnboardingRow) -> Unit,
    onManufacturerGuideClick: (SleepyManufacturer) -> Unit,
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
                Text(
                    stringResource(R.string.onboarding_heading),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.onboarding_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                state.rows.forEach { row ->
                    val granted = state.granted(row)
                    val settingsOnly = row.warning || row in settingsOnlyRows
                    PermissionRow(
                        title = stringResource(row.title),
                        description = stringResource(row.description),
                        granted = granted,
                        warning = row.warning,
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
                state.sleepyManufacturer?.let { manufacturer ->
                    ManufacturerCard(manufacturer, onGuideClick = { onManufacturerGuideClick(manufacturer) })
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

@Composable
private fun PermissionRow(
    title: String,
    description: String,
    granted: Boolean,
    warning: Boolean,
    actionLabel: String?,
    onAction: (() -> Unit)?,
) {
    Row(
        // one TalkBack stop for the icon, title, description and "Granted"; the button stays its own
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PermissionStatusIcon(granted, warning)
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
        }
    }
}

@Composable
private fun PermissionStatusIcon(granted: Boolean, warning: Boolean) {
    val containerColor = when {
        granted -> MaterialTheme.colorScheme.primaryContainer
        warning -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Box(
        modifier = Modifier.size(40.dp).clip(CircleShape).background(containerColor),
        contentAlignment = Alignment.Center,
    ) {
        when {
            granted -> Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            warning -> Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.error)
        }
    }
}

/** Row 6 of TODO.md §4.5: nothing to check or request, just how to keep the maker's battery manager away. */
@Composable
private fun ManufacturerCard(manufacturer: SleepyManufacturer, onGuideClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 16.dp, end = 8.dp, bottom = 4.dp)) {
            Column(Modifier.padding(end = 8.dp).semantics(mergeDescendants = true) {}) {
                Text(
                    stringResource(R.string.onboarding_manufacturer_title, manufacturer.displayName),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.onboarding_manufacturer_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onGuideClick, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.onboarding_manufacturer_guide))
            }
        }
    }
}

@Composable
private fun OnboardingPreviewFrame(
    state: OnboardingUiState,
    settingsOnlyRows: Set<OnboardingRow> = emptySet(),
    canNavigateBack: Boolean = false,
) {
    MeetingMinderTheme {
        OnboardingScreen(
            state = state,
            settingsOnlyRows = settingsOnlyRows,
            canNavigateBack = canNavigateBack,
            onAllowClick = {},
            onOpenSettingsClick = {},
            onManufacturerGuideClick = {},
            onContinueClick = {},
            onBackClick = {},
        )
    }
}

private val PartlyGranted = OnboardingUiState(calendarGranted = true, notificationsGranted = true, exactAlarmsGranted = false)

/** First launch: nothing granted, every row offers "Allow", the optional battery row included. */
@Preview(showBackground = true)
@Composable
internal fun OnboardingScreenStartPreview() {
    OnboardingPreviewFrame(OnboardingUiState(calendarGranted = false))
}

/** Reached from the overflow menu with every required row granted: Continue enabled, the optional battery row still offered. */
@Preview(showBackground = true)
@Composable
internal fun OnboardingScreenGrantedPreview() {
    OnboardingPreviewFrame(
        OnboardingUiState(
            calendarGranted = true,
            notificationsGranted = true,
            exactAlarmsGranted = true,
            fullScreenAlarmsGranted = true,
        ),
        canNavigateBack = true,
    )
}

/** Render 1's mid-way state: calendar and notifications granted, exact and full-screen alarms and battery still to allow. */
@Preview(showBackground = true)
@Composable
internal fun OnboardingScreenPartlyGrantedPreview() {
    OnboardingPreviewFrame(PartlyGranted)
}

/** Calendar denied twice: its button is "Open settings"; notifications on 12/12L is settings-only too. */
@Preview(showBackground = true)
@Composable
internal fun OnboardingScreenPermanentlyDeniedPreview() {
    OnboardingPreviewFrame(
        OnboardingUiState(calendarGranted = false),
        settingsOnlyRows = setOf(OnboardingRow.Calendar, OnboardingRow.Notifications),
    )
}

/**
 * Everything required granted and battery optimisation already off (so its row is gone),
 * but the app is restricted in the background — the warning row — on a Samsung phone, with
 * its instructions card.
 */
@Preview(showBackground = true)
@Composable
internal fun OnboardingScreenWarningsPreview() {
    OnboardingPreviewFrame(
        OnboardingUiState(
            calendarGranted = true,
            notificationsGranted = true,
            exactAlarmsGranted = true,
            fullScreenAlarmsGranted = true,
            batteryOptimizationIgnored = true,
            backgroundRestricted = true,
            sleepyManufacturer = SleepyManufacturer.Samsung,
        ),
        canNavigateBack = true,
    )
}

/** Dark theme: render 1's mid-way state with the restricted warning and the phone-maker card. */
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
internal fun OnboardingScreenDarkPreview() {
    OnboardingPreviewFrame(PartlyGranted.copy(backgroundRestricted = true, sleepyManufacturer = SleepyManufacturer.Xiaomi))
}

/** 1.5× font scale: descriptions wrap beside their buttons, nothing is cut off, and the list scrolls. */
@Preview(showBackground = true, fontScale = 1.5f)
@Composable
internal fun OnboardingScreenLargeFontPreview() {
    OnboardingPreviewFrame(PartlyGranted, settingsOnlyRows = setOf(OnboardingRow.Notifications))
}
