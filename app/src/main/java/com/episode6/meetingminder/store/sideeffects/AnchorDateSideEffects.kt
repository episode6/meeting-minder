package com.episode6.meetingminder.store.sideeffects

import com.episode6.meetingminder.store.AppState
import com.episode6.meetingminder.store.SetAnchorDate
import com.episode6.redux.sideeffects.SideEffect
import com.episode6.redux.subscriberaware.SubscriberStatusChanged
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import java.time.Clock
import java.time.LocalDateTime

private const val MILLIS_PER_MINUTE = 60_000L

/**
 * Midnight rollover while the app is open (TODO.md §5 PR-13): keeps [AppState.anchorDate]
 * — "today" for the pager's Today action, the now-line and the change banner — equal to
 * the device's local date. The store lives as long as the process, which can be days, so
 * the date read at launch goes stale.
 *
 * While the store has subscribers (some UI is visible) it checks the date as the UI
 * appears and then at the top of every minute, dispatching [SetAnchorDate] whenever it
 * differs. A minute tick rather than one timer to midnight, because a wall-clock or
 * timezone change moves midnight and a coroutine `delay` runs on the monotonic clock; the
 * check is one date comparison. Nothing ticks while the app is in the background: the
 * next subscriber re-checks at once.
 */
@ContributesTo(AppScope::class)
interface AnchorDateSideEffects {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Provides @IntoSet
    fun anchorDate(clock: Clock): SideEffect<AppState> = sideEffect {
        actions
            .filterIsInstance<SubscriberStatusChanged>()
            .map { it.subscribersActive }
            .distinctUntilChanged()
            .flatMapLatest { active ->
                if (!active) {
                    emptyFlow()
                } else {
                    flow {
                        while (true) {
                            val now = LocalDateTime.now(clock)
                            emit(now.toLocalDate())
                            delay(MILLIS_PER_MINUTE - now.toLocalTime().toNanoOfDay() / 1_000_000 % MILLIS_PER_MINUTE)
                        }
                    }
                }
            }
            .mapNotNull { today -> SetAnchorDate(today).takeIf { today != currentState().anchorDate } }
    }
}
