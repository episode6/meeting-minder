package com.episode6.meetingminder.data.calendar

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * A stand-in for `com.android.calendar` under Robolectric. Rows live in an in-memory
 * SQLite database so the repository's real projections, selections and bound arguments
 * are honoured; the only provider logic emulated is the `instances/when/{begin}/{end}`
 * overlap match, and `START_DAY`/`END_DAY` are computed at seeding time exactly the way
 * `CalendarInstancesHelper` does (local time for timed events, UTC for all-day ones, an
 * event ending at midnight belongs to the previous day).
 *
 * The two RSVP writes (TODO.md §4.6) are accepted and recorded: an `update` on
 * `attendees/{id}` changes that row's status in place ([updates]), and an `insert` on
 * `exception/{eventId}` hands out a fresh event id ([inserts]) without cloning anything —
 * the real provider's exception logic is not emulated, only its addressing.
 */
class FakeCalendarProvider : ContentProvider() {

    private lateinit var db: SQLiteDatabase

    /** Every query URI seen, so tests can assert on the number of provider round trips. */
    val queriedUris = mutableListOf<Uri>()

    /** Every `update` call, in order. */
    val updates = mutableListOf<Pair<Uri, ContentValues>>()

    /** Every `insert` call, in order. */
    val inserts = mutableListOf<Pair<Uri, ContentValues>>()

    /** The id the next exception insert returns; incremented per insert. */
    var nextExceptionId: Long = 1_000

    override fun onCreate(): Boolean {
        db = SQLiteDatabase.create(null)
        db.execSQL(
            """CREATE TABLE $CALENDARS (
                ${Calendars._ID} INTEGER PRIMARY KEY, ${Calendars.ACCOUNT_NAME} TEXT, ${Calendars.ACCOUNT_TYPE} TEXT,
                ${Calendars.CALENDAR_DISPLAY_NAME} TEXT, ${Calendars.CALENDAR_COLOR} INTEGER, ${Calendars.VISIBLE} INTEGER,
                ${Calendars.SYNC_EVENTS} INTEGER, ${Calendars.OWNER_ACCOUNT} TEXT, ${Calendars.IS_PRIMARY} INTEGER,
                ${Calendars.CALENDAR_ACCESS_LEVEL} INTEGER, ${Calendars.CAN_ORGANIZER_RESPOND} INTEGER)""",
        )
        db.execSQL(
            """CREATE TABLE $INSTANCES (
                ${Instances._ID} INTEGER PRIMARY KEY, ${Instances.EVENT_ID} INTEGER, ${Instances.BEGIN} INTEGER,
                ${Instances.END} INTEGER, ${Instances.START_DAY} INTEGER, ${Instances.END_DAY} INTEGER,
                ${Instances.TITLE} TEXT, ${Instances.EVENT_LOCATION} TEXT, ${Instances.ALL_DAY} INTEGER,
                ${Instances.CALENDAR_ID} INTEGER, ${Instances.SELF_ATTENDEE_STATUS} INTEGER, ${Instances.STATUS} INTEGER,
                ${Instances.DISPLAY_COLOR} INTEGER, ${Instances.CALENDAR_COLOR} INTEGER, ${Instances.ORGANIZER} TEXT,
                ${Instances.IS_ORGANIZER} INTEGER, ${Instances.HAS_ATTENDEE_DATA} INTEGER, ${Instances.AVAILABILITY} INTEGER,
                ${Instances.RRULE} TEXT, ${Instances.RDATE} TEXT, ${Instances.ORIGINAL_ID} INTEGER,
                ${Instances.ORIGINAL_INSTANCE_TIME} INTEGER, ${Events.DELETED} INTEGER, ${Events.DIRTY} INTEGER, ${Instances.OWNER_ACCOUNT} TEXT,
                ${Instances.CALENDAR_ACCESS_LEVEL} INTEGER, ${Instances.VISIBLE} INTEGER, ${Instances.EVENT_TIMEZONE} TEXT)""",
        )
        db.execSQL(
            """CREATE TABLE $ATTENDEES (
                ${Attendees._ID} INTEGER PRIMARY KEY, ${Attendees.EVENT_ID} INTEGER, ${Attendees.ATTENDEE_EMAIL} TEXT,
                ${Attendees.ATTENDEE_TYPE} INTEGER, ${Attendees.ATTENDEE_RELATIONSHIP} INTEGER, ${Attendees.ATTENDEE_STATUS} INTEGER)""",
        )
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor {
        queriedUris += uri
        return when (matcher.match(uri)) {
            MATCH_CALENDARS -> db.query(CALENDARS, projection, selection, selectionArgs, null, null, sortOrder)
            MATCH_ATTENDEES -> db.query(ATTENDEES, projection, selection, selectionArgs, null, null, sortOrder)
            MATCH_INSTANCES_WHEN -> {
                // content://com.android.calendar/instances/when/{begin}/{end} returns every
                // instance overlapping the window, inclusive on both ends exactly like
                // CalendarProvider2.SQL_WHERE_INSTANCES_BETWEEN (so a zero-duration event
                // sitting on the window edge is returned too)
                val begin = uri.pathSegments[2].toLong()
                val end = uri.pathSegments[3].toLong()
                val overlap = "${Instances.END} >= $begin AND ${Instances.BEGIN} <= $end"
                val where = if (selection.isNullOrBlank()) overlap else "$overlap AND ($selection)"
                db.query(INSTANCES, projection, where, selectionArgs, null, null, sortOrder)
            }
            else -> throw IllegalArgumentException("Unsupported uri $uri")
        }
    }

    fun addCalendar(
        id: Long,
        ownerAccount: String = "me@example.com",
        accountName: String = ownerAccount,
        accountType: String = "com.google",
        displayName: String = "Calendar $id",
        color: Int = 0xFF3F51B5.toInt(),
        visible: Boolean = true,
        syncEvents: Boolean = true,
        isPrimary: Boolean? = null,
        accessLevel: Int = Calendars.CAL_ACCESS_OWNER,
        canOrganizerRespond: Boolean = true,
    ) {
        db.insertOrThrow(
            CALENDARS,
            null,
            ContentValues().apply {
                put(Calendars._ID, id)
                put(Calendars.ACCOUNT_NAME, accountName)
                put(Calendars.ACCOUNT_TYPE, accountType)
                put(Calendars.CALENDAR_DISPLAY_NAME, displayName)
                put(Calendars.CALENDAR_COLOR, color)
                put(Calendars.VISIBLE, visible.toInt())
                put(Calendars.SYNC_EVENTS, syncEvents.toInt())
                put(Calendars.OWNER_ACCOUNT, ownerAccount)
                if (isPrimary != null) put(Calendars.IS_PRIMARY, isPrimary.toInt())
                put(Calendars.CALENDAR_ACCESS_LEVEL, accessLevel)
                put(Calendars.CAN_ORGANIZER_RESPOND, canOrganizerRespond.toInt())
            },
        )
    }

    /**
     * One expanded instance, joined with its calendar's columns the way the provider's
     * `Instances` view is. [zone] is the "instances timezone" the provider computed the
     * Julian days in (the device zone); all-day events use UTC regardless.
     */
    fun addInstance(
        instanceId: Long,
        eventId: Long,
        begin: Long,
        end: Long,
        zone: ZoneId,
        calendarId: Long = 1,
        title: String? = "Event $eventId",
        location: String? = null,
        allDay: Boolean = false,
        selfStatus: Int? = Attendees.ATTENDEE_STATUS_NONE,
        status: Int? = Events.STATUS_CONFIRMED,
        displayColor: Int? = 0xFF3F51B5.toInt(),
        calendarColor: Int? = 0xFF3F51B5.toInt(),
        organizer: String? = null,
        isOrganizer: Int? = null,
        hasAttendeeData: Boolean = true,
        availability: Int? = Events.AVAILABILITY_BUSY,
        rrule: String? = null,
        rdate: String? = null,
        originalId: Long? = null,
        originalInstanceTime: Long? = null,
        deleted: Boolean = false,
        dirty: Boolean = false,
        ownerAccount: String? = "me@example.com",
        accessLevel: Int = Calendars.CAL_ACCESS_OWNER,
        visible: Boolean = true,
    ) {
        val dayZone = if (allDay) ZoneOffset.UTC else zone
        val startDay = julianDay(begin, dayZone)
        var endDay = julianDay(end, dayZone)
        // CalendarInstancesHelper.computeTimezoneDependentFields: an event ending exactly at
        // midnight belongs to the previous day (unless it starts and ends at that midnight)
        if (endDay > startDay && isLocalMidnight(end, dayZone)) endDay -= 1
        db.insertOrThrow(
            INSTANCES,
            null,
            ContentValues().apply {
                put(Instances._ID, instanceId)
                put(Instances.EVENT_ID, eventId)
                put(Instances.BEGIN, begin)
                put(Instances.END, end)
                put(Instances.START_DAY, startDay)
                put(Instances.END_DAY, endDay)
                put(Instances.TITLE, title)
                put(Instances.EVENT_LOCATION, location)
                put(Instances.ALL_DAY, allDay.toInt())
                put(Instances.CALENDAR_ID, calendarId)
                put(Instances.SELF_ATTENDEE_STATUS, selfStatus)
                put(Instances.STATUS, status)
                put(Instances.DISPLAY_COLOR, displayColor)
                put(Instances.CALENDAR_COLOR, calendarColor)
                put(Instances.ORGANIZER, organizer)
                put(Instances.IS_ORGANIZER, isOrganizer)
                put(Instances.HAS_ATTENDEE_DATA, hasAttendeeData.toInt())
                put(Instances.AVAILABILITY, availability)
                put(Instances.RRULE, rrule)
                put(Instances.RDATE, rdate)
                put(Instances.ORIGINAL_ID, originalId)
                put(Instances.ORIGINAL_INSTANCE_TIME, originalInstanceTime)
                put(Events.DELETED, deleted.toInt())
                put(Events.DIRTY, dirty.toInt())
                put(Instances.OWNER_ACCOUNT, ownerAccount)
                put(Instances.CALENDAR_ACCESS_LEVEL, accessLevel)
                put(Instances.VISIBLE, visible.toInt())
                put(Instances.EVENT_TIMEZONE, dayZone.id)
            },
        )
    }

    fun addAttendee(
        id: Long,
        eventId: Long,
        email: String,
        type: Int = Attendees.TYPE_REQUIRED,
        relationship: Int = Attendees.RELATIONSHIP_ATTENDEE,
        status: Int = Attendees.ATTENDEE_STATUS_INVITED,
    ) {
        db.insertOrThrow(
            ATTENDEES,
            null,
            ContentValues().apply {
                put(Attendees._ID, id)
                put(Attendees.EVENT_ID, eventId)
                put(Attendees.ATTENDEE_EMAIL, email)
                put(Attendees.ATTENDEE_TYPE, type)
                put(Attendees.ATTENDEE_RELATIONSHIP, relationship)
                put(Attendees.ATTENDEE_STATUS, status)
            },
        )
    }

    /** One attendee row's status, for asserting on an RSVP update. */
    fun attendeeStatus(id: Long): Int? =
        db.query(ATTENDEES, arrayOf(Attendees.ATTENDEE_STATUS), "${Attendees._ID} = ?", arrayOf(id.toString()), null, null, null)
            .use { if (it.moveToFirst()) it.getInt(0) else null }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        inserts += uri to ContentValues(values)
        return when (matcher.match(uri)) {
            // CalendarProvider2 answers an exception insert with the new event's events/{id} uri
            MATCH_EXCEPTION_ID -> ContentUris.withAppendedId(Events.CONTENT_URI, nextExceptionId++)
            else -> throw UnsupportedOperationException("insert on $uri")
        }
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = throw UnsupportedOperationException()

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int {
        updates += uri to ContentValues(values)
        return when (matcher.match(uri)) {
            MATCH_ATTENDEES_ID -> db.update(ATTENDEES, values, "${Attendees._ID} = ?", arrayOf(uri.lastPathSegment))
            else -> throw UnsupportedOperationException("update on $uri")
        }
    }

    private companion object {
        const val CALENDARS = "calendars"
        const val INSTANCES = "instances"
        const val ATTENDEES = "attendees"
        const val MATCH_CALENDARS = 1
        const val MATCH_INSTANCES_WHEN = 2
        const val MATCH_ATTENDEES = 3
        const val MATCH_ATTENDEES_ID = 4
        const val MATCH_EXCEPTION_ID = 5
        const val EPOCH_JULIAN_DAY = 2440588

        val matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(CalendarContract.AUTHORITY, "calendars", MATCH_CALENDARS)
            addURI(CalendarContract.AUTHORITY, "instances/when/#/#", MATCH_INSTANCES_WHEN)
            addURI(CalendarContract.AUTHORITY, "attendees", MATCH_ATTENDEES)
            addURI(CalendarContract.AUTHORITY, "attendees/#", MATCH_ATTENDEES_ID)
            addURI(CalendarContract.AUTHORITY, "exception/#", MATCH_EXCEPTION_ID)
        }

        fun julianDay(millis: Long, zone: ZoneId): Int =
            (Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toEpochDay() + EPOCH_JULIAN_DAY).toInt()

        fun isLocalMidnight(millis: Long, zone: ZoneId): Boolean {
            val local = Instant.ofEpochMilli(millis).atZone(zone)
            return local.toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli() == millis
        }

        fun Boolean.toInt() = if (this) 1 else 0
    }
}

/** The instant [time] on [date] in [zone], as provider millis. */
internal fun LocalDate.at(hour: Int, minute: Int = 0, zone: ZoneId): Long =
    atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

/** UTC midnight of [this], which is how the provider stores an all-day event's `BEGIN`. */
internal fun LocalDate.utcMidnight(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
