package com.episode6.meetingminder.ui.day

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.episode6.meetingminder.model.DayJump
import com.episode6.meetingminder.ui.theme.MeetingMinderTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/**
 * `ShowDay` (a deep link, the app coming forward before today was shown) moving the pager
 * through [DayUiState.dayJump]. The callbacks stand in for the store: a settle sets the date,
 * and a landed jump settles on its date and clears it (`DayJumpLanded`).
 */
@RunWith(RobolectricTestRunner::class)
class DayScreenJumpTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val today = LocalDate.of(2026, 9, 30)
    private var state by mutableStateOf(DayUiState(anchorDate = today))
    private val settled = mutableListOf<LocalDate>()
    private val landed = mutableListOf<DayJump>()
    private var jumps = 0

    @Test
    fun aJump_scrollsToTheDay_andReportsItLanded() {
        showDayScreen()

        jumpTo(today.plusDays(5))

        assertThat(settled.last()).isEqualTo(today.plusDays(5))
        assertThat(landed).containsExactly(DayJump(today.plusDays(5), id = 1))
        assertThat(state.dayJump).isNull()
        composeRule.onNodeWithText("Monday, Oct 5").assertExists()
    }

    @Test
    fun eachJump_scrollsAgain_evenBackToTheAnchor() {
        showDayScreen()
        jumpTo(today.plusDays(1))
        assertThat(settled.last()).isEqualTo(today.plusDays(1))

        jumpTo(today)

        assertThat(settled.last()).isEqualTo(today)
        composeRule.onNodeWithText("Wednesday, Sep 30").assertExists()
    }

    @Test
    fun aJumpToThePageAlreadyShown_stillLands() {
        showDayScreen()

        jumpTo(today)

        assertThat(landed).containsExactly(DayJump(today, id = 1))
        assertThat(state.dayJump).isNull()
    }

    @Test
    fun aPendingJump_winsOverAStaleSettledDate() {
        // back from Settings: the stale first frame reported the old page over ShowDay's date
        showDayScreen()
        state = state.copy(date = today.plusDays(2))
        composeRule.waitForIdle()

        state = state.copy(dayJump = DayJump(today.plusDays(5), id = 1))
        composeRule.waitForIdle()

        assertThat(settled.last()).isEqualTo(today.plusDays(5))
        composeRule.onNodeWithText("Monday, Oct 5").assertExists()
    }

    @Test
    fun aJumpToToday_thatMovesTheAnchorInTheSameState_landsOnToday() {
        // the process outlived the night: the pager is anchored on yesterday, showing the day before
        state = DayUiState(anchorDate = today.minusDays(1), date = today.minusDays(2))
        showDayScreen()
        assertThat(settled.last()).isEqualTo(today.minusDays(2))

        state = state.copy(anchorDate = today, date = today, dayJump = DayJump(today, ++jumps))
        composeRule.waitForIdle()

        assertThat(settled.last()).isEqualTo(today)
        composeRule.onNodeWithText("Wednesday, Sep 30").assertExists()
    }

    @Test
    fun aJumpToToday_beforeTheAnchorCatchesUp_staysOnToday() {
        state = DayUiState(anchorDate = today.minusDays(1), date = today.minusDays(2))
        showDayScreen()

        jumpTo(today)
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
        jumpTo(today)

        assertThat(settled.last()).isEqualTo(today)
    }

    /** What `ShowDay` does to the state the screen sees. */
    private fun jumpTo(date: LocalDate) {
        state = state.copy(date = date, dayJump = DayJump(date, ++jumps))
        composeRule.waitForIdle()
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
                    onDayJumpLanded = { jump ->
                        landed += jump
                        if (state.dayJump?.id == jump.id) state = state.copy(date = jump.date, dayJump = null)
                    },
                )
            }
        }
        composeRule.waitForIdle()
    }
}
