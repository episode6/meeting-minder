package com.episode6.meetingminder.ui.day

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.episode6.meetingminder.ui.theme.MeetingMinderTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/**
 * The FAB's label while busy-calendar sync (TODO.md §4.7) is effective: "Sync & Share"
 * instead of "Share schedule". PR-15a wires only the label; the calendar write itself is
 * PR-15b/c.
 */
@RunWith(RobolectricTestRunner::class)
class DayScreenFabLabelTest {

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
    fun syncNotEffective_readsPlainShareLabel() {
        show(DayUiState(anchorDate = today, meetingCount = 3, fabState = FabState.Share(syncs = false), armedCount = 3))

        composeRule.onNodeWithText("Share schedule", useUnmergedTree = true).assertExists()
    }

    @Test
    fun syncEffective_readsSyncAndShareLabel() {
        show(DayUiState(anchorDate = today, meetingCount = 3, fabState = FabState.Share(syncs = true), armedCount = 3))

        composeRule.onNodeWithText("Sync & Share", useUnmergedTree = true).assertExists()
    }
}
