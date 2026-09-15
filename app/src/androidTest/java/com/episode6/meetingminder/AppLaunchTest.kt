package com.episode6.meetingminder

import android.Manifest
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import assertk.assertThat
import assertk.assertions.startsWith
import com.episode6.meetingminder.ui.day.DAY_PAGER_TEST_TAG
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Smoke test: the app launches through the DI graph and navigation to the day view. Release, snapshot and debug builds each
 * carry their own applicationId (`com.episode6.meetingminder[.snapshot][.debug]`), so
 * the package assertion checks the shared prefix rather than one exact id. Calendar
 * access is pre-granted (PR-4 routes to Onboarding instead of Day without it); the
 * Onboarding routing itself is exercised by
 * [com.episode6.meetingminder.ui.navigation.NavigationViewModelTest] (the `calendarGranted`
 * the routing decision reads) and the Roborazzi previews rather than a second device test.
 */
@RunWith(AndroidJUnit4::class)
class AppLaunchTest {

    private val permissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(permissionRule).around(composeRule)

    @Test
    fun launches_toTheDayView() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertThat(context.packageName).startsWith("com.episode6.meetingminder")

        // one pager, but several composed pages each carrying the timeline tag
        composeRule.onNodeWithTag(DAY_PAGER_TEST_TAG).assertExists()
        composeRule.onNodeWithContentDescription(context.getString(R.string.day_today)).assertExists()
    }
}
