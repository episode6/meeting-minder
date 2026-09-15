package com.episode6.meetingminder.store.sideeffects

import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import com.episode6.meetingminder.R
import com.episode6.meetingminder.alarm.FakeAlarmScheduler
import com.episode6.meetingminder.data.db.AlarmState
import com.episode6.meetingminder.data.db.FakeScheduledAlarmDao
import com.episode6.meetingminder.model.TEST_ALARM_EVENT_ID
import com.episode6.meetingminder.store.PermissionsMaybeChanged
import com.episode6.meetingminder.store.ShowMessage
import com.episode6.meetingminder.store.TestAlarm
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.random.Random

/** [TestAlarmSideEffects] over fakes; a real `Context` (Robolectric) resolves the row's title. */
@RunWith(RobolectricTestRunner::class)
class TestAlarmSideEffectsTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val now = Instant.parse("2026-09-14T08:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val scheduler = FakeAlarmScheduler()
    private val alarmDao = FakeScheduledAlarmDao()

    private fun sideEffect() =
        object : TestAlarmSideEffects {}.testAlarm(context, alarmDao, scheduler, clock, Random(seed = 1))

    /** The one [ShowMessage] in an output (the rest, if any, are other actions). */
    private val List<com.episode6.redux.Action>.message get() = filterIsInstance<ShowMessage>().single().message

    @Test
    fun testAlarm_armsARowTenSecondsOut_independentOfAnySelection() = runTest {
        val output = sideEffect().output(TestAlarm).toList()

        val row = alarmDao.rows.getValue(1)
        assertThat(row.date).isEqualTo(LocalDate.of(2026, 9, 14))
        assertThat(row.eventId).isEqualTo(TEST_ALARM_EVENT_ID)
        assertThat(row.fireAt).isEqualTo(now.plus(TestAlarmDelay).toEpochMilli())
        assertThat(row.title).isEqualTo(context.getString(R.string.settings_test_alarm_event_title))
        assertThat(scheduler.armed.values.toList()).containsExactly(row)
        assertThat(output.message.text).isEqualTo(R.string.settings_test_alarm_scheduled)
    }

    @Test
    fun withoutTheExactAlarmGrant_nothingIsArmed_andASnackbarAsksForTheGrant() = runTest {
        scheduler.canSchedule = false

        val output = sideEffect().output(TestAlarm).toList()

        assertThat(alarmDao.rows).isEmpty()
        assertThat(output.message.text).isEqualTo(R.string.alarms_exact_permission_missing)
        assertThat(output.last()).isEqualTo(PermissionsMaybeChanged)
    }

    @Test
    fun theOsRefusingToArmIt_cancelsTheRow_andAsksForAPermissionRecheck() = runTest {
        scheduler.refuse = true

        val output = sideEffect().output(TestAlarm).toList()

        assertThat(alarmDao.rows.getValue(1).state).isEqualTo(AlarmState.CANCELLED)
        assertThat(scheduler.armed).isEmpty()
        assertThat(output.message.text).isEqualTo(R.string.alarms_exact_permission_missing)
        assertThat(output.last()).isEqualTo(PermissionsMaybeChanged)
    }
}
