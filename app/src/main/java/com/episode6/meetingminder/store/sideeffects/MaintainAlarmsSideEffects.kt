package com.episode6.meetingminder.store.sideeffects

import android.util.Log
import com.episode6.meetingminder.alarm.AlarmMaintainer
import com.episode6.meetingminder.store.AppState
import com.episode6.meetingminder.store.CalendarContentChanged
import com.episode6.redux.Action
import com.episode6.redux.sideeffects.SideEffect
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.transformLatest

/**
 * The in-app trigger of the automatic alarm reconcile (TODO.md §4.4 `MaintainAlarms`): on
 * every [CalendarContentChanged] — the debounced foreground `ContentObserver`, and each time
 * the UI becomes visible — [AlarmMaintainer.maintain] re-times armed alarms whose meetings
 * moved and cancels those now declined. `BootReceiver` runs the same thing after boot and
 * clock/timezone changes, and `monitor/CalendarChangeWorker` from every background check. Nothing is emitted: the rows stream back through
 * `ObserveDayPlansSideEffects`. A newer change cancels a run still reading the provider.
 */
@ContributesTo(AppScope::class)
interface MaintainAlarmsSideEffects {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Provides @IntoSet
    fun maintainAlarms(maintainer: AlarmMaintainer): SideEffect<AppState> = sideEffect {
        actions.filterIsInstance<CalendarContentChanged>().transformLatest<CalendarContentChanged, Action> {
            try {
                maintainer.maintain()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("MeetingMinderAlarms", "alarm maintenance failed", e)
            }
        }
    }
}
