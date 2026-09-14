package com.episode6.meetingminder.alarm

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToLog
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import com.episode6.meetingminder.FullScreenIntentRule
import com.episode6.meetingminder.R
import com.episode6.meetingminder.appGraph
import com.episode6.meetingminder.data.db.AlarmState
import com.episode6.meetingminder.data.db.ScheduledAlarmEntity
import com.episode6.meetingminder.shell
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.time.LocalDate

private const val LOG_TAG = "AlarmRingingDeviceTest"
private const val ALARM_DELAY_MILLIS = 10_000L
private const val RING_TIMEOUT_MILLIS = 45_000L
private const val STEP_TIMEOUT_MILLIS = 15_000L

/**
 * The whole ringing path on a real device (TODO.md PR-10): an alarm armed with
 * `setAlarmClock` 10 s out fires `AlarmReceiver`, which starts `AlarmRingingService`, whose
 * notification's full-screen intent brings up [AlarmActivity] over the lock screen — and
 * Dismiss on that screen marks the row `DISMISSED` and closes it.
 *
 * The screen is switched off first: with the phone in use a full-screen intent is only a
 * heads-up, and the point is the wake-up. The alarm row is inserted and armed the way the
 * "Set alarms" reconcile does it (row first, then `setAlarmClock`); its negative event id
 * matches no calendar event. The same grants as the other device tests are held so the
 * app store's permission snapshot, if this test is what creates it, still routes them to
 * the day view.
 */
@RunWith(AndroidJUnit4::class)
class AlarmRingingDeviceTest {

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
    private var alarmId = 0L

    @After
    fun tearDown() {
        runBlocking {
            if (alarmId != 0L) {
                graph.alarmScheduler.cancel(alarmId)
                val state = graph.scheduledAlarmDao.byId(alarmId)?.state
                if (state != null && state != AlarmState.DISMISSED) graph.scheduledAlarmDao.setState(alarmId, AlarmState.CANCELLED)
            }
        }
        // a failed run may leave it ringing; destroying it silences it and clears the screen
        context.stopService(Intent(context, AlarmRingingService::class.java))
        // leave the phone awake and unlocked for whichever test runs next
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        SystemClock.sleep(1_000)
    }

    @Test
    fun alarmTenSecondsOut_wakesTheScreenIntoTheRingingActivity_andDismissStopsIt() {
        val title = "Ringing device test ${System.nanoTime()}"
        val now = System.currentTimeMillis()
        val row = ScheduledAlarmEntity(
            date = LocalDate.now(),
            eventId = -now,
            instanceTime = 0,
            fireAt = now + ALARM_DELAY_MILLIS,
            title = title,
            beginMillis = now + ALARM_DELAY_MILLIS + 300_000,
            endMillis = now + ALARM_DELAY_MILLIS + 2_100_000,
            soundIndex = 1,
        )
        alarmId = runBlocking { graph.scheduledAlarmDao.insert(row) }
        assertThat(graph.alarmScheduler.schedule(row.copy(alarmId = alarmId))).isTrue()

        shell("input keyevent KEYCODE_SLEEP")
        await("the screen to go off", STEP_TIMEOUT_MILLIS) { !context.getSystemService(PowerManager::class.java).isInteractive }

        await("the ringing activity to come up", RING_TIMEOUT_MILLIS) { resumedActivities().any { it is AlarmActivity } }
        await("the ringing screen to show the meeting", STEP_TIMEOUT_MILLIS) {
            composeRule.onAllNodes(hasText(title)).fetchSemanticsNodes().isNotEmpty()
        }
        assertThat(context.getSystemService(PowerManager::class.java).isInteractive).isTrue()
        assertThat(runBlocking { graph.scheduledAlarmDao.byId(alarmId)?.state }).isEqualTo(AlarmState.FIRED)

        composeRule.onNode(hasText(context.getString(R.string.alarm_dismiss)) and hasClickAction()).performClick()

        await("the alarm to be dismissed", STEP_TIMEOUT_MILLIS) {
            runBlocking { graph.scheduledAlarmDao.byId(alarmId)?.state } == AlarmState.DISMISSED
        }
        await("the ringing service to clear the ringing alarm", STEP_TIMEOUT_MILLIS) { graph.appStore.state.ringing == null }
        await("the ringing activity to close", STEP_TIMEOUT_MILLIS) { resumedActivities().none { it is AlarmActivity } }
    }

    private fun resumedActivities(): List<Activity> {
        var activities = emptyList<Activity>()
        instrumentation.runOnMainSync {
            activities = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).toList()
        }
        return activities
    }

    /**
     * Waits through the compose rule rather than a plain sleep loop: under a compose test the
     * frame clock only advances while the test synchronises with Compose, so a state change
     * (the screen closing itself once nothing rings) is never recomposed by bare polling.
     */
    private fun await(what: String, timeoutMillis: Long, condition: () -> Boolean) {
        try {
            composeRule.waitUntil(timeoutMillis) { runCatching(condition).getOrDefault(false) }
        } catch (e: ComposeTimeoutException) {
            dumpDiagnostics()
            throw AssertionError("timed out waiting for $what", e)
        }
    }

    private fun dumpDiagnostics() {
        runCatching { composeRule.onAllNodes(isRoot()).printToLog(LOG_TAG, maxDepth = Int.MAX_VALUE) }
        for (command in listOf("dumpsys activity activities", "dumpsys notification --noredact", "dumpsys power")) {
            val output = runCatching { shell(command) }.getOrElse { it.toString() }
            Log.i(LOG_TAG, "---- $command")
            output.chunked(3_000).forEach { Log.i(LOG_TAG, it) }
        }
    }
}
