package com.episode6.meetingminder.store.sideeffects

import com.episode6.meetingminder.data.db.DayPlanDao
import com.episode6.meetingminder.data.db.buildDayPlans
import com.episode6.meetingminder.store.AppState
import com.episode6.meetingminder.store.SetDayPlans
import com.episode6.redux.sideeffects.SideEffect
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.merge

/**
 * Room is the source of truth for selections (TODO.md §3.2): streams `day_plan` +
 * `selected_event` into [AppState.dayPlans] on every change, forever (there is no
 * `LoadDay`-style gate — the tables are tiny and every screen may need any date). This is
 * an observe-only effect, so per the [sideEffect] gotcha it still subscribes to `actions`
 * (`merge(actions.filter { false }, …)`) or every other effect would starve.
 */
@ContributesTo(AppScope::class)
interface ObserveDayPlansSideEffects {
    @Provides @IntoSet
    fun observeDayPlans(dao: DayPlanDao): SideEffect<AppState> = sideEffect {
        merge(
            actions.filter { false },
            combine(dao.observeDayPlans(), dao.observeSelectedEvents()) { plans, selections ->
                SetDayPlans(buildDayPlans(plans, selections))
            },
        )
    }
}
