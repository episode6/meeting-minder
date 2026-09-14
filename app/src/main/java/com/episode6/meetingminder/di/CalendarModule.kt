package com.episode6.meetingminder.di

import android.content.Context
import com.episode6.meetingminder.data.calendar.CalendarRepository
import com.episode6.meetingminder.data.calendar.ContentResolverCalendarRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/** Binds the provider-backed [CalendarRepository]; tests substitute `FakeCalendarRepository`. */
@ContributesTo(AppScope::class)
interface CalendarModule {
    @Provides
    @SingleIn(AppScope::class)
    fun calendarRepository(context: Context): CalendarRepository =
        ContentResolverCalendarRepository(context.contentResolver)
}
