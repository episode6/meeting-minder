package com.episode6.meetingminder.share

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.util.Log
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToLog
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.doesNotContain
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.episode6.meetingminder.FullScreenIntentRule
import com.episode6.meetingminder.MainActivity
import com.episode6.meetingminder.R
import com.episode6.meetingminder.appGraph
import com.episode6.meetingminder.model.EventKey
import com.episode6.meetingminder.shell
import com.episode6.meetingminder.store.CalendarContentChanged
import com.episode6.meetingminder.store.MarkNotShared
import com.episode6.meetingminder.store.SetAlarms
import com.episode6.meetingminder.store.ShareFinished
import com.episode6.meetingminder.store.ToggleEvent
import com.episode6.meetingminder.store.startShare
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private const val LOG_TAG = "BusyCalendarSyncDeviceTest"
private const val STEP_TIMEOUT_MILLIS = 20_000L
private const val CHOOSER_TIMEOUT_MILLIS = 10_000L

/**
 * Busy-calendar sync end to end on a real device (TODO.md §4.7): with the feature pointed
 * at a `LOCAL` calendar called "Family", tapping the day view's **Sync & Share** puts one
 * bare `busy` event on that calendar for the day's busy range — title, times and nothing
 * else — the app never shows that event back to itself, and a re-share with nothing
 * selected takes it away again.
 *
 * The meeting is seeded just after midnight so its alarm time is always in the past: "Set
 * alarms" then skips it (which still records `alarms_set_at`, so the FAB turns into
 * "Sync & Share") and the run never leaves an armed alarm behind whatever time of day CI
 * runs at. The selection and the two shares are dispatched to the app store rather than
 * tapped, because a chip tap depends on where the timeline happens to be scrolled and
 * "Share again" lives behind the overflow — the FAB tap, which is the label this feature
 * changes, is the one that goes through the UI. Everything is waited for with
 * `composeRule.waitUntil`: under a compose test rule the frame clock only advances while
 * the test synchronises with Compose, so a plain sleep loop would never see a recomposition.
 */
@RunWith(AndroidJUnit4::class)
class BusyCalendarSyncDeviceTest {

    private val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR,
        Manifest.permission.POST_NOTIFICATIONS,
    )
    private val composeRule = createEmptyComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(permissionRule).around(FullScreenIntentRule()).around(composeRule)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val graph = context.appGraph
    private val resolver = context.contentResolver
    private val zone: ZoneId = ZoneId.systemDefault()
    private val account = "meeting-minder-busy-test@local"
    private val title = "Sync device test ${System.nanoTime()}"
    private val today: LocalDate = LocalDate.now(zone)
    private val begin: Instant = today.atTime(0, 5).atZone(zone).toInstant()
    private val end: Instant = today.atTime(0, 35).atZone(zone).toInstant()
    private var calendarId = -1L
    private var meetingId = -1L

    @Before
    fun setUp() {
        calendarId = insertFamilyCalendar()
        meetingId = insertMeeting()
        runBlocking { graph.settingsRepository.setBusySync(enabled = true, calendarId = calendarId) }
    }

    @After
    fun tearDown() {
        runBlocking {
            graph.settingsRepository.setBusySync(enabled = false, calendarId = null)
            // the calendar delete below takes the provider rows; drop any row still naming them
            for (row in graph.busyBlockDao.blocksOn(today)) graph.busyBlockDao.delete(row.eventId)
        }
        resolver.delete(ContentUris.withAppendedId(Calendars.CONTENT_URI, calendarId).asSyncAdapter(), null, null)
    }

    @Test
    fun syncAndShare_writesOneBareBusyBlock_hidesItFromTheApp_andARoundTripTakesItAway() {
        ActivityScenario.launch(MainActivity::class.java).use {
            await("the seeded meeting to load") { loadedKey() != null }
            val key = loadedKey()!!

            graph.appStore.dispatch(ToggleEvent(today, key))
            await("the meeting to be selected") { graph.appStore.state.dayPlans[today]?.selected?.containsKey(key) == true }
            graph.appStore.dispatch(SetAlarms(today))
            await("the FAB to offer \"Sync & Share\"") { syncAndShareFab().isNotEmpty() }

            composeRule.onNode(hasText(context.getString(R.string.day_fab_sync_share)) and hasClickAction()).performClick()

            await("the busy block on the Family calendar") { busyBlocks().size == 1 }
            val block = busyBlocks().single()
            assertThat(block.title).isEqualTo("busy")
            assertThat(block.begin).isEqualTo(begin.toEpochMilli())
            assertThat(block.end).isEqualTo(end.toEpochMilli())
            // nothing about the meeting itself ever reaches the partner's calendar
            assertThat(block.description).isNull()
            assertThat(block.location).isNull()
            assertThat(attendeeCount(block.id)).isEqualTo(0)
            assertThat(runBlocking { graph.busyBlockDao.blocksOn(today) }.map { it.eventId }).containsExactly(block.id)
            dismissChooser()

            // the block sits on a visible calendar, so only excludeOwnBlocks keeps it out of
            // the itinerary — and out of anything a later share or check could fold it into
            val loadedBefore = graph.appStore.state.eventsByDay[today]?.loadedAt
            graph.appStore.dispatch(CalendarContentChanged)
            await("the day to be re-read after the write") { graph.appStore.state.eventsByDay[today]?.loadedAt != loadedBefore }
            val reloaded = graph.appStore.state.eventsByDay.getValue(today).events.map { it.eventId }
            assertThat(reloaded).contains(meetingId)
            assertThat(reloaded).doesNotContain(block.id)
            assertThat(composeRule.onAllNodes(hasText("busy")).fetchSemanticsNodes()).isEmpty()

            // deselect and re-share (what the overflow's "Share again" dispatches): an empty
            // day's sync reconciles to nothing, so the block goes away again
            graph.appStore.dispatch(ToggleEvent(today, key))
            await("the selection to clear") { graph.appStore.state.dayPlans[today]?.selected.orEmpty().isEmpty() }
            if (!waitFor(STEP_TIMEOUT_MILLIS) { !graph.appStore.state.shareInFlight }) graph.appStore.dispatch(ShareFinished)
            graph.appStore.startShare(today)

            await("the busy block to be deleted again") { busyBlocks().isEmpty() }
            assertThat(runBlocking { graph.busyBlockDao.blocksOn(today) }).isEmpty()
            dismissChooser()

            // leave the day as we found it for whatever test runs next
            graph.appStore.dispatch(MarkNotShared(today))
            await("the day to be marked not shared") { graph.appStore.state.dayPlans[today]?.sharedAt == null }
        }
    }

    /** The seeded meeting's key, once the day view has loaded it (null until then). */
    private fun loadedKey(): EventKey? =
        graph.appStore.state.eventsByDay[today]?.events?.firstOrNull { it.title == title }?.key

    private fun syncAndShareFab() =
        composeRule.onAllNodes(hasText(context.getString(R.string.day_fab_sync_share)) and hasClickAction()).fetchSemanticsNodes()

    /** One `Events` row on the Family calendar, as the partner's calendar would see it. */
    private data class Block(val id: Long, val title: String?, val description: String?, val location: String?, val begin: Long, val end: Long)

    private fun busyBlocks(): List<Block> = resolver.query(
        Events.CONTENT_URI,
        arrayOf(Events._ID, Events.TITLE, Events.DESCRIPTION, Events.EVENT_LOCATION, Events.DTSTART, Events.DTEND),
        "${Events.CALENDAR_ID} = ? AND ${Events.DELETED} = 0",
        arrayOf(calendarId.toString()),
        Events._ID,
    )?.use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                if (id == meetingId) continue
                add(Block(id, cursor.getString(1), cursor.getString(2), cursor.getString(3), cursor.getLong(4), cursor.getLong(5)))
            }
        }
    }.orEmpty()

    private fun attendeeCount(eventId: Long): Int = resolver.query(
        Attendees.CONTENT_URI,
        arrayOf(Attendees._ID),
        "${Attendees.EVENT_ID} = ?",
        arrayOf(eventId.toString()),
        null,
    )?.use { it.count } ?: 0

    /**
     * The share chooser the FAB opened is a system activity on top of ours; back it out so
     * the day view is in front again for the next step. Tolerant of a device where nothing
     * came up: pressing back at the day view would close the app under the test.
     */
    private fun dismissChooser() {
        if (!waitFor(CHOOSER_TIMEOUT_MILLIS) { resumedActivities().none { it is MainActivity } }) {
            Log.w(LOG_TAG, "no share chooser came up; nothing to dismiss")
            return
        }
        shell("input keyevent KEYCODE_BACK")
        await("the day view to come back") { resumedActivities().any { it is MainActivity } }
    }

    private fun resumedActivities(): List<Activity> {
        var activities = emptyList<Activity>()
        instrumentation.runOnMainSync {
            activities = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).toList()
        }
        return activities
    }

    private fun await(what: String, condition: () -> Boolean) {
        if (!waitFor(STEP_TIMEOUT_MILLIS, condition)) {
            runCatching { composeRule.onAllNodes(isRoot()).printToLog(LOG_TAG, maxDepth = Int.MAX_VALUE) }
            throw AssertionError("timed out waiting for $what")
        }
    }

    /** [condition] under the compose rule's clock (see the class doc); false on timeout. */
    private fun waitFor(timeoutMillis: Long, condition: () -> Boolean): Boolean = try {
        composeRule.waitUntil(timeoutMillis) { runCatching(condition).getOrDefault(false) }
        true
    } catch (_: ComposeTimeoutException) {
        false
    }

    private fun insertFamilyCalendar(): Long {
        val uri = resolver.insert(
            Calendars.CONTENT_URI.asSyncAdapter(),
            ContentValues().apply {
                put(Calendars.ACCOUNT_NAME, account)
                put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
                put(Calendars.NAME, "meeting-minder-busy-test")
                put(Calendars.CALENDAR_DISPLAY_NAME, "Family")
                put(Calendars.CALENDAR_COLOR, 0xFF6600.toInt())
                put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
                put(Calendars.OWNER_ACCOUNT, account)
                put(Calendars.VISIBLE, 1)
                put(Calendars.SYNC_EVENTS, 1)
                put(Calendars.CALENDAR_TIME_ZONE, zone.id)
            },
        ) ?: error("calendar insert returned null")
        return ContentUris.parseId(uri)
    }

    /** A real meeting: two attendees with our own row already accepted, so nothing RSVPs. */
    private fun insertMeeting(): Long {
        val uri = resolver.insert(
            Events.CONTENT_URI,
            ContentValues().apply {
                put(Events.CALENDAR_ID, calendarId)
                put(Events.TITLE, title)
                put(Events.DTSTART, begin.toEpochMilli())
                put(Events.DTEND, end.toEpochMilli())
                put(Events.EVENT_TIMEZONE, zone.id)
                put(Events.HAS_ATTENDEE_DATA, 1)
                put(Events.AVAILABILITY, Events.AVAILABILITY_BUSY)
            },
        ) ?: error("event insert returned null")
        val eventId = ContentUris.parseId(uri)
        insertAttendee(eventId, account, Attendees.ATTENDEE_STATUS_ACCEPTED)
        insertAttendee(eventId, "someone-else@example.com", Attendees.ATTENDEE_STATUS_ACCEPTED)
        return eventId
    }

    private fun insertAttendee(eventId: Long, email: String, status: Int) {
        resolver.insert(
            Attendees.CONTENT_URI,
            ContentValues().apply {
                put(Attendees.EVENT_ID, eventId)
                put(Attendees.ATTENDEE_EMAIL, email)
                put(Attendees.ATTENDEE_NAME, email)
                put(Attendees.ATTENDEE_RELATIONSHIP, Attendees.RELATIONSHIP_ATTENDEE)
                put(Attendees.ATTENDEE_TYPE, Attendees.TYPE_REQUIRED)
                put(Attendees.ATTENDEE_STATUS, status)
            },
        ) ?: error("attendee insert returned null")
    }

    private fun Uri.asSyncAdapter(): Uri = buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(Calendars.ACCOUNT_NAME, account)
        .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
        .build()
}
