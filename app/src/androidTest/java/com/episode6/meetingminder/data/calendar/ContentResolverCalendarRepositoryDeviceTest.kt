package com.episode6.meetingminder.data.calendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import assertk.all
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import assertk.assertions.prop
import assertk.assertions.single
import com.episode6.meetingminder.model.CalendarEvent
import com.episode6.meetingminder.model.EventKey
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The one test against the real Calendar Provider (TODO.md §3.6): a `LOCAL` calendar and an
 * event are inserted as a sync adapter, the provider expands the instance, and the
 * repository must find it on its day. The calendar is deleted afterwards, which cascades
 * to its events.
 */
@RunWith(AndroidJUnit4::class)
class ContentResolverCalendarRepositoryDeviceTest {

    @get:Rule
    val calendarPermissions: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = targetContext.contentResolver
    private val zone: ZoneId = ZoneId.systemDefault()
    private val account = "meeting-minder-test@local"
    private val repository = ContentResolverCalendarRepository(resolver, packageName = targetContext.packageName, zone = { zone })

    @Test
    fun findsAnEventInsertedIntoTheProvider() = runBlocking {
        val calendarUri = resolver.insert(
            Calendars.CONTENT_URI.asSyncAdapter(),
            ContentValues().apply {
                put(Calendars.ACCOUNT_NAME, account)
                put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
                put(Calendars.NAME, "meeting-minder-test")
                put(Calendars.CALENDAR_DISPLAY_NAME, "Meeting Minder test")
                put(Calendars.CALENDAR_COLOR, 0xFF3F51B5.toInt())
                put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
                put(Calendars.OWNER_ACCOUNT, account)
                put(Calendars.VISIBLE, 1)
                put(Calendars.SYNC_EVENTS, 1)
                put(Calendars.CALENDAR_TIME_ZONE, zone.id)
            },
        ) ?: error("calendar insert returned null")
        val calendarId = ContentUris.parseId(calendarUri)
        try {
            // a few days out so a stray event on the device today can't collide
            val date = LocalDate.now(zone).plusDays(3)
            val begin = date.atTime(10, 0).atZone(zone).toInstant()
            val end = date.atTime(11, 0).atZone(zone).toInstant()
            val eventUri = resolver.insert(
                Events.CONTENT_URI,
                ContentValues().apply {
                    put(Events.CALENDAR_ID, calendarId)
                    put(Events.TITLE, "Meeting Minder device test")
                    put(Events.DTSTART, begin.toEpochMilli())
                    put(Events.DTEND, end.toEpochMilli())
                    put(Events.EVENT_TIMEZONE, zone.id)
                },
            ) ?: error("event insert returned null")
            val eventId = ContentUris.parseId(eventUri)

            val events = repository.eventsOn(date, CalendarFilter.Only(setOf(calendarId)))

            assertThat(events).single().all {
                prop(CalendarEvent::eventId).isEqualTo(eventId)
                prop(CalendarEvent::key).isEqualTo(EventKey(eventId, 0))
                prop(CalendarEvent::calendarId).isEqualTo(calendarId)
                prop(CalendarEvent::title).isEqualTo("Meeting Minder device test")
                prop(CalendarEvent::begin).isEqualTo(begin)
                prop(CalendarEvent::end).isEqualTo(end)
                prop(CalendarEvent::allDay).isFalse()
                prop(CalendarEvent::calendarAccessLevel).isEqualTo(Calendars.CAL_ACCESS_OWNER)
            }
            assertThat(events.single().isMeeting).isFalse()
            assertThat(repository.calendars().any { it.id == calendarId }).isTrue()
        } finally {
            resolver.delete(ContentUris.withAppendedId(Calendars.CONTENT_URI, calendarId).asSyncAdapter(), null, null)
        }
    }

    private fun Uri.asSyncAdapter(): Uri = buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(Calendars.ACCOUNT_NAME, account)
        .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
        .build()
}
