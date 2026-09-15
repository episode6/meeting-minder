package com.episode6.meetingminder.ui.day

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import assertk.assertThat
import assertk.assertions.isEqualTo
import com.episode6.meetingminder.ui.theme.MeetingMinderTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/** Midnight rollover while the day view is open (TODO.md §5 PR-13): the anchor moves, the page being viewed doesn't. */
@RunWith(RobolectricTestRunner::class)
class DayScreenRolloverTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val today = LocalDate.of(2026, 9, 14)

    @Test
    fun anchorMovingToTheNextDay_keepsShowingTheDateThatWasSettled() {
        var state by mutableStateOf(DayUiState(anchorDate = today, date = today.plusDays(2)))
        val settled = mutableListOf<LocalDate>()
        composeRule.setContent {
            MeetingMinderTheme {
                DayScreen(
                    state = state,
                    onPageSettled = { date ->
                        settled += date
                        state = state.copy(date = date)
                    },
                    onPermissionsClick = {},
                    onSettingsClick = {},
                    onLicensesClick = {},
                    onCheckForUpdatesClick = {},
                    onShareAgainClick = {},
                    onMarkNotSharedClick = {},
                    onEventClick = { _, _ -> },
                    onRefreshClick = {},
                    onEventOpenClick = {},
                    onEventRespond = { _, _, _ -> },
                    onFabClick = {},
                )
            }
        }
        composeRule.waitForIdle()
        assertThat(settled.last()).isEqualTo(today.plusDays(2))

        state = state.copy(anchorDate = today.plusDays(1))
        composeRule.waitForIdle()

        assertThat(settled.last()).isEqualTo(today.plusDays(2))
        composeRule.onNodeWithText("Wednesday, Sep 16").assertExists()
    }

    @Test
    fun anchorMoving_whileViewingTheOldToday_staysOnThatDate() {
        var state by mutableStateOf(DayUiState(anchorDate = today))
        val settled = mutableListOf<LocalDate>()
        composeRule.setContent {
            MeetingMinderTheme {
                DayScreen(
                    state = state,
                    onPageSettled = { date ->
                        settled += date
                        state = state.copy(date = date)
                    },
                    onPermissionsClick = {},
                    onSettingsClick = {},
                    onLicensesClick = {},
                    onCheckForUpdatesClick = {},
                    onShareAgainClick = {},
                    onMarkNotSharedClick = {},
                    onEventClick = { _, _ -> },
                    onRefreshClick = {},
                    onEventOpenClick = {},
                    onEventRespond = { _, _, _ -> },
                    onFabClick = {},
                )
            }
        }
        composeRule.waitForIdle()

        state = state.copy(anchorDate = today.plusDays(1))
        composeRule.waitForIdle()

        // yesterday's page stays up (the Today action now leads to the new date)
        assertThat(settled.last()).isEqualTo(today)
    }
}
