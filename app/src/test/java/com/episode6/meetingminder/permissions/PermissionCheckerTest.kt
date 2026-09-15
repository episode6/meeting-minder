package com.episode6.meetingminder.permissions

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class PermissionCheckerTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val checker = AndroidPermissionChecker(context)

    @Test
    fun neitherGranted_reportsCalendarNotGranted() {
        assertThat(checker.currentState()).isEqualTo(PermissionState(calendarGranted = false))
    }

    @Test
    fun onlyReadGranted_stillReportsCalendarNotGranted() {
        shadowOf(context).grantPermissions(Manifest.permission.READ_CALENDAR)

        assertThat(checker.currentState()).isEqualTo(PermissionState(calendarGranted = false))
    }

    @Test
    fun bothReadAndWriteGranted_reportsCalendarGranted() {
        shadowOf(context).grantPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

        assertThat(checker.currentState()).isEqualTo(PermissionState(calendarGranted = true))
    }
}
