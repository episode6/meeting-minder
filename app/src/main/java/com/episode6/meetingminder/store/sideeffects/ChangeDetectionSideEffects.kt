package com.episode6.meetingminder.store.sideeffects

import com.episode6.meetingminder.data.db.ChangeSnapshotDao
import com.episode6.meetingminder.data.db.decodeScheduleChanges
import com.episode6.meetingminder.monitor.ChangeCheckReason
import com.episode6.meetingminder.monitor.ChangeMonitor
import com.episode6.meetingminder.store.AppState
import com.episode6.meetingminder.store.CalendarContentChanged
import com.episode6.meetingminder.store.SetScheduleChanges
import com.episode6.redux.Action
import com.episode6.redux.sideeffects.SideEffect
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.transformLatest

/**
 * Change detection's foreground half (TODO.md §4.3). [observeScheduleChanges] streams every
 * shared day's recorded changes (`change_snapshot.changes_json`) into
 * [AppState.scheduleChanges] — observe-only, so it still subscribes to `actions` — which is
 * what the day view's "changed since you shared" banner shows, whether a check ran in the
 * background or here. [runChangeCheck] runs [ChangeMonitor.runCheck] on every
 * [CalendarContentChanged] (mechanism 1: the debounced `ContentObserver` while UI is visible,
 * and once each time it becomes visible), a newer change cancelling a check still running.
 */
@ContributesTo(AppScope::class)
interface ChangeDetectionSideEffects {
    @Provides @IntoSet
    fun observeScheduleChanges(dao: ChangeSnapshotDao): SideEffect<AppState> = sideEffect {
        merge(
            actions.filter { false },
            dao.observeAll().map { rows -> SetScheduleChanges(rows.flatMap { decodeScheduleChanges(it.date, it.changesJson) }) },
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Provides @IntoSet
    fun runChangeCheck(monitor: ChangeMonitor): SideEffect<AppState> = sideEffect {
        actions.filterIsInstance<CalendarContentChanged>().transformLatest<CalendarContentChanged, Action> {
            monitor.runCheck(ChangeCheckReason.IN_APP)
        }
    }
}
