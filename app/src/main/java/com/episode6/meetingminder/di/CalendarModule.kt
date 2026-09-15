package com.episode6.meetingminder.di

import android.content.Context
import com.episode6.meetingminder.data.calendar.CalendarChangeSource
import com.episode6.meetingminder.data.calendar.CalendarRepository
import com.episode6.meetingminder.data.calendar.CalendarSyncRequester
import com.episode6.meetingminder.data.calendar.ContentResolverCalendarChangeSource
import com.episode6.meetingminder.data.calendar.ContentResolverCalendarRepository
import com.episode6.meetingminder.data.calendar.ContentResolverCalendarSyncRequester
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/**
 * Binds the provider-backed [CalendarRepository], [CalendarChangeSource] and
 * [CalendarSyncRequester]; tests substitute `FakeCalendarRepository`, a fake change source
 * and a recording requester.
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

    @Provides
    @SingleIn(AppScope::class)
    fun calendarSyncRequester(): CalendarSyncRequester = ContentResolverCalendarSyncRequester()
}
