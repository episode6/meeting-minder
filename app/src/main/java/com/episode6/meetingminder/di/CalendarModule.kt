package com.episode6.meetingminder.di

import android.content.Context
import com.episode6.meetingminder.data.calendar.CalendarChangeSource
import com.episode6.meetingminder.data.calendar.CalendarRepository
import com.episode6.meetingminder.data.calendar.ContentResolverCalendarChangeSource
import com.episode6.meetingminder.data.calendar.ContentResolverCalendarRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/**
 * Binds the provider-backed [CalendarRepository] and [CalendarChangeSource]; tests
 * substitute `FakeCalendarRepository` and a fake change source.
 */
@ContributesTo(AppScope::class)
interface CalendarModule {
    @Provides
    @SingleIn(AppScope::class)
    fun calendarRepository(context: Context): CalendarRepository =
        ContentResolverCalendarRepository(context.contentResolver)

    @Provides
    @SingleIn(AppScope::class)
    fun calendarChangeSource(context: Context): CalendarChangeSource =
        ContentResolverCalendarChangeSource(context.contentResolver)
}
