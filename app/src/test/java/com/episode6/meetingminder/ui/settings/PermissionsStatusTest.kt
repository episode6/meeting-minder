package com.episode6.meetingminder.ui.settings

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.episode6.meetingminder.permissions.PermissionState
import org.junit.Test

class PermissionsStatusTest {

    private val allRequired = PermissionState(
        calendarGranted = true,
        notificationsGranted = true,
        exactAlarmsGranted = true,
        fullScreenIntentGranted = true,
    )

    @Test
    fun everythingRequiredGranted_isAllGranted_whatever_theBatteryOptimisationRowSays() {
        assertThat(allRequired.toStatus()).isEqualTo(PermissionsStatus.AllGranted)
        assertThat(allRequired.copy(ignoringBatteryOptimizations = true).toStatus()).isEqualTo(PermissionsStatus.AllGranted)
    }

    @Test
    fun backgroundRestricted_isReportedOnceEverythingRequiredIsGranted() {
        assertThat(allRequired.copy(backgroundRestricted = true).toStatus()).isEqualTo(PermissionsStatus.BackgroundRestricted)
    }

    @Test
    fun aMissingRequiredGrant_winsOverTheRestrictedWarning() {
        assertThat(allRequired.copy(notificationsGranted = false, backgroundRestricted = true).toStatus())
            .isEqualTo(PermissionsStatus.MissingSome(1))
    }
}
