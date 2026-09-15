package com.episode6.meetingminder.ui.day

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
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

/** The chip's long-press menu: "Open in calendar" first, then the three answers only for a respondable event. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS")
class EventChipMenuTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val responses = mutableListOf<EventResponse>()
    private var opened = false

    private fun show(event: TimelineEvent) {
        composeRule.setContent {
            MeetingMinderTheme {
                EventChip(
                    event = event,
                    onClick = {},
                    onOpenClick = { opened = true },
                    onRespond = { responses += it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                )
            }
        }
    }

    private fun longPressChip() {
        composeRule.onNodeWithText(PreviewEvents.standup.title).performTouchInput { longClick() }
        composeRule.waitForIdle()
    }

    @Test
    fun aRespondableChip_offersOpenThenTheThreeAnswers_withTheCurrentOneMarked() {
        show(PreviewEvents.standup.copy(respondable = true, response = EventResponse.MAYBE))

        longPressChip()

        composeRule.onNodeWithText("Open in calendar").assertIsDisplayed()
        composeRule.onNodeWithText("Respond Yes").assertIsDisplayed()
        composeRule.onNodeWithText("Respond No").assertIsDisplayed()
        composeRule.onNodeWithText("Respond Maybe").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Your current response").assertIsDisplayed()
    }

    @Test
    fun choosingAnAnswer_reportsIt_andClosesTheMenu() {
        show(PreviewEvents.standup.copy(respondable = true))

        longPressChip()
        composeRule.onNodeWithText("Respond No").performClick()
        composeRule.waitForIdle()

        assertThat(responses).containsExactly(EventResponse.NO)
        assertThat(composeRule.onAllNodesWithText("Respond No").fetchSemanticsNodes().size).isEqualTo(0)
    }

    @Test
    fun openInCalendar_reportsTheOpen() {
        show(PreviewEvents.standup.copy(respondable = true))

        longPressChip()
        composeRule.onNodeWithText("Open in calendar").performClick()
        composeRule.waitForIdle()

        assertThat(opened).isTrue()
    }

    @Test
    fun aChipThatCannotBeAnswered_offersOnlyOpen() {
        show(PreviewEvents.standup.copy(respondable = false))

        longPressChip()

        composeRule.onNodeWithText("Open in calendar").assertIsDisplayed()
        assertThat(composeRule.onAllNodesWithText("Respond Yes").fetchSemanticsNodes().size).isEqualTo(0)
    }
}
