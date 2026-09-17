package com.episode6.meetingminder.store.sideeffects

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import com.episode6.meetingminder.R
import com.episode6.meetingminder.data.calendar.CalendarSyncRequester
import com.episode6.meetingminder.data.calendar.FakeCalendarRepository
import com.episode6.meetingminder.model.CalendarInfo
import com.episode6.meetingminder.store.CalendarContentChanged
import com.episode6.meetingminder.store.EnableCalendarSync
import com.episode6.meetingminder.store.PermissionsMaybeChanged
import com.episode6.meetingminder.store.ShowMessage
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test

class EnableCalendarSyncSideEffectsTest {

    private val notSyncing = CalendarInfo(
        id = 3,
        accountName = "me@example.com",
        accountType = "com.google",
        displayName = "New",
        color = 0,
        visible = false,
        syncEvents = false,
        ownerAccount = "me@example.com",
        isPrimary = false,
        accessLevel = 700,
        canOrganizerRespond = false,
    )
    private val repository = FakeCalendarRepository(calendars = listOf(notSyncing))
    private var syncRequests = 0
    private val syncRequester = CalendarSyncRequester { syncRequests++ }

    private fun enableCalendarSync() = object : EnableCalendarSyncSideEffects {}.enableCalendarSync(repository, syncRequester)

    @Test
    fun turnsSyncOn_requestsASync_andReloads() = runTest {
        val output = enableCalendarSync().output(EnableCalendarSync(notSyncing.id)).toList()

        assertThat(repository.syncEnabledCalendarIds).containsExactly(notSyncing.id)
        assertThat(repository.calendars.single().syncEvents).isTrue()
        assertThat(syncRequests).isEqualTo(1)
        assertThat(output).containsExactly(CalendarContentChanged)
    }

    @Test
    fun aCalendarThatIsGone_isASnackbar_andNoSync() = runTest {
        val output = enableCalendarSync().output(EnableCalendarSync(99)).toList()

        assertThat(syncRequests).isEqualTo(0)
        assertThat((output.single() as ShowMessage).message.text).isEqualTo(R.string.busy_sync_enable_calendar_failed)
    }

    @Test
    fun aRefusedWrite_isASnackbar_andTheEffectSurvivesForTheNextPick() = runTest {
        repository.error = IllegalStateException("provider refused")
        val effect = enableCalendarSync()

        val output = effect.output(EnableCalendarSync(notSyncing.id), EnableCalendarSync(notSyncing.id)).toList()

        assertThat(output.map { (it as ShowMessage).message.text })
            .containsExactly(R.string.busy_sync_enable_calendar_failed, R.string.busy_sync_enable_calendar_failed)
    }

    @Test
    fun lostCalendarAccess_reChecksPermissions() = runTest {
        repository.error = SecurityException("revoked")

        val output = enableCalendarSync().output(EnableCalendarSync(notSyncing.id)).toList()

        assertThat(output).containsExactly(PermissionsMaybeChanged)
    }
}
