package com.episode6.meetingminder.ui.settings

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import com.episode6.meetingminder.alarm.AlarmSound
import com.episode6.meetingminder.ui.theme.MeetingMinderTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings → Alarm sounds (TODO.md §4.4) as TalkBack and a tap see it: every row is a
 * checkbox, a group's header is one too (partly checked for a mixed group) and checks the
 * rest of the group, and an unchecked group's header checks it whole.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS")
class AlarmSoundsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val checkbox = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)
    private val indeterminate = SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Indeterminate)

    private val state = AlarmSoundsUiState(
        loaded = true,
        system = listOf(
            SoundChoice("system:1", "Cesium", enabled = true),
            SoundChoice("system:2", "Krypton", enabled = false),
        ),
        bundled = listOf(
            SoundChoice("bundled:Argon", "Argon", enabled = false),
            SoundChoice("bundled:Carbon", "Carbon", enabled = false),
        ),
        sirenEnabled = true,
    )

    /** The groups below the fold aren't composed until the lazy list is scrolled to them. */
    private fun ComposeContentTestRule.scrollTo(text: String) {
        onNode(hasScrollAction()).performScrollToNode(hasText(text))
    }

    private fun setContent(
        onSoundToggle: (String, Boolean) -> Unit = { _, _ -> },
        onGroupToggle: (AlarmSoundGroup, Boolean) -> Unit = { _, _ -> },
    ) {
        composeRule.setContent {
            MeetingMinderTheme {
                AlarmSoundsScreen(state = state, onBackClick = {}, onSoundToggle = onSoundToggle, onGroupToggle = onGroupToggle)
            }
        }
    }

    @Test
    fun everySound_isACheckboxRow_thatReportsItsToggle() {
        val toggled = mutableListOf<Pair<String, Boolean>>()
        setContent(onSoundToggle = { id, enabled -> toggled += id to enabled })

        composeRule.onNode(hasText("Cesium") and isToggleable()).assert(checkbox).assertIsOn().performClick()
        composeRule.onNode(hasText("Krypton") and isToggleable()).assert(checkbox).assertIsOff().performClick()
        composeRule.scrollTo("Siren sweep")
        composeRule.onNode(hasText("Siren sweep") and isToggleable()).assert(checkbox).assertIsOn()

        assertThat(toggled).containsExactly("system:1" to false, "system:2" to true)
    }

    @Test
    fun aMixedGroup_readsPartlyChecked_andItsHeaderChecksTheRest() {
        val toggled = mutableListOf<Pair<AlarmSoundGroup, Boolean>>()
        setContent(onGroupToggle = { group, enabled -> toggled += group to enabled })

        composeRule.onNode(hasText("Device alarm sounds") and isToggleable())
            .assert(checkbox)
            .assert(indeterminate)
            .assert(hasText("1 of 2 on"))
            .performClick()

        assertThat(toggled).containsExactly(AlarmSoundGroup.SYSTEM to true)
    }

    @Test
    fun anUncheckedGroup_readsOff_andItsHeaderChecksItWhole_aCheckedOneUnchecksIt() {
        val toggled = mutableListOf<Pair<AlarmSoundGroup, Boolean>>()
        setContent(onGroupToggle = { group, enabled -> toggled += group to enabled })

        composeRule.scrollTo("Bundled sounds")
        composeRule.onNode(hasText("Bundled sounds") and isToggleable()).assertIsOff().assert(hasText("0 of 2 on")).performClick()
        composeRule.scrollTo("Siren")
        composeRule.onNode(hasText("Siren") and isToggleable()).assertIsOn().assert(hasText("1 of 1 on")).performClick()

        assertThat(toggled).containsExactly(AlarmSoundGroup.BUNDLED to true, AlarmSoundGroup.SIREN to false)
    }

    @Test
    fun beforeTheCatalogLoads_noGroupIsShown() {
        val toggled = mutableListOf<Pair<AlarmSoundGroup, Boolean>>()
        composeRule.setContent {
            MeetingMinderTheme {
                AlarmSoundsScreen(
                    state = AlarmSoundsUiState(),
                    onBackClick = {},
                    onSoundToggle = { _, _ -> },
                    onGroupToggle = { group, enabled -> toggled += group to enabled },
                )
            }
        }

        composeRule.onNodeWithText("Siren sweep").assertDoesNotExist()
        composeRule.onNodeWithText("Device alarm sounds").assertDoesNotExist()
        assertThat(toggled).isEmpty()
    }

    /** The siren's row is the id the recipe knows, not the shown name. */
    @Test
    fun theSirenRow_togglesTheSirenId() {
        val toggled = mutableListOf<Pair<String, Boolean>>()
        setContent(onSoundToggle = { id, enabled -> toggled += id to enabled })

        composeRule.scrollTo("Siren sweep")
        composeRule.onNode(hasText("Siren sweep") and isToggleable()).performClick()

        assertThat(toggled).containsExactly(AlarmSound.SIREN_ID to false)
    }
}
