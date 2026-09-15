package com.episode6.meetingminder.ui.day

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.episode6.meetingminder.ui.theme.MeetingMinderTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/** The app bar's "shared …" subtitle (TODO.md §4.2): a bare time only when the share happened on the day being viewed. */
@RunWith(RobolectricTestRunner::class)
class DayScreenSubtitleTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val today = LocalDate.of(2026, 9, 14)

    private fun show(state: DayUiState) {
        composeRule.setContent {
            MeetingMinderTheme {
                DayScreen(
                    state = state,
                    onPageSettled = {},
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

    @Test
    fun aDaySharedOnTheDayItself_showsJustTheTime() {
        show(DayUiState(anchorDate = today, meetingCount = 3, fabState = FabState.Share, armedCount = 3, sharedAt = today.atTime(8, 12)))

        composeRule.onNodeWithText("shared 8:12 AM").assertExists()
    }

    @Test
    fun aDaySharedOnAnotherDay_namesThatDay() {
        // tomorrow's schedule, shared tonight: "shared 9:00 PM" on tomorrow's page would read as tomorrow evening
        val tomorrow = today.plusDays(1)
        show(
            DayUiState(
                anchorDate = today, date = tomorrow, meetingCount = 3, fabState = FabState.Share, armedCount = 3,
                sharedAt = today.atTime(21, 0),
            ),
        )

        composeRule.onNodeWithText("shared Sep 14, 9:00 PM").assertExists()
    }
}
