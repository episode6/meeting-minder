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

/** `ShowDay` (a deep link, the first foreground of a day) moving the pager through [DayUiState.dayJumps]. */
@RunWith(RobolectricTestRunner::class)
class DayScreenJumpTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val today = LocalDate.of(2026, 9, 30)
    private var state by mutableStateOf(DayUiState(anchorDate = today))
    private val settled = mutableListOf<LocalDate>()

    @Test
    fun aJump_scrollsToTheDay() {
        showDayScreen()

        state = state.copy(date = today.plusDays(5), dayJumps = 1)
        composeRule.waitForIdle()

        assertThat(settled.last()).isEqualTo(today.plusDays(5))
        composeRule.onNodeWithText("Monday, Oct 5").assertExists()
    }

    @Test
    fun eachJump_scrollsAgain_evenBackToTheAnchor() {
        showDayScreen()
        state = state.copy(date = today.plusDays(1), dayJumps = 1)
        composeRule.waitForIdle()
        assertThat(settled.last()).isEqualTo(today.plusDays(1))

        state = state.copy(date = today, dayJumps = 2)
        composeRule.waitForIdle()

        assertThat(settled.last()).isEqualTo(today)
        composeRule.onNodeWithText("Wednesday, Sep 30").assertExists()
    }

    @Test
    fun aJumpToToday_thatMovesTheAnchorInTheSameState_landsOnToday() {
        // the process outlived the night: the pager is anchored on yesterday, showing the day before
        state = DayUiState(anchorDate = today.minusDays(1), date = today.minusDays(2))
        showDayScreen()
        assertThat(settled.last()).isEqualTo(today.minusDays(2))

        state = state.copy(anchorDate = today, date = today, dayJumps = 1)
        composeRule.waitForIdle()

        assertThat(settled.last()).isEqualTo(today)
        composeRule.onNodeWithText("Wednesday, Sep 30").assertExists()
    }

    @Test
    fun aJumpToToday_beforeTheAnchorCatchesUp_staysOnToday() {
        state = DayUiState(anchorDate = today.minusDays(1), date = today.minusDays(2))
        showDayScreen()

        state = state.copy(date = today, dayJumps = 1)
        composeRule.waitForIdle()
        state = state.copy(anchorDate = today)
        composeRule.waitForIdle()

        assertThat(settled.last()).isEqualTo(today)
    }

    @Test
    fun theAnchorCatchingUpFirst_thenTheJump_landsOnToday() {
        state = DayUiState(anchorDate = today.minusDays(1), date = today.minusDays(2))
        showDayScreen()

        state = state.copy(anchorDate = today)
        composeRule.waitForIdle()
        state = state.copy(date = today, dayJumps = 1)
        composeRule.waitForIdle()

        assertThat(settled.last()).isEqualTo(today)
    }

    private fun showDayScreen() {
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
    }
}
