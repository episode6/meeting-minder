package com.episode6.meetingminder.data.calendar

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import com.episode6.meetingminder.model.Availability
import com.episode6.meetingminder.model.BusyRange
import com.episode6.meetingminder.model.CalendarEvent
import com.episode6.meetingminder.model.CalendarInfo
import com.episode6.meetingminder.model.EventKey
import com.episode6.meetingminder.model.EventResponse
import com.episode6.meetingminder.model.EventStatus
import com.episode6.meetingminder.model.SelfStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * [CalendarRepository] over the Calendar Provider (TODO.md §4.1). Events for a day come from
 * `Instances` (provider-expanded recurrences with exceptions and EXDATEs applied, every
 * `Events` + `Calendars` column joined in), never from `Events` directly.
 *
 * [zone] is read per query so a timezone change while the app is alive is picked up; tests
 * pin it to a negative-offset zone to exercise the all-day gotcha. [packageName] is the
 * running build's `applicationId`: written as `CUSTOM_APP_PACKAGE` on every busy block the
 * app inserts and compared against on every instance read ([CalendarEvent.ownedByApp]), so
 * a debug build and a snapshot build sharing a device each recognise only their own blocks.
 */
class ContentResolverCalendarRepository(
    private val contentResolver: ContentResolver,
    private val packageName: String,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CalendarRepository {

    override suspend fun calendars(): List<CalendarInfo> = withContext(ioDispatcher) {
        contentResolver.query(Calendars.CONTENT_URI, CALENDAR_PROJECTION, null, null, Calendars._ID)
            ?.use { cursor -> cursor.mapRows { it.toCalendarInfo() } }
            .orEmpty()
    }

    override suspend fun eventsOn(date: LocalDate, filter: CalendarFilter): List<CalendarEvent> =
        withContext(ioDispatcher) {
            if (filter is CalendarFilter.Only && filter.calendarIds.isEmpty()) return@withContext emptyList()
            val instances = queryInstances(date, filter)
            if (instances.isEmpty()) return@withContext emptyList()
            val attendees = queryAttendees(instances)
            instances
                .map { it.toCalendarEvent(attendees[it.eventId] ?: AttendeeSummary.EMPTY) }
                .sortedWith(compareByDescending<CalendarEvent> { it.allDay }.thenBy { it.begin }.thenByDescending { it.end }.thenBy { it.title })
        }

    /**
     * The write shapes are the ones AOSP's own calendar app uses (TODO.md §4.6), both
     * addressed by the occurrence's **own** id ([CalendarEvent.eventId]), never by
     * `key.eventId` (the series id for a recurring occurrence):
     *  - a recurring occurrence → `insert(Events.CONTENT_EXCEPTION_URI/{eventId})` with
     *    `ORIGINAL_INSTANCE_TIME = begin` and `SELF_ATTENDEE_STATUS` = [response]. That is the
     *    one place `SELF_ATTENDEE_STATUS` is app-writable: the provider clones the event as
     *    an exception (with `ORIGINAL_ID`, so it keeps the same [EventKey]) and updates the
     *    clone's self-attendee row. Google syncs it as a per-instance response.
     *  - anything else → `update(Attendees.CONTENT_URI/{selfAttendeeId})` with
     *    `ATTENDEE_STATUS` = [response]; the provider mirrors it into `SELF_ATTENDEE_STATUS`.
     * Either way the provider marks the event `DIRTY` and the account's sync adapter
     * uploads the response on its next upload sync. The exception's `STATUS` is
     * `CONFIRMED` whatever the answer, as in AOSP's calendar app: it is the occurrence's
     * status, not ours.
     */
    override suspend fun respondToInstance(event: CalendarEvent, response: EventResponse): Long = withContext(ioDispatcher) {
        val attendeeStatus = response.attendeeStatus
        if (event.isRecurringInstance) {
            val uri = ContentUris.withAppendedId(Events.CONTENT_EXCEPTION_URI, event.eventId)
            val values = ContentValues().apply {
                put(Events.ORIGINAL_INSTANCE_TIME, event.begin.toEpochMilli())
                put(Events.SELF_ATTENDEE_STATUS, attendeeStatus)
                put(Events.STATUS, Events.STATUS_CONFIRMED)
            }
            val inserted = contentResolver.insert(uri, values) ?: error("exception insert on $uri returned no row")
            ContentUris.parseId(inserted)
        } else {
            val selfAttendeeId = event.selfAttendeeId ?: error("event ${event.eventId} has no self-attendee row to answer through")
            val uri = ContentUris.withAppendedId(Attendees.CONTENT_URI, selfAttendeeId)
            val values = ContentValues().apply { put(Attendees.ATTENDEE_STATUS, attendeeStatus) }
            val updated = contentResolver.update(uri, values, null, null)
            check(updated == 1) { "attendee update on $uri touched $updated rows" }
            event.eventId
        }
    }

    /**
     * Exactly these columns and no others (TODO.md §4.7): a busy block must carry nothing
     * about the meetings it stands in for, so `DESCRIPTION`, `EVENT_LOCATION`, `EVENT_COLOR`,
     * `ORGANIZER`, `GUESTS_*` and `RRULE` are never set. Not a sync-adapter insert, so the
     * provider marks the row `DIRTY` and the account's adapter uploads it.
     * `CUSTOM_APP_PACKAGE` is the ownership marker [CalendarEvent.ownedByApp] reads back.
     * `EVENT_TIMEZONE` is this repository's own [zone] — the one every read uses too — so
     * there is a single zone authority here, and a test that pins the zone pins the insert's.
     */
    override suspend fun insertBusyBlock(calendarId: Long, range: BusyRange): Long = withContext(ioDispatcher) {
        val values = ContentValues().apply {
            put(Events.CALENDAR_ID, calendarId)
            put(Events.DTSTART, range.begin.toEpochMilli())
            put(Events.DTEND, range.end.toEpochMilli())
            put(Events.TITLE, BUSY_BLOCK_TITLE)
            put(Events.EVENT_TIMEZONE, zone().id)
            put(Events.AVAILABILITY, Events.AVAILABILITY_BUSY)
            put(Events.HAS_ALARM, 0)
            put(Events.ACCESS_LEVEL, Events.ACCESS_DEFAULT)
            put(Events.CUSTOM_APP_PACKAGE, packageName)
        }
        val inserted = contentResolver.insert(Events.CONTENT_URI, values)
            ?: error("busy block insert on calendar $calendarId returned no row")
        ContentUris.parseId(inserted)
    }

    /**
     * A bare `events/{id}` delete with no selection: the id came out of our own `busy_block`
     * table, so there is nothing to double-check it against, and a plain (non-sync-adapter)
     * delete lets the account's adapter remove the event upstream. The provider answers with
     * the rows affected: 0 when the event was already gone.
     */
    override suspend fun deleteOwnEvent(eventId: Long): Boolean = withContext(ioDispatcher) {
        contentResolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, eventId), null, null) > 0
    }

    override suspend fun enableCalendarSync(calendarId: Long): Boolean = withContext(ioDispatcher) {
        val values = ContentValues().apply { put(Calendars.SYNC_EVENTS, 1) }
        contentResolver.update(ContentUris.withAppendedId(Calendars.CONTENT_URI, calendarId), values, null, null) > 0
    }

    override suspend fun syncedEventIds(eventIds: Collection<Long>): Set<Long> = withContext(ioDispatcher) {
        val ids = eventIds.toSortedSet()
        if (ids.isEmpty()) return@withContext emptySet()
        buildSet {
            ids.chunked(ATTENDEE_QUERY_CHUNK).forEach { chunk ->
                contentResolver.query(
                    Events.CONTENT_URI,
                    arrayOf(Events._ID),
                    "${Events._ID} IN (${placeholders(chunk.size)}) AND ${Events.DIRTY} = 0",
                    chunk.map(Long::toString).toTypedArray(),
                    null,
                )?.use { cursor -> while (cursor.moveToNext()) add(cursor.getLong(Events._ID)) }
            }
        }
    }

    /**
     * The query window is local midnight → next local midnight **widened by ±1 day**, then
     * re-filtered on `START_DAY`/`END_DAY` (Julian days the provider computed in local time,
     * and in UTC for all-day events). The provider matches anything *overlapping* the window,
     * and all-day events store `BEGIN` as UTC midnight, so without the day filter tomorrow's
     * all-day event would show up at 8 PM tonight in New York. An event ending exactly at
     * midnight gets `END_DAY` of the previous day from the provider, so it never leaks into
     * the next day either.
     *
     * Caveat: the provider computes `START_DAY`/`END_DAY` in its own "instances timezone"
     * (`CalendarCache`), which equals the device zone only while the timezone type is `auto`
     * — the default, and the only mode Google Calendar on Android uses. A calendar app that
     * writes a fixed "home time zone" into the cache would put Julian days in that zone while
     * this window is built in [zone], and a late-evening event could then land on the wrong
     * day. Same caveat applies to `Instances.CONTENT_BY_DAY_URI`, so there is no better
     * option; noted here so the symptom is recognisable.
     */
    private fun queryInstances(date: LocalDate, filter: CalendarFilter): List<InstanceRow> {
        val zone = zone()
        val windowBegin = date.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val windowEnd = date.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
        val uri = Instances.CONTENT_URI.buildUpon()
            .also { ContentUris.appendId(it, windowBegin) }
            .also { ContentUris.appendId(it, windowEnd) }
            .build()
        val (selection, selectionArgs) = instanceSelection(filter)
        val julianDay = date.julianDay
        return contentResolver.query(uri, INSTANCE_PROJECTION, selection, selectionArgs, Instances.BEGIN)
            ?.use { cursor -> cursor.mapRows { it.toInstanceRow() } }
            .orEmpty()
            .filter { it.startDay <= julianDay && julianDay <= it.endDay }
    }

    private fun instanceSelection(filter: CalendarFilter): Pair<String, Array<String>> {
        // STATUS is nullable: a locally inserted event without one is still a confirmed event
        val base = "${Events.DELETED} = 0 AND (${Instances.STATUS} IS NULL OR ${Instances.STATUS} != ${Events.STATUS_CANCELED})"
        return when (filter) {
            CalendarFilter.Visible -> "$base AND ${Instances.VISIBLE} = 1" to emptyArray()
            is CalendarFilter.Only -> {
                // an empty id set is answered in eventsOn without a provider round trip
                val ids = filter.calendarIds.sorted()
                "$base AND ${Instances.CALENDAR_ID} IN (${placeholders(ids.size)})" to ids.map(Long::toString).toTypedArray()
            }
        }
    }

    /**
     * One batched `Attendees` query per day (never per event). Humans exclude
     * `TYPE_RESOURCE` rooms; "me" is the row whose email matches the calendar's
     * `OWNER_ACCOUNT` case-insensitively.
     */
    private fun queryAttendees(instances: List<InstanceRow>): Map<Long, AttendeeSummary> {
        val ownerByEvent = instances.associate { it.eventId to it.ownerAccount }
        val accumulators = mutableMapOf<Long, AttendeeAccumulator>()
        ownerByEvent.keys.sorted().chunked(ATTENDEE_QUERY_CHUNK).forEach { ids ->
            contentResolver.query(
                Attendees.CONTENT_URI,
                ATTENDEE_PROJECTION,
                "${Attendees.EVENT_ID} IN (${placeholders(ids.size)})",
                ids.map(Long::toString).toTypedArray(),
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val eventId = cursor.getLong(Attendees.EVENT_ID)
                    val accumulator = accumulators.getOrPut(eventId) { AttendeeAccumulator() }
                    if (cursor.getIntOrNull(Attendees.ATTENDEE_TYPE) != Attendees.TYPE_RESOURCE) accumulator.humans++
                    val email = cursor.getStringOrNull(Attendees.ATTENDEE_EMAIL)
                    val owner = ownerByEvent[eventId]
                    if (email != null && owner != null && email.equals(owner, ignoreCase = true)) {
                        // Some sync adapters produce two rows for the owner (the invite's and the
                        // account's own). Organizer-ness sticks once any row says so, and the
                        // organizer row is the one PR-8b should write the RSVP through.
                        val isOrganizerRow =
                            cursor.getIntOrNull(Attendees.ATTENDEE_RELATIONSHIP) == Attendees.RELATIONSHIP_ORGANIZER
                        if (isOrganizerRow || accumulator.selfAttendeeId == null) {
                            accumulator.selfAttendeeId = cursor.getLong(Attendees._ID)
                        }
                        accumulator.selfIsOrganizer = accumulator.selfIsOrganizer || isOrganizerRow
                    }
                }
            }
        }
        return accumulators.mapValues { (_, it) -> it.toSummary() }
    }

    private data class AttendeeSummary(
        val humans: Int = 0,
        val selfAttendeeId: Long? = null,
        val selfIsOrganizer: Boolean = false,
    ) {
        companion object {
            val EMPTY = AttendeeSummary()
        }
    }

    /** Mutable scratch space for one event's attendee pass; never escapes [queryAttendees]. */
    private class AttendeeAccumulator {
        var humans: Int = 0
        var selfAttendeeId: Long? = null
        var selfIsOrganizer: Boolean = false

        fun toSummary() = AttendeeSummary(humans, selfAttendeeId, selfIsOrganizer)
    }

    /** The raw `Instances` columns one event needs, read before the attendee pass. */
    private class InstanceRow(
        val eventId: Long,
        val calendarId: Long,
        val title: String,
        val location: String?,
        val begin: Long,
        val end: Long,
        val allDay: Boolean,
        val startDay: Int,
        val endDay: Int,
        val color: Int,
        val selfStatus: SelfStatus,
        val status: EventStatus,
        val organizer: String?,
        val isOrganizerFlag: Boolean,
        val hasAttendeeData: Boolean,
        val availability: Availability,
        val rrule: String?,
        val rdate: String?,
        val originalId: Long?,
        val originalInstanceTime: Long?,
        val ownerAccount: String?,
        val calendarAccessLevel: Int,
        val customAppPackage: String?,
    )

    private fun Cursor.toInstanceRow() = InstanceRow(
        eventId = getLong(Instances.EVENT_ID),
        calendarId = getLong(Instances.CALENDAR_ID),
        title = getStringOrNull(Instances.TITLE).orEmpty(),
        location = getStringOrNull(Instances.EVENT_LOCATION)?.takeIf { it.isNotBlank() },
        begin = getLong(Instances.BEGIN),
        end = getLong(Instances.END),
        allDay = getIntOrNull(Instances.ALL_DAY) == 1,
        startDay = getInt(Instances.START_DAY),
        endDay = getInt(Instances.END_DAY),
        color = getIntOrNull(Instances.DISPLAY_COLOR) ?: getIntOrNull(Instances.CALENDAR_COLOR) ?: 0,
        selfStatus = when (getIntOrNull(Instances.SELF_ATTENDEE_STATUS)) {
            Attendees.ATTENDEE_STATUS_ACCEPTED -> SelfStatus.ACCEPTED
            Attendees.ATTENDEE_STATUS_TENTATIVE -> SelfStatus.TENTATIVE
            Attendees.ATTENDEE_STATUS_DECLINED -> SelfStatus.DECLINED
            Attendees.ATTENDEE_STATUS_INVITED -> SelfStatus.NEEDS_ACTION
            else -> SelfStatus.NONE
        },
        status = when (getIntOrNull(Instances.STATUS)) {
            Events.STATUS_TENTATIVE -> EventStatus.TENTATIVE
            Events.STATUS_CANCELED -> EventStatus.CANCELED
            else -> EventStatus.CONFIRMED
        },
        organizer = getStringOrNull(Instances.ORGANIZER),
        isOrganizerFlag = getIntOrNull(Instances.IS_ORGANIZER) == 1,
        hasAttendeeData = getIntOrNull(Instances.HAS_ATTENDEE_DATA) == 1,
        availability = when (getIntOrNull(Instances.AVAILABILITY)) {
            Events.AVAILABILITY_FREE -> Availability.FREE
            else -> Availability.BUSY
        },
        rrule = getStringOrNull(Instances.RRULE),
        rdate = getStringOrNull(Instances.RDATE),
        originalId = getLongOrNull(Instances.ORIGINAL_ID),
        originalInstanceTime = getLongOrNull(Instances.ORIGINAL_INSTANCE_TIME),
        ownerAccount = getStringOrNull(Instances.OWNER_ACCOUNT),
        calendarAccessLevel = getIntOrNull(Instances.CALENDAR_ACCESS_LEVEL) ?: Calendars.CAL_ACCESS_NONE,
        customAppPackage = getStringOrNull(Instances.CUSTOM_APP_PACKAGE),
    )

    private fun InstanceRow.toCalendarEvent(attendees: AttendeeSummary): CalendarEvent {
        val isException = originalId != null
        val isRecurring = !rrule.isNullOrEmpty() || !rdate.isNullOrEmpty()
        return CalendarEvent(
            key = EventKey.fromInstance(eventId, begin, rrule, rdate, originalId, originalInstanceTime),
            eventId = eventId,
            calendarId = calendarId,
            title = title,
            location = location,
            begin = Instant.ofEpochMilli(begin),
            end = Instant.ofEpochMilli(end),
            allDay = allDay,
            color = color,
            selfStatus = selfStatus,
            status = status,
            // IS_ORGANIZER is filled in by some sync adapters and not others, so the organizer
            // email and our own attendee row's relationship are consulted too
            isOrganizer = isOrganizerFlag ||
                attendees.selfIsOrganizer ||
                (organizer != null && ownerAccount != null && organizer.equals(ownerAccount, ignoreCase = true)),
            hasAttendeeData = hasAttendeeData,
            humanAttendees = if (hasAttendeeData) attendees.humans else 0,
            availability = availability,
            selfAttendeeId = attendees.selfAttendeeId,
            isRecurringInstance = isRecurring && !isException,
            calendarAccessLevel = calendarAccessLevel,
            ownedByApp = customAppPackage == packageName,
        )
    }

    private fun Cursor.toCalendarInfo(): CalendarInfo {
        val accountName = getStringOrNull(Calendars.ACCOUNT_NAME).orEmpty()
        val ownerAccount = getStringOrNull(Calendars.OWNER_ACCOUNT)
        return CalendarInfo(
            id = getLong(Calendars._ID),
            accountName = accountName,
            accountType = getStringOrNull(Calendars.ACCOUNT_TYPE).orEmpty(),
            displayName = getStringOrNull(Calendars.CALENDAR_DISPLAY_NAME).orEmpty(),
            color = getIntOrNull(Calendars.CALENDAR_COLOR) ?: 0,
            visible = getIntOrNull(Calendars.VISIBLE) == 1,
            syncEvents = getIntOrNull(Calendars.SYNC_EVENTS) == 1,
            ownerAccount = ownerAccount,
            isPrimary = getIntOrNull(Calendars.IS_PRIMARY)?.let { it == 1 }
                ?: (ownerAccount != null && ownerAccount.equals(accountName, ignoreCase = true)),
            accessLevel = getIntOrNull(Calendars.CALENDAR_ACCESS_LEVEL) ?: Calendars.CAL_ACCESS_NONE,
            canOrganizerRespond = getIntOrNull(Calendars.CAN_ORGANIZER_RESPOND) == 1,
        )
    }

    private companion object {
        /** SQLite caps bound variables; a day never has this many events, but chunk the `IN (…)` queries anyway. */
        const val ATTENDEE_QUERY_CHUNK = 500

        /** The literal, lowercase title of every busy block the app writes (TODO.md §4.7). */
        const val BUSY_BLOCK_TITLE = "busy"

        val CALENDAR_PROJECTION = arrayOf(
            Calendars._ID,
            Calendars.ACCOUNT_NAME,
            Calendars.ACCOUNT_TYPE,
            Calendars.CALENDAR_DISPLAY_NAME,
            Calendars.CALENDAR_COLOR,
            Calendars.VISIBLE,
            Calendars.SYNC_EVENTS,
            Calendars.OWNER_ACCOUNT,
            Calendars.IS_PRIMARY,
            Calendars.CALENDAR_ACCESS_LEVEL,
            Calendars.CAN_ORGANIZER_RESPOND,
        )

        val INSTANCE_PROJECTION = arrayOf(
            Instances._ID,
            Instances.EVENT_ID,
            Instances.BEGIN,
            Instances.END,
            Instances.TITLE,
            Instances.EVENT_LOCATION,
            Instances.ALL_DAY,
            Instances.CALENDAR_ID,
            Instances.SELF_ATTENDEE_STATUS,
            Instances.STATUS,
            Instances.DISPLAY_COLOR,
            Instances.CALENDAR_COLOR,
            Instances.ORGANIZER,
            Instances.IS_ORGANIZER,
            Instances.HAS_ATTENDEE_DATA,
            Instances.AVAILABILITY,
            Instances.RRULE,
            Instances.RDATE,
            Instances.ORIGINAL_ID,
            Instances.ORIGINAL_INSTANCE_TIME,
            Events.DELETED,
            Instances.OWNER_ACCOUNT,
            Instances.CALENDAR_ACCESS_LEVEL,
            Instances.START_DAY,
            Instances.END_DAY,
            Instances.CUSTOM_APP_PACKAGE,
        )

        val ATTENDEE_PROJECTION = arrayOf(
            Attendees._ID,
            Attendees.EVENT_ID,
            Attendees.ATTENDEE_EMAIL,
            Attendees.ATTENDEE_TYPE,
            Attendees.ATTENDEE_RELATIONSHIP,
        )

        fun placeholders(count: Int): String = List(count) { "?" }.joinToString(",")
    }
}

/** The `Attendees.ATTENDEE_STATUS_*` value an [EventResponse] is written as. */
private val EventResponse.attendeeStatus: Int
    get() = when (this) {
        EventResponse.YES -> Attendees.ATTENDEE_STATUS_ACCEPTED
        EventResponse.NO -> Attendees.ATTENDEE_STATUS_DECLINED
        EventResponse.MAYBE -> Attendees.ATTENDEE_STATUS_TENTATIVE
    }

/** Julian day number of 1970-01-01, the epoch of `Instances.START_DAY`/`END_DAY`. */
private const val EPOCH_JULIAN_DAY = 2440588

/** The `Instances.START_DAY`/`END_DAY` value of a local calendar date (zone-independent). */
private val LocalDate.julianDay: Int
    get() = (toEpochDay() + EPOCH_JULIAN_DAY).toInt()

private inline fun <T> Cursor.mapRows(map: (Cursor) -> T): List<T> = buildList {
    while (moveToNext()) add(map(this@mapRows))
}

private fun Cursor.getLong(column: String): Long = getLong(getColumnIndexOrThrow(column))
private fun Cursor.getInt(column: String): Int = getInt(getColumnIndexOrThrow(column))
private fun Cursor.getLongOrNull(column: String): Long? =
    getColumnIndexOrThrow(column).let { if (isNull(it)) null else getLong(it) }
private fun Cursor.getIntOrNull(column: String): Int? =
    getColumnIndexOrThrow(column).let { if (isNull(it)) null else getInt(it) }
private fun Cursor.getStringOrNull(column: String): String? =
    getColumnIndexOrThrow(column).let { if (isNull(it)) null else getString(it) }
