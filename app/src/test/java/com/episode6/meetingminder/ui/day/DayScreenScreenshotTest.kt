package com.episode6.meetingminder.ui.day

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.episode6.meetingminder.ui.theme.MeetingMinderTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi screenshot tests. Reference PNGs live in `src/test/screenshots/` and must be
 * recorded inside the CI image (`recordRoborazziDebug`), because font rendering differs
 * between machines; CI runs `verifyRoborazziDebug`. A plain `test`/`check` renders the
 * screens but neither records nor compares.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = RobolectricDeviceQualifiers.Pixel7)
class DayScreenScreenshotTest {

    @Test
    fun emptyDay() {
        captureRoboImage("src/test/screenshots/DayScreen_empty.png") {
            MeetingMinderTheme(darkTheme = false) {
                DayScreen(
                    state = DayUiState(date = PreviewDate, isToday = true),
                    onTodayClick = {},
                    onPermissionsClick = {},
                    onSettingsClick = {},
                    onLicensesClick = {},
                    onCheckForUpdatesClick = {},
                )
            }
        }
    }
}
