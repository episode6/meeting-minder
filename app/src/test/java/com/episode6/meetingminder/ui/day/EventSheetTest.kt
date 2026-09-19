package com.episode6.meetingminder.ui.day

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import com.episode6.meetingminder.model.EventResponse
import com.episode6.meetingminder.ui.theme.MeetingMinderTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The chip's long-press sheet: the full title with when and where, "Open in calendar", then
 * the three answers only for a respondable event, with the current one selected.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS")
class EventSheetTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val responses = mutableListOf<EventResponse>()
    private var opened = false

    private val longTitle = "Quarterly business review with the Northwind partnership team (legal + finance)"

    private fun show(event: TimelineEvent) {
        composeRule.setContent {
            MeetingMinderTheme {
                EventChip(
                    event = event,
                    onClick = {},
                    onOpenClick = { opened = true },
                    onRespond = { responses += it },
                    timeFormat = TimelineTimeFormat(is24Hour = false, locale = java.util.Locale.US),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                )
            }
        }
    }

    private fun longPressChip(title: String = PreviewEvents.standup.title) {
        composeRule.onAllNodesWithText(title)[0].performTouchInput { longClick() }
        composeRule.waitForIdle()
    }

    private fun sheetIsGone() = composeRule.onAllNodesWithText("Open in calendar").fetchSemanticsNodes().isEmpty()

    @Test
    fun theSheet_showsTheFullTitle_withWhenAndWhere() {
        show(PreviewEvents.designReview.copy(title = longTitle))

        longPressChip(longTitle)

        // the chip's copy plus the sheet's
        assertThat(composeRule.onAllNodesWithText(longTitle).fetchSemanticsNodes().size).isEqualTo(2)
        composeRule.onNodeWithText("Monday, Sep 14").assertIsDisplayed()
        composeRule.onNodeWithText("10:00 AM – 11:00 AM").assertIsDisplayed()
        composeRule.onNodeWithText("Meet").assertIsDisplayed()
    }

    @Test
    fun aRespondableChip_offersOpenThenTheThreeAnswers_withTheCurrentOneSelected() {
        show(PreviewEvents.standup.copy(respondable = true, response = EventResponse.MAYBE))

        longPressChip()

        composeRule.onNodeWithText("Open in calendar").assertIsDisplayed()
        composeRule.onNodeWithText("Your response").assertIsDisplayed()
        composeRule.onNodeWithText("Yes").assertIsNotSelected()
        composeRule.onNodeWithText("No").assertIsNotSelected()
        composeRule.onNodeWithText("Maybe").assertIsSelected()
    }

    @Test
    fun choosingAnAnswer_reportsIt_andClosesTheSheet() {
        show(PreviewEvents.standup.copy(respondable = true))

        longPressChip()
        composeRule.onNodeWithText("No").performClick()
        composeRule.waitForIdle()

        assertThat(responses).containsExactly(EventResponse.NO)
        assertThat(sheetIsGone()).isTrue()
    }

    @Test
    fun openInCalendar_reportsTheOpen_andClosesTheSheet() {
        show(PreviewEvents.standup.copy(respondable = true))

        longPressChip()
        composeRule.onNodeWithText("Open in calendar").performClick()
        composeRule.waitForIdle()

        assertThat(opened).isTrue()
        assertThat(sheetIsGone()).isTrue()
    }

    @Test
    fun aChipThatCannotBeAnswered_offersOnlyOpen() {
        show(PreviewEvents.standup.copy(respondable = false))

        longPressChip()

        composeRule.onNodeWithText("Open in calendar").assertIsDisplayed()
        assertThat(composeRule.onAllNodesWithText("Your response").fetchSemanticsNodes().size).isEqualTo(0)
        assertThat(composeRule.onAllNodesWithText("Yes").fetchSemanticsNodes().size).isEqualTo(0)
    }
}
