package com.episode6.meetingminder.ui.day

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.printToLog
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import assertk.assertThat
import assertk.assertions.contains
import com.episode6.meetingminder.MainActivity
import com.episode6.meetingminder.data.calendar.ContentResolverCalendarRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

private const val LOAD_TIMEOUT_MILLIS = 15_000L
private const val LOG_TAG = "DayViewDeviceTest"

/**
 * The day view against the real Calendar Provider (TODO.md PR-6): an event inserted into a
 * `LOCAL` calendar shows up on today's page, both when it exists before launch (the pager's
 * `LoadDay`) and when it is inserted while the day is on screen (the foreground
 * `ContentObserver` → `CalendarContentChanged` reload). The calendar is deleted afterwards,
 * which cascades to its events.
 *
 * The store's action log is switched on for the run, and a timed-out wait prints the
 * semantics tree, so a failure's logcat shows which actions ran and what was on screen.
 */
@RunWith(AndroidJUnit4::class)
class DayViewDeviceTest {

    private val permissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
    private val composeRule = createEmptyComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(permissionRule).around(composeRule)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val resolver = instrumentation.targetContext.contentResolver
    private val zone: ZoneId = ZoneId.systemDefault()
    private val account = "meeting-minder-day-test@local"
    private var calendarId = -1L

    @Before
    fun setUp() {
        instrumentation.uiAutomation.executeShellCommand("setprop log.tag.MeetingMinderStore DEBUG").close()
        val uri = resolver.insert(
            Calendars.CONTENT_URI.asSyncAdapter(),
            ContentValues().apply {
                put(Calendars.ACCOUNT_NAME, account)
                put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
                put(Calendars.NAME, "meeting-minder-day-test")
                put(Calendars.CALENDAR_DISPLAY_NAME, "Meeting Minder day test")
                put(Calendars.CALENDAR_COLOR, 0xFF3F51B5.toInt())
                put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
                put(Calendars.OWNER_ACCOUNT, account)
                put(Calendars.VISIBLE, 1)
                put(Calendars.SYNC_EVENTS, 1)
                put(Calendars.CALENDAR_TIME_ZONE, zone.id)
            },
        ) ?: error("calendar insert returned null")
        calendarId = ContentUris.parseId(uri)
    }

    @After
    fun deleteCalendar() {
        resolver.delete(ContentUris.withAppendedId(Calendars.CONTENT_URI, calendarId).asSyncAdapter(), null, null)
    }

    @Test
    fun eventInsertedBeforeLaunch_appearsOnToday() {
        val title = "Inserted before launch ${System.nanoTime()}"
        insertEventToday(title)
        // the same query the app runs: if this fails the provider is at fault, not the UI
        val seenByRepository = runBlocking { ContentResolverCalendarRepository(resolver).eventsOn(LocalDate.now(zone)) }
        assertThat(seenByRepository.map { it.title }).contains(title)

        ActivityScenario.launch(MainActivity::class.java).use {
            awaitPager()
            awaitChip(title)
        }
    }

    @Test
    fun eventInsertedWhileTheDayIsShowing_appearsWithoutRelaunching() {
        val title = "Inserted while showing ${System.nanoTime()}"

        ActivityScenario.launch(MainActivity::class.java).use {
            awaitPager()
            insertEventToday(title)

            awaitChip(title)
        }
    }

    private fun awaitPager() = awaitNode("the day pager") {
        composeRule.onAllNodes(hasTestTag(DAY_PAGER_TEST_TAG)).fetchSemanticsNodes().isNotEmpty()
    }

    // chips are composed for the whole day (the timeline scrolls, it isn't lazy), so the
    // chip exists whether or not noon is currently scrolled into view
    private fun awaitChip(title: String) = awaitNode("the chip \"$title\"") {
        composeRule.onAllNodes(hasText(title, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun awaitNode(what: String, condition: () -> Boolean) {
        try {
            composeRule.waitUntil(LOAD_TIMEOUT_MILLIS) { runCatching(condition).getOrDefault(false) }
        } catch (e: ComposeTimeoutException) {
            runCatching { composeRule.onAllNodes(isRoot()).printToLog(LOG_TAG) }
            throw AssertionError("timed out waiting for $what", e)
        }
    }

    private fun insertEventToday(title: String) {
        val today = LocalDate.now(zone)
        resolver.insert(
            Events.CONTENT_URI,
            ContentValues().apply {
                put(Events.CALENDAR_ID, calendarId)
                put(Events.TITLE, title)
                put(Events.DTSTART, today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli())
                put(Events.DTEND, today.atTime(13, 0).atZone(zone).toInstant().toEpochMilli())
                put(Events.EVENT_TIMEZONE, zone.id)
            },
        ) ?: error("event insert returned null")
    }

    private fun Uri.asSyncAdapter(): Uri = buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(Calendars.ACCOUNT_NAME, account)
        .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
        .build()
}
