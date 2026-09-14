package com.episode6.meetingminder.store.sideeffects

import android.content.Context
import com.episode6.meetingminder.R
import com.episode6.meetingminder.alarm.AlarmScheduler
import com.episode6.meetingminder.data.db.AlarmState
import com.episode6.meetingminder.data.db.ScheduledAlarmDao
import com.episode6.meetingminder.data.db.ScheduledAlarmEntity
import com.episode6.meetingminder.store.AppState
import com.episode6.meetingminder.store.PermissionsMaybeChanged
import com.episode6.meetingminder.store.ShowMessage
import com.episode6.meetingminder.store.TestAlarm
import com.episode6.meetingminder.store.UiMessage
import com.episode6.redux.sideeffects.SideEffect
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import kotlin.random.Random

/** The `scheduled_alarm.event_id` a test alarm's row uses: never a real `Events._ID`, which is always positive. */
internal const val TEST_ALARM_EVENT_ID = -1L

/** How long before it rings (TODO.md §4.4: "a 'Test alarm' button that fires in 10 s"). */
internal val TestAlarmDelay: Duration = Duration.ofSeconds(10)

/**
 * Settings' "Test alarm" button (TODO.md §4.4/§5 PR-12): inserts and arms one
 * `scheduled_alarm` row ten seconds out, on the real path ([AlarmScheduler], so
 * `AlarmReceiver`/`AlarmRingingService`/`AlarmActivity` fire exactly as they would for a
 * real meeting), independent of the day's selection ([TEST_ALARM_EVENT_ID] never matches
 * a real event, so a later "Set alarms" reconcile for today simply cancels it like any
 * other alarm that fell out of the selection). Refuses with a snackbar (and a permission
 * re-check) the same way [SetAlarms] does when the exact-alarm grant is missing or the OS
 * refuses to arm it.
 */
@ContributesTo(AppScope::class)
interface TestAlarmSideEffects {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Provides @IntoSet
    fun testAlarm(
        context: Context,
        alarmDao: ScheduledAlarmDao,
        scheduler: AlarmScheduler,
        clock: Clock,
        random: Random,
    ): SideEffect<AppState> = sideEffect {
        actions.filterIsInstance<TestAlarm>().flatMapMerge {
            flow {
                if (!scheduler.canScheduleExactAlarms()) {
                    emit(ShowMessage(UiMessage.next(R.string.alarms_exact_permission_missing)))
                    emit(PermissionsMaybeChanged)
                    return@flow
                }
                val now = clock.instant()
                val fireAt = now.plus(TestAlarmDelay)
                val row = ScheduledAlarmEntity(
                    date = LocalDate.now(clock),
                    eventId = TEST_ALARM_EVENT_ID,
                    instanceTime = now.toEpochMilli(),
                    fireAt = fireAt.toEpochMilli(),
                    title = context.getString(R.string.settings_test_alarm_event_title),
                    beginMillis = fireAt.toEpochMilli(),
                    endMillis = fireAt.toEpochMilli(),
                    soundIndex = random.nextInt(Int.MAX_VALUE),
                )
                val alarmId = alarmDao.insert(row)
                if (scheduler.schedule(row.copy(alarmId = alarmId))) {
                    emit(ShowMessage(UiMessage.next(R.string.settings_test_alarm_scheduled)))
                } else {
                    alarmDao.setState(alarmId, AlarmState.CANCELLED)
                    emit(ShowMessage(UiMessage.next(R.string.alarms_exact_permission_missing)))
                    emit(PermissionsMaybeChanged)
                }
            }
        }
    }
}
