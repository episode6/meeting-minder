package com.episode6.meetingminder.store.sideeffects

import assertk.assertThat
import assertk.assertions.containsExactly
import com.episode6.meetingminder.permissions.PermissionChecker
import com.episode6.meetingminder.permissions.PermissionState
import com.episode6.meetingminder.store.PermissionsMaybeChanged
import com.episode6.meetingminder.store.SetPermissions
import com.episode6.meetingminder.store.ShowMessage
import com.episode6.meetingminder.store.UiMessage
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PermissionsSideEffectsTest {

    private class FakePermissionChecker(private val state: PermissionState) : PermissionChecker {
        override fun currentState(): PermissionState = state
    }

    private val sideEffects = object : PermissionsSideEffects {}

    @Test
    fun permissionsMaybeChanged_emitsTheFreshlyCheckedState() = runTest {
        val checker = FakePermissionChecker(PermissionState(calendarGranted = true))

        val output = sideEffects.permissions(checker).output(PermissionsMaybeChanged).toList()

        assertThat(output).containsExactly(SetPermissions(PermissionState(calendarGranted = true)))
    }

    @Test
    fun otherActions_areIgnored() = runTest {
        val checker = FakePermissionChecker(PermissionState(calendarGranted = true))

        val output = sideEffects.permissions(checker).output(ShowMessage(UiMessage(id = 1, text = 1))).toList()

        assertThat(output).containsExactly()
    }
}
