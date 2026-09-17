package com.episode6.meetingminder.ui.settings

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.isToggleable
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

    private val family = CalendarInfo(
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

    /**
     * The same calendar sits in both the Calendars list and the Busy-calendar radio list, as
     * it does for any writable calendar: the radio rows must not reuse the Calendars rows'
     * `LazyColumn` keys (they live in one `item` so they can't), or composing both throws
     * "Key was already used".
     */
    @Test
    fun busyCalendarRadioRows_readAsRadioButtons_besideTheSameCalendarsSwitchRow() {
        composeRule.setContent {
            MeetingMinderTheme {
                SettingsScreen(
                    state = SettingsUiState(
                        calendars = listOf(CalendarRow(family, included = true)),
                        busySyncEnabled = true,
                        busySyncCalendarId = family.id,
                        writableCalendars = listOf(family),
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

        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Family") and radioButton)
        composeRule.onNode(hasText("Family") and radioButton).assertIsSelected()
        composeRule.onNode(hasText("Family") and isToggleable()).assertIsOn()
    }

    /** A checked toggle stays enabled with nothing writable left, so the feature can still be turned off. */
    @Test
    fun busySyncToggle_staysEnabledWhileOn_evenWithNoWritableCalendar() {
        composeRule.setContent {
            MeetingMinderTheme {
                SettingsScreen(
                    state = SettingsUiState(busySyncEnabled = true, busySyncCalendarId = family.id, writableCalendars = emptyList()),
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

        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Sync busy times to a calendar"))
        composeRule.onNodeWithText("Sync busy times to a calendar").assertIsEnabled().assertIsOn()
    }

    /** Off with nothing writable there is nothing to turn it on to, so the toggle is disabled. */
    @Test
    fun busySyncToggle_isDisabledWhileOff_withNoWritableCalendar() {
        composeRule.setContent {
            MeetingMinderTheme {
                SettingsScreen(
                    state = SettingsUiState(busySyncEnabled = false, writableCalendars = emptyList()),
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

        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Sync busy times to a calendar"))
        composeRule.onNodeWithText("Sync busy times to a calendar").assertIsNotEnabled()
    }
}
