package com.episode6.meetingminder

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import assertk.assertThat
import assertk.assertions.startsWith
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke test: the app launches and renders. Release, snapshot and debug builds each
 * carry their own applicationId (`com.episode6.meetingminder[.snapshot][.debug]`), so
 * the package assertion checks the shared prefix rather than one exact id.
 */
@RunWith(AndroidJUnit4::class)
class AppLaunchTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun launches_withAppNameOnScreen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertThat(context.packageName).startsWith("com.episode6.meetingminder")

        val appName = context.getString(R.string.app_name)
        composeRule.onNodeWithText(appName).assertExists()
    }
}
