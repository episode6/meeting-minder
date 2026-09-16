package com.episode6.meetingminder.ui.settings

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import com.episode6.meetingminder.model.CalendarInfo
import com.episode6.meetingminder.ui.theme.MeetingMinderTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * The single-choice chip rows read as radio buttons (TODO.md §5 PR-13 TalkBack pass): the
 * outer `semantics { role }` must win over `FilterChip`'s own `Role.Checkbox`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS")
class SettingsChipsSemanticsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val radioButton = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

    @Test
    fun durationAndSoundChips_readAsRadioButtons() {
        composeRule.setContent {
            MeetingMinderTheme {
                SettingsScreen(
                    state = SettingsUiState(leadTime = Duration.ofMinutes(5), snoozeLength = Duration.ofMinutes(2), autoTimeout = Duration.ofMinutes(3)),
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

        composeRule.onNodeWithText("All").assert(radioButton)
        composeRule.onNodeWithText("System only").assert(radioButton)
        composeRule.onAllNodes(hasText(" min", substring = true)).onFirst().assert(radioButton)
    }

    @Test
    fun busyCalendarRadioRows_readAsRadioButtons() {
        val family = CalendarInfo(
            id = 1,
            accountName = "me@example.com",
            accountType = "com.google",
            displayName = "Family",
            color = 0xFF0B8043.toInt(),
            visible = true,
            syncEvents = true,
            ownerAccount = "me@example.com",
            isPrimary = false,
            accessLevel = 700,
            canOrganizerRespond = false,
        )

        composeRule.setContent {
            MeetingMinderTheme {
                SettingsScreen(
                    state = SettingsUiState(busySyncEnabled = true, writableCalendars = listOf(family)),
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

        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Family"))
        composeRule.onNodeWithText("Family").assert(radioButton)
    }
}
