package com.episode6.meetingminder.ui.day

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.episode6.meetingminder.data.calendar.ShareMode
import com.episode6.meetingminder.monitor.ScheduleChangeLine
import com.episode6.meetingminder.ui.theme.MeetingMinderTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/**
 * The FAB's label while busy-calendar sync (TODO.md §4.7) is effective: "Sync & Share"
 * instead of "Share schedule", or "Sync busy times" with the text turned off — and where in
 * the semantics tree that label lives, which is how `BusyCalendarSyncDeviceTest` finds the
 * button to tap. Plus the sync-only wording of the overflow and the change banner.
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
        show(DayUiState(anchorDate = today, meetingCount = 3, fabState = FabState.Share(), armedCount = 3))

        composeRule.onNodeWithText("Share schedule", useUnmergedTree = true).assertExists()
    }

    @Test
    fun syncEffective_readsSyncAndShareLabel() {
        show(DayUiState(anchorDate = today, meetingCount = 3, fabState = FabState.Share(ShareMode.SYNC_AND_TEXT), armedCount = 3))

        composeRule.onNodeWithText("Sync & Share", useUnmergedTree = true).assertExists()
    }

    /**
     * The Material 3 extended FAB wraps its label in `clearAndSetSemantics`, so the label
     * exists in the **unmerged** tree only — not on the FAB's clickable node, and not as a
     * descendant of it in the merged tree. `BusyCalendarSyncDeviceTest` therefore taps that
     * unmerged text node (a `performClick` is an injected touch, which lands on the FAB
     * under it); pinned here so the two can't drift apart.
     */
    @Test
    fun theFabsLabelIsFoundOnlyInTheUnmergedTree() {
        show(DayUiState(anchorDate = today, meetingCount = 3, fabState = FabState.Share(ShareMode.SYNC_AND_TEXT), armedCount = 3))

        composeRule.onAllNodes(hasText("Sync & Share")).assertCountEquals(0)
        composeRule.onAllNodes(hasText("Sync & Share"), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun syncOnly_readsSyncBusyTimesLabel() {
        show(DayUiState(anchorDate = today, meetingCount = 3, fabState = FabState.Share(ShareMode.SYNC_ONLY), armedCount = 3))

        composeRule.onNodeWithText("Sync busy times", useUnmergedTree = true).assertExists()
    }

    @Test
    fun syncOnly_overflowOffersSyncAgainAndRemoveBusyBlocks() {
        show(
            DayUiState(
                anchorDate = today, meetingCount = 3, fabState = FabState.Synced, armedCount = 3,
                sharedAt = today.atTime(8, 12), shareMode = ShareMode.SYNC_ONLY,
            ),
        )

        composeRule.onNodeWithContentDescription("More options").performClick()

        composeRule.onNodeWithText("Sync again").assertExists()
        composeRule.onNodeWithText("Remove busy blocks").assertExists()
        composeRule.onAllNodes(hasText("Share again")).assertCountEquals(0)
    }

    @Test
    fun syncOnly_bannerReadsSinceYouSynced_andReSync() {
        show(
            DayUiState(
                anchorDate = today, meetingCount = 3, fabState = FabState.Synced, armedCount = 3,
                sharedAt = today.atTime(8, 12), shareMode = ShareMode.SYNC_ONLY,
                changeBanner = ScheduleChangeBannerState(listOf(ScheduleChangeLine.New("3:00 – 3:30 PM"))),
            ),
        )

        composeRule.onNodeWithText("1 change since you synced", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("Re-sync").assertExists()
    }

    @Test
    fun syncOnly_onceSynced_showsNoButton_andTheBannerCarriesTheReSync() {
        show(
            DayUiState(
                anchorDate = today, meetingCount = 3, fabState = FabState.Synced, armedCount = 3,
                sharedAt = today.atTime(8, 12), shareMode = ShareMode.SYNC_ONLY,
                changeBanner = ScheduleChangeBannerState(listOf(ScheduleChangeLine.New("3:00 – 3:30 PM"))),
            ),
        )

        composeRule.onAllNodes(hasText("Sync busy times"), useUnmergedTree = true).assertCountEquals(0)
        composeRule.onAllNodes(hasText("Re-sync")).assertCountEquals(1)
    }
}
