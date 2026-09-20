package com.episode6.meetingminder.data.calendar

import android.content.ContentUris
import android.content.Context
import android.provider.CalendarContract
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Events
import androidx.test.core.app.ApplicationProvider
import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.hasClass
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.episode6.meetingminder.model.EventResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/**
 * The two RSVP write shapes of TODO.md §4.6 against [FakeCalendarProvider]: a plain event
 * updates our own `attendees/{id}` row, a recurring occurrence inserts an
 * `exception/{eventId}` with `ORIGINAL_INSTANCE_TIME = begin`. Every write is addressed by
 * the occurrence's own `eventId`, never the series id in its key. A "No" or "Maybe" from
 * the long-press sheet takes the same shapes with a different status.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ContentResolverCalendarRepositoryRsvpTest {

    private val zone: ZoneId = ZoneId.of("America/New_York")
    private val today: LocalDate = LocalDate.of(2026, 9, 14)
    private val me = "me@example.com"

    private lateinit var provider: FakeCalendarProvider
    private lateinit var repository: ContentResolverCalendarRepository

    @Before
    fun setUp() {
        provider = Robolectric.setupContentProvider(FakeCalendarProvider::class.java, CalendarContract.AUTHORITY)
        provider.addCalendar(id = 1, ownerAccount = me)
        repository = ContentResolverCalendarRepository(
            contentResolver = ApplicationProvider.getApplicationContext<Context>().contentResolver,
            packageName = TEST_PACKAGE,
            zone = { zone },
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    private fun seedInvite(eventId: Long, selfAttendeeId: Long, rrule: String? = null, originalId: Long? = null, originalInstanceTime: Long? = null) {
        provider.addInstance(
            instanceId = eventId * 100, eventId = eventId, begin = today.at(10, zone = zone), end = today.at(11, zone = zone), zone = zone,
            organizer = "boss@example.com", rrule = rrule, originalId = originalId, originalInstanceTime = originalInstanceTime,
        )
        provider.addAttendee(id = selfAttendeeId, eventId = eventId, email = me)
        provider.addAttendee(id = selfAttendeeId + 1, eventId = eventId, email = "boss@example.com", relationship = Attendees.RELATIONSHIP_ORGANIZER)
    }

    @Test
    fun plainEvent_updatesOurOwnAttendeeRowToAccepted_andReturnsTheEventsOwnId() = runTest {
        seedInvite(eventId = 10, selfAttendeeId = 7)
        val event = repository.eventsOn(today).single()

        val written = repository.respondToInstance(event, EventResponse.YES)

        assertThat(written).isEqualTo(10)
        provider.updates.single().let { (uri, values) ->
            assertThat(uri).isEqualTo(ContentUris.withAppendedId(Attendees.CONTENT_URI, 7))
            assertThat(values.size()).isEqualTo(1)
            assertThat(values.getAsInteger(Attendees.ATTENDEE_STATUS)).isEqualTo(Attendees.ATTENDEE_STATUS_ACCEPTED)
        }
        assertThat(provider.inserts).isEmpty()
        assertThat(provider.attendeeStatus(7)).isEqualTo(Attendees.ATTENDEE_STATUS_ACCEPTED)
        // the organizer's row is untouched
        assertThat(provider.attendeeStatus(8)).isEqualTo(Attendees.ATTENDEE_STATUS_INVITED)
    }

    @Test
    fun recurringOccurrence_insertsAnExceptionForThatInstance_andReturnsTheNewId() = runTest {
        seedInvite(eventId = 20, selfAttendeeId = 7, rrule = "FREQ=DAILY")
        provider.nextExceptionId = 555
        val event = repository.eventsOn(today).single()
        assertThat(event.isRecurringInstance).isTrue()

        val written = repository.respondToInstance(event, EventResponse.YES)

        assertThat(written).isEqualTo(555)
        provider.inserts.single().let { (uri, values) ->
            assertThat(uri).isEqualTo(ContentUris.withAppendedId(Events.CONTENT_EXCEPTION_URI, 20))
            assertThat(values.size()).isEqualTo(3)
            assertThat(values.getAsLong(Events.ORIGINAL_INSTANCE_TIME)).isEqualTo(today.at(10, zone = zone))
            assertThat(values.getAsInteger(Events.SELF_ATTENDEE_STATUS)).isEqualTo(Attendees.ATTENDEE_STATUS_ACCEPTED)
            assertThat(values.getAsInteger(Events.STATUS)).isEqualTo(Events.STATUS_CONFIRMED)
        }
        assertThat(provider.updates).isEmpty()
    }

    @Test
    fun anOccurrenceThatAlreadyIsAnException_isAPlainEvent_addressedByItsOwnIdNotTheSeriesId() = runTest {
        // the key holds the series id (20) and the original time; the write must go to event 21's row
        seedInvite(eventId = 21, selfAttendeeId = 9, originalId = 20, originalInstanceTime = today.at(9, zone = zone))
        val event = repository.eventsOn(today).single()
        assertThat(event.key.eventId).isEqualTo(20)
        assertThat(event.isRecurringInstance).isFalse()

        val written = repository.respondToInstance(event, EventResponse.YES)

        assertThat(written).isEqualTo(21)
        provider.updates.single().let { (uri, _) ->
            assertThat(uri).isEqualTo(ContentUris.withAppendedId(Attendees.CONTENT_URI, 9))
        }
        assertThat(provider.inserts).isEmpty()
    }

    @Test
    fun aNo_onAPlainEvent_writesDeclinedToOurOwnRow() = runTest {
        seedInvite(eventId = 10, selfAttendeeId = 7)
        val event = repository.eventsOn(today).single()

        val written = repository.respondToInstance(event, EventResponse.NO)

        assertThat(written).isEqualTo(10)
        assertThat(provider.updates.single().second.getAsInteger(Attendees.ATTENDEE_STATUS)).isEqualTo(Attendees.ATTENDEE_STATUS_DECLINED)
        assertThat(provider.attendeeStatus(7)).isEqualTo(Attendees.ATTENDEE_STATUS_DECLINED)
    }

    @Test
    fun aMaybe_onARecurringOccurrence_insertsATentativeException_stillConfirmed() = runTest {
        seedInvite(eventId = 20, selfAttendeeId = 7, rrule = "FREQ=DAILY")
        provider.nextExceptionId = 556
        val event = repository.eventsOn(today).single()

        val written = repository.respondToInstance(event, EventResponse.MAYBE)

        assertThat(written).isEqualTo(556)
        provider.inserts.single().let { (uri, values) ->
            assertThat(uri).isEqualTo(ContentUris.withAppendedId(Events.CONTENT_EXCEPTION_URI, 20))
            assertThat(values.getAsInteger(Events.SELF_ATTENDEE_STATUS)).isEqualTo(Attendees.ATTENDEE_STATUS_TENTATIVE)
            // the occurrence's own status, not our answer: AOSP's calendar app writes it the same way for a decline
            assertThat(values.getAsInteger(Events.STATUS)).isEqualTo(Events.STATUS_CONFIRMED)
        }
    }

    @Test
    fun plainEvent_whoseAttendeeRowIsGone_throwsInsteadOfReportingSuccess() = runTest {
        seedInvite(eventId = 10, selfAttendeeId = 7)
        val event = repository.eventsOn(today).single()

        assertFailure { repository.respondToInstance(event.copy(selfAttendeeId = 99), EventResponse.YES) }.hasClass(IllegalStateException::class)
        assertFailure { repository.respondToInstance(event.copy(selfAttendeeId = null), EventResponse.YES) }.hasClass(IllegalStateException::class)
    }

    @Test
    fun syncedEventIds_areTheRequestedIdsThatExistAndAreClean() = runTest {
        provider.addInstance(instanceId = 100, eventId = 10, begin = today.at(9, zone = zone), end = today.at(10, zone = zone), zone = zone, dirty = true)
        provider.addInstance(instanceId = 101, eventId = 11, begin = today.at(11, zone = zone), end = today.at(12, zone = zone), zone = zone)
        provider.addInstance(instanceId = 102, eventId = 12, begin = today.at(13, zone = zone), end = today.at(14, zone = zone), zone = zone)

        // 10 is still dirty, 12 wasn't asked about, 99 doesn't exist (deleted is not synced)
        assertThat(repository.syncedEventIds(listOf(10, 11, 99))).isEqualTo(setOf(11L))
        assertThat(repository.syncedEventIds(emptyList())).isEmpty()
        assertThat(provider.queriedUris.count { it == Events.CONTENT_URI }).isEqualTo(1)
    }
}
