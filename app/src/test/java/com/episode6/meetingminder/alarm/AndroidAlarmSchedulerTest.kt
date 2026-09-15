package com.episode6.meetingminder.alarm

import android.app.AlarmManager
import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import com.episode6.meetingminder.MainActivity
import com.episode6.meetingminder.data.db.ScheduledAlarmEntity
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager
import java.time.LocalDate

/**
 * [AndroidAlarmScheduler] against `ShadowAlarmManager`: `setAlarmClock` was used (the
 * scheduled alarm carries a `showIntent`), the `PendingIntent` identity comes from the
 * alarm id, so re-scheduling replaces and cancelling removes exactly that alarm.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidAlarmSchedulerTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val scheduler = AndroidAlarmScheduler(context)
    private val today = LocalDate.of(2026, 9, 14)

    private fun alarm(id: Long, fireAt: Long = 1_000_000L) = ScheduledAlarmEntity(
        alarmId = id,
        date = today,
        eventId = id,
        instanceTime = 0,
        fireAt = fireAt,
        title = "Standup",
        beginMillis = fireAt + 300_000,
        endMillis = fireAt + 2_100_000,
        soundIndex = 3,
    )

    @After
    fun tearDown() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @Test
    fun schedule_usesSetAlarmClock_atTheFireTime() {
        assertThat(scheduler.schedule(alarm(1))).isTrue()

        val scheduled = shadowOf(alarmManager).scheduledAlarms.single()
        assertThat(scheduled.triggerAtTime).isEqualTo(1_000_000L)
        assertThat(scheduled.type).isEqualTo(AlarmManager.RTC_WAKEUP)
        // only setAlarmClock carries a show intent; it opens the day view
        assertThat(scheduled.showIntent).isNotNull()
        val show = shadowOf(scheduled.showIntent).savedIntent
        assertThat(show.component?.className).isEqualTo(MainActivity::class.java.name)
        assertThat(show.data).isEqualTo(AlarmUris.day(today))
        // the operation targets AlarmReceiver with the alarm's identity in its data
        val operation = shadowOf(scheduled.operation).savedIntent
        assertThat(operation.component?.className).isEqualTo(AlarmReceiver::class.java.name)
        assertThat(operation.action).isEqualTo(AlarmReceiver.ACTION_FIRE)
        assertThat(operation.data).isEqualTo(AlarmUris.alarm(1))
        assertThat(shadowOf(scheduled.operation).requestCode).isEqualTo(1)
    }

    @Test
    fun schedulingTheSameAlarmIdAgain_replacesInsteadOfAdding() {
        scheduler.schedule(alarm(1, fireAt = 1_000_000L))
        scheduler.schedule(alarm(1, fireAt = 2_000_000L))

        val scheduled = shadowOf(alarmManager).scheduledAlarms
        assertThat(scheduled).hasSize(1)
        assertThat(scheduled.single().triggerAtTime).isEqualTo(2_000_000L)
    }

    @Test
    fun differentAlarmIds_areDistinctAlarms() {
        scheduler.schedule(alarm(1))
        scheduler.schedule(alarm(2))

        assertThat(shadowOf(alarmManager).scheduledAlarms).hasSize(2)
    }

    @Test
    fun cancel_removesOnlyThatAlarm() {
        scheduler.schedule(alarm(1))
        scheduler.schedule(alarm(2))

        scheduler.cancel(1)

        val remaining = shadowOf(alarmManager).scheduledAlarms.single()
        assertThat(shadowOf(remaining.operation).savedIntent.data).isEqualTo(AlarmUris.alarm(2))
    }

    @Test
    fun cancel_ofAnAlarmNeverScheduled_isANoOp() {
        scheduler.schedule(alarm(1))

        scheduler.cancel(99)

        assertThat(shadowOf(alarmManager).scheduledAlarms).hasSize(1)
    }

    @Test
    fun canScheduleExactAlarms_followsTheSystem() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertThat(scheduler.canScheduleExactAlarms()).isFalse()

        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        assertThat(scheduler.canScheduleExactAlarms()).isTrue()
    }

    @Test
    fun fireIntent_resolvesToTheManifestReceiver() {
        val intent = AlarmReceiver.fireIntent(context, 7)

        val receivers = context.packageManager.queryBroadcastReceivers(intent, 0)

        assertThat(receivers.map { it.activityInfo.name }).isEqualTo(listOf(AlarmReceiver::class.java.name))
        assertThat(intent.data?.lastPathSegment).isEqualTo("7")
    }

    @Test
    fun bootReceiver_isRegisteredForEveryRearmBroadcast() {
        for (action in BootReceiver.HANDLED_ACTIONS) {
            val receivers = context.packageManager.queryBroadcastReceivers(Intent(action).setPackage(context.packageName), 0)
            assertThat(receivers.map { it.activityInfo.name }, action).isEqualTo(listOf(BootReceiver::class.java.name))
        }
        val unrelated = context.packageManager.queryBroadcastReceivers(Intent(Intent.ACTION_SCREEN_ON).setPackage(context.packageName), 0)
        assertThat(unrelated).isEmpty()
    }
}
