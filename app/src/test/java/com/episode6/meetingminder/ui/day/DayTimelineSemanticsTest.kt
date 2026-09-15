package com.episode6.meetingminder.ui.day

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import com.episode6.meetingminder.ui.theme.MeetingMinderTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalTime

/** The TalkBack pass (TODO.md §5 PR-13): what the day timeline exposes to accessibility services. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS")
class DayTimelineSemanticsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun show(state: DayTimelineState) {
        composeRule.setContent {
            MeetingMinderTheme {
                DayTimeline(
                    state = state,
                    scrollState = rememberTimelineScrollState(PreviewEvents.FIRST_VISIBLE_HOUR),
                    onEventClick = {},
                    onEventLongClick = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    private fun hasStateDescription(description: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, description)

    @Test
    fun hourGutterLabels_areNotInTheSemanticsTree() {
        show(PreviewEvents.busyDay)

        // the merged tree is what accessibility services get; the unmerged one still lists
        // the children of a clearAndSetSemantics node
        assertThat(composeRule.onAllNodesWithText("9 AM").fetchSemanticsNodes()).isEmpty()
        assertThat(composeRule.onAllNodesWithText("all-day").fetchSemanticsNodes()).isEmpty()
        // and they really are rendered, just not exposed
        assertThat(composeRule.onAllNodesWithText("9 AM", useUnmergedTree = true).fetchSemanticsNodes().size).isEqualTo(1)
    }

    @Test
    fun aSelectableChip_readsTitleTimesAndPlace_asACheckboxWithItsState_andLabelledActions() {
        val event = PreviewEvents.standup.copy(title = "Design review", location = "Meet", selected = false)
        show(DayTimelineState(date = PreviewDate, timedEvents = listOf(event)))

        val begin = event.begin.toLocalTime().formatA11y()
        val end = event.end.toLocalTime().formatA11y()
        val node = composeRule.onNodeWithContentDescription("Design review, $begin to $end, Meet")
        node.assert(hasStateDescription("Not selected"))
        node.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
        val semantics = node.fetchSemanticsNode().config
        assertThat(semantics.getOrNull(SemanticsActions.OnClick)?.label).isEqualTo("select")
        assertThat(semantics.getOrNull(SemanticsActions.OnLongClick)?.label).isEqualTo("open in calendar")
    }

    @Test
    fun anArmedChip_saysWhenItsAlarmRings() {
        val event = PreviewEvents.standup.copy(title = "Standup", location = null, selected = true, alarmAt = LocalTime.of(8, 55))
        show(DayTimelineState(date = PreviewDate, timedEvents = listOf(event)))

        val node = composeRule.onNodeWithContentDescription("Standup", substring = true)
        node.assert(hasStateDescription("Alarm set for 8:55 AM"))
        assertThat(node.fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick)?.label).isEqualTo("deselect")
    }

    @Test
    fun anArmedChip_speaksAnOnTheHourAlarmInFull_notAsTheChipsShortForm() {
        val event = PreviewEvents.standup.copy(title = "Standup", location = null, selected = true, alarmAt = LocalTime.of(9, 0))
        show(DayTimelineState(date = PreviewDate, timedEvents = listOf(event)))

        composeRule.onNodeWithContentDescription("Standup", substring = true).assert(hasStateDescription("Alarm set for 9:00 AM"))
    }

    @Test
    fun aDeclinedChip_saysSo_andOffersNoSelectAction() {
        val event = PreviewEvents.standup.copy(title = "Skipped sync", location = null, status = ChipStatus.Declined)
        show(DayTimelineState(date = PreviewDate, timedEvents = listOf(event)))

        val node = composeRule.onNodeWithContentDescription("Skipped sync", substring = true)
        node.assert(hasStateDescription("Declined"))
        assertThat(node.fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick)?.label).isEqualTo(null)
    }

    @Test
    fun anAllDayChip_readsAllDay() {
        show(DayTimelineState(date = PreviewDate, allDayEvents = listOf(PreviewEvents.planningWeek)))

        composeRule.onNodeWithContentDescription("${PreviewEvents.planningWeek.title}, all day").assertExists()
    }

    private fun LocalTime.formatA11y(): String = TimelineTimeFormat(is24Hour = false, locale = java.util.Locale.US).timeWithPeriod(this)
}
