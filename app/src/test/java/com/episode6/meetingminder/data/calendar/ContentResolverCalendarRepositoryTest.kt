package com.episode6.meetingminder.data.calendar

import android.content.Context
import android.provider.CalendarContract
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import androidx.test.core.app.ApplicationProvider
import assertk.all
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import assertk.assertions.prop
import assertk.assertions.single
import com.episode6.meetingminder.model.Availability
import com.episode6.meetingminder.model.CalendarEvent
import com.episode6.meetingminder.model.CalendarInfo
import com.episode6.meetingminder.model.EventKey
import com.episode6.meetingminder.model.EventStatus
import com.episode6.meetingminder.model.SelfStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Drives [ContentResolverCalendarRepository] against [FakeCalendarProvider] registered for
 * `com.android.calendar`. The device zone is pinned to New York (UTC-4 in September) because
 * that is where the all-day-at-UTC-midnight gotcha bites (TODO.md §4.1).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ContentResolverCalendarRepositoryTest {

    private val zone: ZoneId = ZoneId.of("America/New_York")
    private val today: LocalDate = LocalDate.of(2026, 9, 14)
    private val tomorrow: LocalDate = today.plusDays(1)
    private val me = "me@example.com"

    private lateinit var provider: FakeCalendarProvider
    private lateinit var repository: ContentResolverCalendarRepository

    @Before
    fun setUp() {
        provider = Robolectric.setupContentProvider(FakeCalendarProvider::class.java, CalendarContract.AUTHORITY)
        provider.addCalendar(id = 1, ownerAccount = me)
        repository = ContentResolverCalendarRepository(
            contentResolver = ApplicationProvider.getApplicationContext<Context>().contentResolver,
            zone = { zone },
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    @Test
    fun timedEvent_mapsEveryColumn() = runTest {
        provider.addInstance(
            instanceId = 100, eventId = 10, begin = today.at(9, zone = zone), end = today.at(9, 30, zone), zone = zone,
            title = "Daily standup", location = "Room 4", selfStatus = Attendees.ATTENDEE_STATUS_ACCEPTED,
            displayColor = 0xFF00FF00.toInt(), organizer = "boss@example.com", hasAttendeeData = true,
        )
        provider.addAttendee(id = 1, eventId = 10, email = me)
        provider.addAttendee(id = 2, eventId = 10, email = "boss@example.com", relationship = Attendees.RELATIONSHIP_ORGANIZER)
        provider.addAttendee(id = 3, eventId = 10, email = "room4@resource.calendar.google.com", type = Attendees.TYPE_RESOURCE)

        val events = repository.eventsOn(today)

        assertThat(events).containsExactly(
            CalendarEvent(
                key = EventKey(10, 0),
                eventId = 10,
                calendarId = 1,
                title = "Daily standup",
                location = "Room 4",
                begin = Instant.ofEpochMilli(today.at(9, zone = zone)),
                end = Instant.ofEpochMilli(today.at(9, 30, zone)),
                allDay = false,
                color = 0xFF00FF00.toInt(),
                selfStatus = SelfStatus.ACCEPTED,
                status = EventStatus.CONFIRMED,
                isOrganizer = false,
                hasAttendeeData = true,
                humanAttendees = 2,
                availability = Availability.BUSY,
                selfAttendeeId = 1,
                isRecurringInstance = false,
                calendarAccessLevel = Calendars.CAL_ACCESS_OWNER,
            ),
        )
        assertThat(events.single().isMeeting).isTrue()
    }

    @Test
    fun recurringInstance_isKeyedBySeriesIdAndBegin() = runTest {
        val begin = today.at(10, zone = zone)
        provider.addInstance(
            instanceId = 200, eventId = 20, begin = begin, end = today.at(11, zone = zone), zone = zone,
            rrule = "FREQ=WEEKLY;BYDAY=MO",
        )

        val event = repository.eventsOn(today).single()

        assertThat(event.key).isEqualTo(EventKey(20, begin))
        assertThat(event.eventId).isEqualTo(20)
        assertThat(event.isRecurringInstance).isTrue()
    }

    @Test
    fun movedOccurrence_keepsTheSeriesKeyButCarriesItsOwnEventId() = runTest {
        // the 10:00 occurrence of series 20 was dragged to 10:30 in Google, which created
        // exception event 21 with ORIGINAL_ID = 20 and ORIGINAL_INSTANCE_TIME = the old start
        val originalBegin = today.at(10, zone = zone)
        provider.addInstance(
            instanceId = 201, eventId = 21, begin = today.at(10, 30, zone), end = today.at(11, 30, zone), zone = zone,
            originalId = 20, originalInstanceTime = originalBegin,
        )

        val event = repository.eventsOn(today).single()

        assertThat(event.key).isEqualTo(EventKey(20, originalBegin))
        assertThat(event.eventId).isEqualTo(21)
        assertThat(event.begin).isEqualTo(Instant.ofEpochMilli(today.at(10, 30, zone)))
        assertThat(event.isRecurringInstance).isFalse()
    }

    @Test
    fun allDayEvent_belongsOnlyToItsOwnDate_inANegativeOffsetZone() = runTest {
        // stored as UTC midnight, which is 8 PM the evening before in New York
        provider.addInstance(
            instanceId = 300, eventId = 30, begin = tomorrow.utcMidnight(), end = tomorrow.plusDays(1).utcMidnight(),
            zone = zone, allDay = true, title = "Company holiday",
        )

        assertThat(repository.eventsOn(today)).isEmpty()
        assertThat(repository.eventsOn(tomorrow)).single().all {
            prop(CalendarEvent::title).isEqualTo("Company holiday")
            prop(CalendarEvent::allDay).isTrue()
            prop(CalendarEvent::isMeeting).isFalse()
        }
    }

    @Test
    fun eventEndingAtMidnight_doesNotBelongToTheNextDay() = runTest {
        provider.addInstance(
            instanceId = 400, eventId = 40, begin = today.at(23, zone = zone), end = tomorrow.at(0, zone = zone), zone = zone,
        )

        assertThat(repository.eventsOn(today)).single().prop(CalendarEvent::eventId).isEqualTo(40)
        assertThat(repository.eventsOn(tomorrow)).isEmpty()
    }

    @Test
    fun midnightSpanningEvent_appearsOnBothDays_withItsTrueTimes() = runTest {
        provider.addInstance(
            instanceId = 500, eventId = 50, begin = today.at(22, zone = zone), end = tomorrow.at(2, zone = zone), zone = zone,
        )

        assertThat(repository.eventsOn(today)).single().prop(CalendarEvent::end)
            .isEqualTo(Instant.ofEpochMilli(tomorrow.at(2, zone = zone)))
        assertThat(repository.eventsOn(tomorrow)).single().prop(CalendarEvent::begin)
            .isEqualTo(Instant.ofEpochMilli(today.at(22, zone = zone)))
    }

    @Test
    fun cancelledAndDeletedEvents_areLeftOut() = runTest {
        provider.addInstance(
            instanceId = 600, eventId = 60, begin = today.at(9, zone = zone), end = today.at(10, zone = zone), zone = zone,
            status = Events.STATUS_CANCELED,
        )
        provider.addInstance(
            instanceId = 601, eventId = 61, begin = today.at(9, zone = zone), end = today.at(10, zone = zone), zone = zone,
            deleted = true,
        )
        provider.addInstance(
            instanceId = 602, eventId = 62, begin = today.at(9, zone = zone), end = today.at(10, zone = zone), zone = zone,
            status = null,
        )

        assertThat(repository.eventsOn(today)).single().prop(CalendarEvent::eventId).isEqualTo(62)
    }

    @Test
    fun declinedEvent_isKept_andIsNotAMeeting() = runTest {
        provider.addInstance(
            instanceId = 700, eventId = 70, begin = today.at(14, zone = zone), end = today.at(15, zone = zone), zone = zone,
            selfStatus = Attendees.ATTENDEE_STATUS_DECLINED,
        )
        provider.addAttendee(id = 1, eventId = 70, email = me, status = Attendees.ATTENDEE_STATUS_DECLINED)
        provider.addAttendee(id = 2, eventId = 70, email = "other@example.com")

        assertThat(repository.eventsOn(today)).single().all {
            prop(CalendarEvent::selfStatus).isEqualTo(SelfStatus.DECLINED)
            prop(CalendarEvent::humanAttendees).isEqualTo(2)
            prop(CalendarEvent::isMeeting).isFalse()
        }
    }

    @Test
    fun hiddenCalendar_isSkippedByDefault_andIncludedWhenAskedFor() = runTest {
        provider.addCalendar(id = 2, ownerAccount = me, visible = false)
        provider.addInstance(
            instanceId = 800, eventId = 80, begin = today.at(9, zone = zone), end = today.at(10, zone = zone), zone = zone,
            calendarId = 1,
        )
        provider.addInstance(
            instanceId = 801, eventId = 81, begin = today.at(9, zone = zone), end = today.at(10, zone = zone), zone = zone,
            calendarId = 2, visible = false,
        )

        assertThat(repository.eventsOn(today).map { it.eventId }).containsExactly(80L)
        assertThat(repository.eventsOn(today, CalendarFilter.Only(setOf(2L))).map { it.eventId }).containsExactly(81L)
        assertThat(repository.eventsOn(today, CalendarFilter.Only(setOf(1L, 2L))).map { it.eventId }).containsExactly(80L, 81L)
    }

    @Test
    fun emptyOnlyFilter_isAnsweredWithoutAskingTheProvider() = runTest {
        provider.addInstance(
            instanceId = 800, eventId = 80, begin = today.at(9, zone = zone), end = today.at(10, zone = zone), zone = zone,
        )

        assertThat(repository.eventsOn(today, CalendarFilter.Only(emptySet()))).isEmpty()
        assertThat(provider.queriedUris).isEmpty()
    }

    @Test
    fun attendees_areFetchedInOneQueryPerDay() = runTest {
        repeat(5) { i ->
            provider.addInstance(
                instanceId = 900L + i, eventId = 90L + i, begin = today.at(9 + i, zone = zone), end = today.at(10 + i, zone = zone), zone = zone,
            )
            provider.addAttendee(id = 10L + i, eventId = 90L + i, email = me)
            provider.addAttendee(id = 20L + i, eventId = 90L + i, email = "guest$i@example.com")
        }

        val events = repository.eventsOn(today)

        assertThat(events).hasSize(5)
        assertThat(events.map { it.humanAttendees }).containsExactly(2, 2, 2, 2, 2)
        assertThat(events.map { it.selfAttendeeId }).containsExactly(10L, 11L, 12L, 13L, 14L)
        assertThat(provider.queriedUris.filter { it.path?.startsWith("/attendees") == true }).hasSize(1)
    }

    @Test
    fun attendees_areFetchedInChunks_whenADayHasMoreEventsThanSqliteCanBind() = runTest {
        val count = 501
        repeat(count) { i ->
            provider.addInstance(
                instanceId = 10_000L + i, eventId = 10_000L + i, begin = today.at(9, zone = zone), end = today.at(10, zone = zone), zone = zone,
            )
            provider.addAttendee(id = 20_000L + i, eventId = 10_000L + i, email = "guest$i@example.com")
        }

        val events = repository.eventsOn(today)

        assertThat(events).hasSize(count)
        assertThat(events.all { it.humanAttendees == 1 }).isTrue()
        assertThat(provider.queriedUris.filter { it.path?.startsWith("/attendees") == true }).hasSize(2)
    }

    @Test
    fun duplicateSelfRows_keepOrganizerness_andPreferTheOrganizerRowsId() = runTest {
        // the organizer row comes first and the account's own plain row second: the second
        // row must neither flip isOrganizer back to false nor replace the organizer row's id
        provider.addInstance(
            instanceId = 950, eventId = 95, begin = today.at(9, zone = zone), end = today.at(10, zone = zone), zone = zone,
        )
        provider.addAttendee(id = 1, eventId = 95, email = me, relationship = Attendees.RELATIONSHIP_ORGANIZER)
        provider.addAttendee(id = 2, eventId = 95, email = me)
        // and the other order: the plain row first, the organizer row second
        provider.addInstance(
            instanceId = 951, eventId = 96, begin = today.at(11, zone = zone), end = today.at(12, zone = zone), zone = zone,
        )
        provider.addAttendee(id = 3, eventId = 96, email = me)
        provider.addAttendee(id = 4, eventId = 96, email = me, relationship = Attendees.RELATIONSHIP_ORGANIZER)

        val byId = repository.eventsOn(today).associateBy { it.eventId }

        assertThat(byId.getValue(95).isOrganizer).isTrue()
        assertThat(byId.getValue(95).selfAttendeeId).isEqualTo(1L)
        assertThat(byId.getValue(96).isOrganizer).isTrue()
        assertThat(byId.getValue(96).selfAttendeeId).isEqualTo(4L)
    }

    @Test
    fun emptyDay_skipsTheAttendeeQuery() = runTest {
        assertThat(repository.eventsOn(today)).isEmpty()
        assertThat(provider.queriedUris.filter { it.path?.startsWith("/attendees") == true }).isEmpty()
    }

    @Test
    fun selfOnlyAttendeeData_countsNoHumans_andReadsTheInviteFromSelfStatus() = runTest {
        // Exchange-style calendars: HAS_ATTENDEE_DATA = 0, so the attendee table is not trusted
        provider.addInstance(
            instanceId = 1000, eventId = 100, begin = today.at(9, zone = zone), end = today.at(10, zone = zone), zone = zone,
            hasAttendeeData = false, selfStatus = Attendees.ATTENDEE_STATUS_INVITED,
        )
        provider.addAttendee(id = 1, eventId = 100, email = me)

        assertThat(repository.eventsOn(today)).single().all {
            prop(CalendarEvent::hasAttendeeData).isFalse()
            prop(CalendarEvent::humanAttendees).isEqualTo(0)
            prop(CalendarEvent::selfStatus).isEqualTo(SelfStatus.NEEDS_ACTION)
            prop(CalendarEvent::selfAttendeeId).isEqualTo(1L)
            prop(CalendarEvent::isMeeting).isTrue()
        }
    }

    @Test
    fun organizer_isRecognisedFromTheFlag_theOrganizerEmail_orOurAttendeeRow() = runTest {
        provider.addInstance(
            instanceId = 1100, eventId = 110, begin = today.at(9, zone = zone), end = today.at(10, zone = zone), zone = zone,
            isOrganizer = 1,
        )
        provider.addInstance(
            instanceId = 1101, eventId = 111, begin = today.at(11, zone = zone), end = today.at(12, zone = zone), zone = zone,
            organizer = "ME@example.com",
        )
        provider.addInstance(
            instanceId = 1102, eventId = 112, begin = today.at(13, zone = zone), end = today.at(14, zone = zone), zone = zone,
        )
        provider.addAttendee(id = 1, eventId = 112, email = "Me@Example.com", relationship = Attendees.RELATIONSHIP_ORGANIZER)
        provider.addInstance(
            instanceId = 1103, eventId = 113, begin = today.at(15, zone = zone), end = today.at(16, zone = zone), zone = zone,
            organizer = "boss@example.com",
        )
        provider.addAttendee(id = 2, eventId = 113, email = me)

        val byId = repository.eventsOn(today).associateBy { it.eventId }

        assertThat(byId.getValue(110).isOrganizer).isTrue()
        assertThat(byId.getValue(111).isOrganizer).isTrue()
        assertThat(byId.getValue(112).isOrganizer).isTrue()
        assertThat(byId.getValue(112).selfAttendeeId).isEqualTo(1L)
        assertThat(byId.getValue(113).isOrganizer).isFalse()
    }

    @Test
    fun soloBlock_hasNoSelfAttendeeRow() = runTest {
        provider.addInstance(
            instanceId = 1200, eventId = 120, begin = today.at(9, zone = zone), end = today.at(10, zone = zone), zone = zone,
            title = "Dentist",
        )

        assertThat(repository.eventsOn(today)).single().all {
            prop(CalendarEvent::selfAttendeeId).isNull()
            prop(CalendarEvent::humanAttendees).isEqualTo(0)
            prop(CalendarEvent::isMeeting).isFalse()
        }
    }

    @Test
    fun events_areOrderedAllDayFirst_thenByStart() = runTest {
        provider.addInstance(instanceId = 1, eventId = 1, begin = today.at(13, zone = zone), end = today.at(14, zone = zone), zone = zone)
        provider.addInstance(instanceId = 2, eventId = 2, begin = today.at(9, zone = zone), end = today.at(10, zone = zone), zone = zone)
        provider.addInstance(
            instanceId = 3, eventId = 3, begin = today.utcMidnight(), end = tomorrow.utcMidnight(), zone = zone, allDay = true,
        )
        provider.addInstance(instanceId = 4, eventId = 4, begin = today.at(9, zone = zone), end = today.at(11, zone = zone), zone = zone)

        assertThat(repository.eventsOn(today).map { it.eventId }).containsExactly(3L, 4L, 2L, 1L)
    }

    @Test
    fun calendars_returnsEveryRow_hiddenAndNonSyncingIncluded() = runTest {
        provider.addCalendar(
            id = 2, ownerAccount = "work@example.com", accountType = "com.google", displayName = "Work",
            color = 0xFFFF0000.toInt(), visible = false, syncEvents = false, isPrimary = true,
            accessLevel = Calendars.CAL_ACCESS_READ, canOrganizerRespond = false,
        )

        assertThat(repository.calendars()).containsExactly(
            CalendarInfo(
                id = 1, accountName = me, accountType = "com.google", displayName = "Calendar 1",
                color = 0xFF3F51B5.toInt(), visible = true, syncEvents = true, ownerAccount = me,
                isPrimary = true, accessLevel = Calendars.CAL_ACCESS_OWNER, canOrganizerRespond = true,
            ),
            CalendarInfo(
                id = 2, accountName = "work@example.com", accountType = "com.google", displayName = "Work",
                color = 0xFFFF0000.toInt(), visible = false, syncEvents = false, ownerAccount = "work@example.com",
                isPrimary = true, accessLevel = Calendars.CAL_ACCESS_READ, canOrganizerRespond = false,
            ),
        )
    }
}
