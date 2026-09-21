package com.episode6.meetingminder.ui.settings

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import com.episode6.meetingminder.ui.theme.MeetingMinderTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings → Busy calendar's "Your first name" field (TODO.md §4.7): only there while the
 * sync is on, reports every edit, previews the title the next sync writes, and takes the
 * stored name when it arrives after the first frame (DataStore's first read is late).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS")
class SettingsBusyFirstNameTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val edits = mutableListOf<String>()
    private var state by mutableStateOf(SettingsUiState(busySyncEnabled = true))

    private fun setContent() = composeRule.setContent {
        MeetingMinderTheme {
            SettingsScreen(
                state = state,
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
                onBusyFirstNameChanged = { edits += it },
                onBusySendTextToggle = {},
                onLoudChangeAlertsToggle = {},
                onPermissionsClick = {},
                onLicensesClick = {},
            )
        }
    }

    @Test
    fun typingAName_reportsIt_andPreviewsTheTitle() {
        setContent()
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasSetTextAction())
        composeRule.onNodeWithText("Blocks are titled \"busy\"", substring = true).assertExists()

        composeRule.onNode(hasSetTextAction()).performTextInput("Geoff")

        assertThat(edits).containsExactly("Geoff")
        composeRule.onNodeWithText("Blocks are titled \"Geoff busy\"", substring = true).assertExists()
    }

    @Test
    fun theStoredName_arrivingAfterTheFirstFrame_fillsTheField_withoutBeingReportedBack() {
        setContent()
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasSetTextAction())

        state = state.copy(busySyncFirstName = "Jane")

        composeRule.onNode(hasSetTextAction()).assert(hasText("Jane"))
        assertThat(edits).isEmpty()
    }

    @Test
    fun withTheSyncOff_thereIsNoField() {
        state = SettingsUiState(busySyncEnabled = false)
        setContent()

        composeRule.onNode(hasSetTextAction()).assertDoesNotExist()
    }

    @Test
    fun done_givesTheFieldUp_soTheTrimmedStoredNameTakesTheBufferOver() {
        setContent()
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasSetTextAction())
        composeRule.onNode(hasSetTextAction()).performTextInput("Geoff ")
        // the per-keystroke write has already come back, trimmed, while the field was focused
        state = state.copy(busySyncFirstName = "Geoff")
        composeRule.onNode(hasSetTextAction()).assert(hasText("Geoff "))

        composeRule.onNode(hasSetTextAction()).performImeAction()

        composeRule.onNode(hasSetTextAction()).assert(hasText("Geoff"))
        composeRule.onNode(hasSetTextAction()).assertIsNotFocused()
    }
}
