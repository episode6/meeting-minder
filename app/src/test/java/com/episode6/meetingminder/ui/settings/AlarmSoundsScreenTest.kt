package com.episode6.meetingminder.ui.settings

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
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
 * Settings → Alarm sounds (TODO.md §4.4) as TalkBack and a tap see it: every sound has a
 * checkbox named for it, a group's header is one too (partly checked for a mixed group) and
 * checks the rest of the group, an unchecked group's header checks it whole, and tapping a
 * sound's row (not its checkbox) plays it.
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

    /** A sound's checkbox, which carries its name as a content description rather than as text. */
    private fun soundCheckbox(name: String) = hasContentDescription(name) and isToggleable()

    /** A sound's row, which plays it. */
    private fun soundRow(name: String) = hasText(name) and hasClickAction()

    private fun setContent(
        state: AlarmSoundsUiState = this.state,
        onSoundToggle: (String, Boolean) -> Unit = { _, _ -> },
        onGroupToggle: (AlarmSoundGroup, Boolean) -> Unit = { _, _ -> },
        onSoundPreview: (String) -> Unit = {},
    ) {
        composeRule.setContent {
            MeetingMinderTheme {
                AlarmSoundsScreen(
                    state = state,
                    onBackClick = {},
                    onSoundToggle = onSoundToggle,
                    onGroupToggle = onGroupToggle,
                    onSoundPreview = onSoundPreview,
                )
            }
        }
    }

    @Test
    fun everySound_hasACheckbox_thatReportsItsToggle_andPlaysNothing() {
        val toggled = mutableListOf<Pair<String, Boolean>>()
        val previewed = mutableListOf<String>()
        setContent(onSoundToggle = { id, enabled -> toggled += id to enabled }, onSoundPreview = { previewed += it })

        composeRule.onNode(soundCheckbox("Cesium")).assert(checkbox).assertIsOn().performClick()
        composeRule.onNode(soundCheckbox("Krypton")).assert(checkbox).assertIsOff().performClick()
        composeRule.scrollTo("Siren sweep")
        composeRule.onNode(soundCheckbox("Siren sweep")).assert(checkbox).assertIsOn()

        assertThat(toggled).containsExactly("system:1" to false, "system:2" to true)
        assertThat(previewed).isEmpty()
    }

    @Test
    fun tappingASoundsRow_playsIt_andTogglesNothing() {
        val toggled = mutableListOf<Pair<String, Boolean>>()
        val previewed = mutableListOf<String>()
        setContent(onSoundToggle = { id, enabled -> toggled += id to enabled }, onSoundPreview = { previewed += it })

        composeRule.onNode(soundRow("Krypton")).assert(hasClickLabel("Play")).performClick()
        composeRule.scrollTo("Siren sweep")
        composeRule.onNode(soundRow("Siren sweep")).performClick()

        assertThat(previewed).containsExactly("system:2", AlarmSound.SIREN_ID)
        assertThat(toggled).isEmpty()
    }

    /** The sound playing reads "Playing" and offers "Stop"; the rest still offer "Play". */
    @Test
    fun theSoundPlaying_readsPlaying_andItsTapStopsIt() {
        val previewed = mutableListOf<String>()
        setContent(state = state.copy(previewing = "system:1"), onSoundPreview = { previewed += it })

        composeRule.onNode(soundRow("Cesium"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Playing"))
            .assert(hasClickLabel("Stop"))
            .performClick()
        composeRule.onNode(soundRow("Krypton"))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
            .assert(hasClickLabel("Play"))

        assertThat(previewed).containsExactly("system:1")
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
                    onSoundPreview = {},
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
        composeRule.onNode(soundCheckbox("Siren sweep")).performClick()

        assertThat(toggled).containsExactly(AlarmSound.SIREN_ID to false)
    }
}

private fun hasClickLabel(label: String) = SemanticsMatcher("click label is '$label'") {
    it.config.getOrNull(SemanticsActions.OnClick)?.label == label
}
