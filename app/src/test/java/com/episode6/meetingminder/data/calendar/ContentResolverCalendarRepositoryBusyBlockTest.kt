package com.episode6.meetingminder.data.calendar

import android.content.ContentUris
import android.content.Context
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.episode6.meetingminder.model.BusyRange
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
 * The busy-calendar writes of TODO.md §4.7 against [FakeCalendarProvider]: what an insert
 * puts on the calendar, and nothing more, is the whole point of the feature — the block
 * lands on a calendar other people read — so the column set is pinned exactly, and a
 * delete is a bare `events/{id}` with no selection, answering true only for a row that
 * was there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ContentResolverCalendarRepositoryBusyBlockTest {

    private val zone: ZoneId = ZoneId.of("America/New_York")
    private val today: LocalDate = LocalDate.of(2026, 9, 14)
    private val familyCalendar = 7L
    private val range = BusyRange(
        begin = Instant.ofEpochMilli(today.at(10, zone = zone)),
        end = Instant.ofEpochMilli(today.at(11, 30, zone)),
    )

    private lateinit var provider: FakeCalendarProvider
    private lateinit var repository: ContentResolverCalendarRepository

    @Before
    fun setUp() {
        provider = Robolectric.setupContentProvider(FakeCalendarProvider::class.java, CalendarContract.AUTHORITY)
        provider.addCalendar(id = familyCalendar, displayName = "Family")
        repository = ContentResolverCalendarRepository(
            contentResolver = ApplicationProvider.getApplicationContext<Context>().contentResolver,
            packageName = TEST_PACKAGE,
            zone = { zone },
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    @Test
    fun insertBusyBlock_writesExactlyTheBareColumnSet_andNothingAboutTheMeeting() = runTest {
        repository.insertBusyBlock(familyCalendar, range)

        val (uri, values) = provider.inserts.single()
        assertThat(uri).isEqualTo(Events.CONTENT_URI)
        assertThat(values.keySet()).containsExactlyInAnyOrder(
            Events.CALENDAR_ID,
            Events.DTSTART,
            Events.DTEND,
            Events.TITLE,
            Events.EVENT_TIMEZONE,
            Events.AVAILABILITY,
            Events.HAS_ALARM,
            Events.ACCESS_LEVEL,
            Events.CUSTOM_APP_PACKAGE,
        )
        assertThat(values.getAsLong(Events.CALENDAR_ID)).isEqualTo(familyCalendar)
        assertThat(values.getAsLong(Events.DTSTART)).isEqualTo(range.begin.toEpochMilli())
        assertThat(values.getAsLong(Events.DTEND)).isEqualTo(range.end.toEpochMilli())
        assertThat(values.getAsString(Events.TITLE)).isEqualTo("busy")
        assertThat(values.getAsString(Events.EVENT_TIMEZONE)).isEqualTo("America/New_York")
        assertThat(values.getAsInteger(Events.AVAILABILITY)).isEqualTo(Events.AVAILABILITY_BUSY)
        assertThat(values.getAsInteger(Events.HAS_ALARM)).isEqualTo(0)
        assertThat(values.getAsInteger(Events.ACCESS_LEVEL)).isEqualTo(Events.ACCESS_DEFAULT)
        assertThat(values.getAsString(Events.CUSTOM_APP_PACKAGE)).isEqualTo(TEST_PACKAGE)
        // the columns that would leak the meeting, or make the block anything but a bare block
        for (leak in listOf(
            Events.DESCRIPTION, Events.EVENT_LOCATION, Events.EVENT_COLOR, Events.EVENT_COLOR_KEY, Events.ORGANIZER,
            Events.GUESTS_CAN_MODIFY, Events.GUESTS_CAN_INVITE_OTHERS, Events.GUESTS_CAN_SEE_GUESTS, Events.HAS_ATTENDEE_DATA,
            Events.RRULE, Events.RDATE, Events.DURATION, Events.ALL_DAY, Events.SELF_ATTENDEE_STATUS, Events.CUSTOM_APP_URI,
        )) {
            assertThat(values.containsKey(leak), leak).isFalse()
        }
    }

    @Test
    fun insertBusyBlock_isAPlainInsert_notASyncAdapterOne_soTheRowIsDirty() = runTest {
        val id = repository.insertBusyBlock(familyCalendar, range)

        val (uri, _) = provider.inserts.single()
        assertThat(uri.getQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER) ?: "false").isEqualTo("false")
        // dirty = 1: the account's own sync adapter has it to upload
        assertThat(repository.syncedEventIds(listOf(id))).isEmpty()
    }

    @Test
    fun insertBusyBlock_returnsTheNewEventsId_whichAFollowUpReadSeesAsOwnedByApp() = runTest {
        provider.nextEventId = 4242

        val id = repository.insertBusyBlock(familyCalendar, range)

        assertThat(id).isEqualTo(4242)
        val block = repository.eventsOn(today).single()
        assertThat(block.eventId).isEqualTo(4242)
        assertThat(block.calendarId).isEqualTo(familyCalendar)
        assertThat(block.title).isEqualTo("busy")
        assertThat(block.begin).isEqualTo(range.begin)
        assertThat(block.end).isEqualTo(range.end)
        assertThat(block.ownedByApp).isTrue()
        assertThat(block.isMeeting).isFalse()
    }

    @Test
    fun deleteOwnEvent_isABareEventsIdDelete_andReturnsTrueForARowThatWasThere() = runTest {
        val id = repository.insertBusyBlock(familyCalendar, range)

        assertThat(repository.deleteOwnEvent(id)).isTrue()

        assertThat(provider.deletes).containsExactly(ContentUris.withAppendedId(Events.CONTENT_URI, id))
        assertThat(provider.hasEvent(id)).isFalse()
        assertThat(repository.eventsOn(today)).isEmpty()
    }

    @Test
    fun deleteOwnEvent_returnsFalseForARowThatIsAlreadyGone() = runTest {
        val id = repository.insertBusyBlock(familyCalendar, range)
        assertThat(repository.deleteOwnEvent(id)).isTrue()

        assertThat(repository.deleteOwnEvent(id)).isFalse()
        assertThat(repository.deleteOwnEvent(999_999)).isFalse()
    }

    @Test
    fun enableCalendarSync_writesOnlySyncEvents_toThatCalendar() = runTest {
        provider.addCalendar(id = 8, displayName = "New", syncEvents = false, visible = false)

        assertThat(repository.enableCalendarSync(8)).isTrue()

        val (uri, values) = provider.updates.single()
        assertThat(uri).isEqualTo(ContentUris.withAppendedId(Calendars.CONTENT_URI, 8))
        assertThat(values.keySet()).containsExactlyInAnyOrder(Calendars.SYNC_EVENTS)
        assertThat(values.getAsInteger(Calendars.SYNC_EVENTS)).isEqualTo(1)
        val calendar = repository.calendars().single { it.id == 8L }
        assertThat(calendar.syncEvents).isTrue()
        assertThat(calendar.visible).isFalse()
    }

    @Test
    fun enableCalendarSync_returnsFalseForACalendarThatIsGone() = runTest {
        assertThat(repository.enableCalendarSync(999)).isFalse()
    }
}
