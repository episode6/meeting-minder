package com.episode6.meetingminder.store.sideeffects

import com.episode6.meetingminder.data.settings.TodayShownLog
import com.episode6.meetingminder.store.AppState
import com.episode6.meetingminder.store.SetSettledDate
import com.episode6.redux.Action
import com.episode6.redux.sideeffects.SideEffect
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow

/**
 * The day pager settling on today ([SetSettledDate]) is what spends the day's "open on today"
 * jump ([TodayShownLog]): only a page the user actually had in front of them counts, whether
 * the jump, a swipe or the Today action put it there. Emits nothing.
 */
@ContributesTo(AppScope::class)
interface TodayShownSideEffects {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Provides @IntoSet
    fun todayShown(log: TodayShownLog): SideEffect<AppState> = sideEffect {
        actions.filterIsInstance<SetSettledDate>().flatMapMerge { settled ->
            flow<Action> { log.onPageSettled(settled.date) }
        }
    }
}
