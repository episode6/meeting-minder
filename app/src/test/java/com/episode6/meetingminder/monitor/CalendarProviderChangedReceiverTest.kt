package com.episode6.meetingminder.monitor

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** The `PROVIDER_CHANGED` accelerator (TODO.md §4.3 mechanism 3), on WorkManager's test instance. */
@RunWith(RobolectricTestRunner::class)
class CalendarProviderChangedReceiverTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val component = ComponentName(context, CalendarProviderChangedReceiver::class.java)
    private val today = LocalDate.of(2026, 9, 14)
    private val clock = Clock.fixed(Instant.parse("2026-09-14T20:00:00Z"), ZoneOffset.UTC)

    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setMinimumLoggingLevel(Log.DEBUG).setExecutor(SynchronousExecutor()).build(),
        )
        workManager = WorkManager.getInstance(context)
    }

    private fun broadcastWorks(): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork(WorkManagerChangeWorkScheduler.BROADCAST_WORK).get()

    private fun providerChanged(authority: String = CalendarContract.AUTHORITY) =
        Intent(Intent.ACTION_PROVIDER_CHANGED, Uri.parse("content://$authority"))

    private fun receiverEnabled(): Boolean =
        context.packageManager.getComponentEnabledSetting(component) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    @Test
    fun calendarProviderChanged_enqueuesOneSettledChangeCheck() {
        CalendarProviderChangedReceiver().onReceive(context, providerChanged())
        // a sync's burst of broadcasts
        CalendarProviderChangedReceiver().onReceive(context, providerChanged())

        val work = broadcastWorks().single()
        assertThat(work.state).isEqualTo(WorkInfo.State.ENQUEUED)
        assertThat(work.initialDelayMillis).isEqualTo(Duration.ofSeconds(5).toMillis())
        assertThat(work.tags.contains(WorkManagerChangeWorkScheduler.WORK_TAG)).isTrue()
    }

    @Test
    fun otherProvidersAndActions_areIgnored() {
        CalendarProviderChangedReceiver().onReceive(context, providerChanged(authority = "com.example.other"))
        CalendarProviderChangedReceiver().onReceive(context, Intent("com.example.NOT_IT", Uri.parse("content://com.android.calendar")))

        assertThat(broadcastWorks()).isEmpty()
    }

    @Test
    fun theReceiver_isDeclaredDisabled_andExported_forTheCalendarProvider() {
        val info = context.packageManager.getReceiverInfo(component, PackageManager.MATCH_DISABLED_COMPONENTS)

        assertThat(info.enabled).isFalse()
        assertThat(info.exported).isTrue()
    }

    @Test
    fun theReceiver_isEnabledOnlyWhileADayIsShared() {
        val scheduler = WorkManagerChangeWorkScheduler(context, clock)

        scheduler.update(setOf(today), ChangeCheckReason.IN_APP)
        assertThat(receiverEnabled()).isTrue()

        scheduler.update(emptySet(), ChangeCheckReason.IN_APP)
        assertThat(receiverEnabled()).isFalse()
    }

    @Test
    fun disablingAFreshInstall_writesNothing_becauseTheManifestAlreadySaysDisabled() {
        CalendarProviderChangedReceiver.setEnabled(context, enabled = false)

        assertThat(context.packageManager.getComponentEnabledSetting(component))
            .isEqualTo(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
    }

    @Test
    fun disarming_cancelsAWaitingAcceleratorCheck_exceptFromThatCheckItself() {
        val scheduler = WorkManagerChangeWorkScheduler(context, clock)
        CalendarProviderChangedReceiver().onReceive(context, providerChanged())

        scheduler.update(emptySet(), ChangeCheckReason.PROVIDER_CHANGED)
        assertThat(broadcastWorks().single().state).isEqualTo(WorkInfo.State.ENQUEUED)

        scheduler.update(emptySet(), ChangeCheckReason.IN_APP)
        assertThat(broadcastWorks().single().state).isEqualTo(WorkInfo.State.CANCELLED)
    }
}
