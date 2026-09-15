package com.episode6.meetingminder.data.calendar

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow

/** [CalendarChangeSource] driven by hand; [observers] stands in for registered `ContentObserver`s. */
class FakeCalendarChangeSource : CalendarChangeSource {
    private val changes = MutableSharedFlow<Unit>()

    /** How many collectors (registered observers) there are right now. */
    val observers: StateFlow<Int> get() = changes.subscriptionCount

    override fun changes(): Flow<Unit> = changes

    /** Delivers one provider change notification to every current collector. */
    suspend fun notifyChange() = changes.emit(Unit)
}
