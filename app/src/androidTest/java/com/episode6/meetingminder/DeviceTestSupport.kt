package com.episode6.meetingminder

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource

/** Runs [command] as the shell user and returns its output, waiting for it to finish. */
fun shell(command: String): String =
    ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command))
        .bufferedReader()
        .use { it.readText() }

/**
 * Allows `USE_FULL_SCREEN_INTENT` for the app under test. A sideloaded install has it by
 * default, but "Full-screen alarms" is a required onboarding row, so launch routing (and
 * the ringing screen) depend on it: pin it rather than trust the default. Special access,
 * so `GrantPermissionRule` can't do it.
 */
class FullScreenIntentRule : ExternalResource() {
    override fun before() {
        val packageName = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        shell("appops set $packageName USE_FULL_SCREEN_INTENT allow")
    }
}
