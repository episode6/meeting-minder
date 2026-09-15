package com.episode6.meetingminder.ui.day

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.episode6.meetingminder.ui.theme.MeetingMinderTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalTime

/**
 * The compact (one-line) chip gives the whole line to the title when title and time range
 * can't share it: the hour grid already says when the event is, only the chip says what.
 * Runs with native graphics so text is measured with real font metrics (legacy graphics
 * measure every glyph at about a pixel, and everything fits).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EventChipCompactTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val timeRange = "9:00 – 9:30"

    private fun show(event: TimelineEvent, width: Int = 200) {
        composeRule.setContent {
            MeetingMinderTheme {
                EventChip(
                    event = event,
                    onClick = {},
                    onLongClick = {},
                    contentLayout = ChipContentLayout.Compact,
                    modifier = Modifier
                        .width(width.dp)
                        .height(32.dp),
                )
            }
        }
    }

    @Test
    fun aShortTitle_showsTheTimeRangeBesideIt() {
        show(PreviewEvents.standup.copy(title = "Standup"))

        composeRule.onNodeWithText("Standup", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText(timeRange, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun aTitleThatWouldBeEllipsized_hidesTheTimeRange() {
        val title = "Quarterly planning review with the whole platform team"
        show(PreviewEvents.standup.copy(title = title))

        composeRule.onNodeWithText(title, useUnmergedTree = true).assertIsDisplayed()
        // composed but not placed: the Layout drops it rather than never composing it
        composeRule.onNodeWithText(timeRange, useUnmergedTree = true).assertIsNotDisplayed()
    }

    @Test
    fun anArmedChip_keepsItsAlarmTime_evenWithALongTitle() {
        val title = "Quarterly planning review with the whole platform team"
        show(PreviewEvents.standup.copy(title = title, selected = true, alarmAt = LocalTime.of(8, 55)))

        composeRule.onNodeWithText("8:55", useUnmergedTree = true).assertIsDisplayed()
        // the armed row never composes the range at all, unlike the unplaced one above
        composeRule.onNodeWithText(timeRange, useUnmergedTree = true).assertDoesNotExist()
    }
}
