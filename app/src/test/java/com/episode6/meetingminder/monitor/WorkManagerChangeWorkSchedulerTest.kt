package com.episode6.meetingminder.monitor

import android.content.Context
import android.os.Looper
import android.provider.CalendarContract
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEqualTo
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** The three unique works behind background monitoring (TODO.md §4.3), on WorkManager's test driver. It is 20:00 UTC on [today]. */
@RunWith(RobolectricTestRunner::class)
class WorkManagerChangeWorkSchedulerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val today = LocalDate.of(2026, 9, 14)
    private val clock = Clock.fixed(Instant.parse("2026-09-14T20:00:00Z"), ZoneOffset.UTC)

    private lateinit var workManager: WorkManager
    private lateinit var scheduler: WorkManagerChangeWorkScheduler

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setMinimumLoggingLevel(Log.DEBUG).setExecutor(SynchronousExecutor()).build(),
        )
        workManager = WorkManager.getInstance(context)
        scheduler = WorkManagerChangeWorkScheduler(context, clock)
    }

    private fun works(name: String): List<WorkInfo> = workManager.getWorkInfosForUniqueWork(name).get()

    private fun work(name: String): WorkInfo = works(name).single()

    @Test
    fun update_withASharedDay_armsTheContentTrigger() {
        scheduler.update(setOf(today), ChangeCheckReason.IN_APP)

        val trigger = work(WorkManagerChangeWorkScheduler.TRIGGER_WORK)
        assertThat(trigger.state).isEqualTo(WorkInfo.State.ENQUEUED)
        assertThat(trigger.constraints.contentUriTriggers.map { it.uri to it.isTriggeredForDescendants })
            .containsExactly(CalendarContract.CONTENT_URI to true)
        assertThat(trigger.constraints.contentTriggerUpdateDelayMillis).isEqualTo(Duration.ofSeconds(5).toMillis())
        assertThat(trigger.constraints.contentTriggerMaxDelayMillis).isEqualTo(Duration.ofMinutes(1).toMillis())
    }

    @Test
    fun update_withASharedDay_armsThe30MinuteSafetyNet() {
        scheduler.update(setOf(today), ChangeCheckReason.IN_APP)

        val periodic = work(WorkManagerChangeWorkScheduler.PERIODIC_WORK)
        assertThat(periodic.state).isEqualTo(WorkInfo.State.ENQUEUED)
        assertThat(periodic.periodicityInfo!!.repeatIntervalMillis).isEqualTo(Duration.ofMinutes(30).toMillis())
        assertThat(periodic.periodicityInfo!!.flexIntervalMillis).isEqualTo(Duration.ofMinutes(10).toMillis())
    }

    @Test
    fun update_schedulesTheExpiryForTheMidnightThatEndsTheLastSharedDay() {
        scheduler.update(setOf(today), ChangeCheckReason.IN_APP)
        assertThat(work(WorkManagerChangeWorkScheduler.EXPIRY_WORK).initialDelayMillis).isEqualTo(Duration.ofHours(4).toMillis())

        scheduler.update(setOf(today, today.plusDays(2)), ChangeCheckReason.IN_APP)

        assertThat(works(WorkManagerChangeWorkScheduler.EXPIRY_WORK).filter { !it.state.isFinished }.single().initialDelayMillis)
            .isEqualTo(Duration.ofHours(52).toMillis())
    }

    @Test
    fun update_fromABackgroundCheck_keepsTheExpiryThatIsAlreadyWaiting() {
        scheduler.update(setOf(today), ChangeCheckReason.IN_APP)
        val expiry = work(WorkManagerChangeWorkScheduler.EXPIRY_WORK).id

        scheduler.update(setOf(today), ChangeCheckReason.PERIODIC)
        scheduler.update(setOf(today), ChangeCheckReason.CONTENT_TRIGGER)

        assertThat(work(WorkManagerChangeWorkScheduler.EXPIRY_WORK).id).isEqualTo(expiry)
    }

    @Test
    fun update_fromTheApp_keepsATriggerThatIsAlreadyWaiting() {
        scheduler.update(setOf(today), ChangeCheckReason.IN_APP)
        val first = work(WorkManagerChangeWorkScheduler.TRIGGER_WORK).id

        scheduler.update(setOf(today), ChangeCheckReason.IN_APP)
        scheduler.update(setOf(today), ChangeCheckReason.PERIODIC)

        assertThat(work(WorkManagerChangeWorkScheduler.TRIGGER_WORK).id).isEqualTo(first)
    }

    @Test
    fun update_fromTheTriggerRun_appendsTheNextTriggerBehindItself() {
        scheduler.update(setOf(today), ChangeCheckReason.IN_APP)

        scheduler.update(setOf(today), ChangeCheckReason.CONTENT_TRIGGER)

        assertThat(works(WorkManagerChangeWorkScheduler.TRIGGER_WORK).map { it.state }.sorted())
            .containsExactly(WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED)
    }

    @Test
    fun theTrigger_whenTheCalendarChanges_runsTheCheck_whichReArmsIt() {
        // the check runs against the real graph, which has nothing shared: it disarms
        // everything except the trigger that is running it, which just finishes
        scheduler.update(setOf(today), ChangeCheckReason.IN_APP)
        val trigger = work(WorkManagerChangeWorkScheduler.TRIGGER_WORK).id

        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(trigger)

        assertThat(awaitFinished(trigger).state).isEqualTo(WorkInfo.State.SUCCEEDED)
        assertThat(work(WorkManagerChangeWorkScheduler.PERIODIC_WORK).state).isEqualTo(WorkInfo.State.CANCELLED)
        assertThat(work(WorkManagerChangeWorkScheduler.EXPIRY_WORK).state).isEqualTo(WorkInfo.State.CANCELLED)
    }

    @Test
    fun update_withNothingShared_cancelsEveryWork() {
        scheduler.update(setOf(today), ChangeCheckReason.IN_APP)

        scheduler.update(emptySet(), ChangeCheckReason.IN_APP)

        assertThat(work(WorkManagerChangeWorkScheduler.TRIGGER_WORK).state).isEqualTo(WorkInfo.State.CANCELLED)
        assertThat(work(WorkManagerChangeWorkScheduler.PERIODIC_WORK).state).isEqualTo(WorkInfo.State.CANCELLED)
        assertThat(work(WorkManagerChangeWorkScheduler.EXPIRY_WORK).state).isEqualTo(WorkInfo.State.CANCELLED)
    }

    @Test
    fun update_withNothingShared_neverCancelsTheOneTimeWorkThatIsAsking() {
        scheduler.update(setOf(today), ChangeCheckReason.IN_APP)

        scheduler.update(emptySet(), ChangeCheckReason.CONTENT_TRIGGER)
        assertThat(work(WorkManagerChangeWorkScheduler.TRIGGER_WORK).state).isNotEqualTo(WorkInfo.State.CANCELLED)
        assertThat(work(WorkManagerChangeWorkScheduler.EXPIRY_WORK).state).isEqualTo(WorkInfo.State.CANCELLED)

        scheduler.update(setOf(today), ChangeCheckReason.IN_APP)
        scheduler.update(emptySet(), ChangeCheckReason.DAY_ENDED)
        assertThat(works(WorkManagerChangeWorkScheduler.EXPIRY_WORK).last().state).isNotEqualTo(WorkInfo.State.CANCELLED)
        assertThat(work(WorkManagerChangeWorkScheduler.PERIODIC_WORK).state).isEqualTo(WorkInfo.State.CANCELLED)
    }

    /** A `CoroutineWorker` finishes off the test executor (and starts on the main looper), so wait for it. */
    private fun awaitFinished(id: UUID): WorkInfo {
        repeat(400) {
            shadowOf(Looper.getMainLooper()).idle()
            val info = workManager.getWorkInfoById(id).get()!!
            if (info.state.isFinished) return info
            Thread.sleep(10)
        }
        error("work $id never finished")
    }
}
