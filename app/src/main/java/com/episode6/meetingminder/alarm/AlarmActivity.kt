package com.episode6.meetingminder.alarm

import android.app.KeyguardManager
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.episode6.meetingminder.R
import com.episode6.meetingminder.appGraph
import com.episode6.meetingminder.model.RingingAlarm
import com.episode6.meetingminder.ui.alarm.AlarmRingingScreen
import com.episode6.meetingminder.ui.alarm.AlarmRingingUiState
import com.episode6.meetingminder.ui.alarm.AlarmRingingViewModel
import com.episode6.meetingminder.ui.alarm.ScheduleChangeAlertScreen
import com.episode6.meetingminder.ui.navigation.DeepLinks
import com.episode6.meetingminder.ui.navigation.openInCalendar
import com.episode6.meetingminder.ui.theme.MeetingMinderTheme
import dev.zacsweers.metrox.viewmodel.LocalMetroViewModelFactory
import dev.zacsweers.metrox.viewmodel.metroViewModel

/**
 * The full-screen ringing screen (TODO.md §4.4, render 5). Launched only by the ringing
 * notification's full-screen intent — never `startActivity` from the service, which
 * background-activity-launch rules block — so it wakes the screen (`turnScreenOn`) and
 * shows over the lock screen (`showWhenLocked`) when the phone is off or locked, and opens
 * from the heads-up when it's in use. It keeps the screen on, lives in its own task
 * (`singleInstance`, empty affinity, excluded from recents), and hosts the Compose
 * [AlarmRingingScreen] as the wiring layer for [AlarmRingingViewModel].
 *
 * A day's schedule-change alert (TODO.md §4.3) is hosted here too, as [ScheduleChangeAlertScreen];
 * its "Open itinerary" and "Sync & Re-share" go to `MainActivity` by deep link, past the
 * keyguard like "Open meeting". Either volume key silences whatever is making a sound.
 *
 * Dismiss, Snooze and Silence never need an unlock. Back is disabled (TODO.md §5): only a choice
 * silences a ringing alarm. "Open meeting" asks the keyguard to go away first
 * (`requestDismissKeyguard`), then dismisses the alarm and opens the event in the calendar.
 * The activity closes once nothing rings any more.
 */
class AlarmActivity : ComponentActivity() {

    // the same instance the composition's metroViewModel() gets: same owner, same default key
    private val volumeKeyViewModel: AlarmRingingViewModel by viewModels { appGraph.metroViewModelFactory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        onBackPressedDispatcher.addCallback(this) {
            // disabled: a ringing alarm is only silenced by Dismiss or Snooze
        }
        setContent {
            CompositionLocalProvider(LocalMetroViewModelFactory provides appGraph.metroViewModelFactory) {
                MeetingMinderTheme(darkTheme = true) {
                    AlarmRingingWiring()
                }
            }
        }
    }

    @Composable
    private fun AlarmRingingWiring() {
        val viewModel: AlarmRingingViewModel = metroViewModel()
        val state by viewModel.state.collectAsStateWithLifecycle()
        when (val current = state) {
            is AlarmRingingUiState.Ringing -> AlarmRingingScreen(
                state = current.screen,
                onDismiss = { viewModel.onDismiss(current.alarm.alarmId) },
                onSnooze = { viewModel.onSnooze(current.alarm.alarmId) },
                onOpenMeeting = { openMeeting(current.alarm, viewModel) },
                onSilence = { viewModel.onSilence(current.alarm.alarmId) },
            )
            is AlarmRingingUiState.ScheduleChanged -> ScheduleChangeAlertScreen(
                state = current.screen,
                onReshare = { whenUnlocked { startActivity(DeepLinks.activityIntent(this, DeepLinks.share(current.alarm.date))) } },
                onOpenItinerary = {
                    whenUnlocked {
                        viewModel.onOpenItinerary(current.alarm.alarmId)
                        startActivity(DeepLinks.activityIntent(this, DeepLinks.day(current.alarm.date)))
                    }
                },
                onSilence = { viewModel.onSilence(current.alarm.alarmId) },
                onDismiss = { viewModel.onDismiss(current.alarm.alarmId) },
            )
            AlarmRingingUiState.Waiting -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
            AlarmRingingUiState.Finished -> LaunchedEffect(Unit) { finish() }
        }
    }

    /**
     * Either volume key silences what is making a sound, as for an incoming call, and the
     * press is consumed so the volume panel doesn't come up for it. Once silent (or with
     * nothing ringing) the keys are ordinary volume keys again.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) {
            if (volumeKeyViewModel.onVolumeKey()) return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun openMeeting(alarm: RingingAlarm, viewModel: AlarmRingingViewModel) = whenUnlocked {
        viewModel.onOpenMeeting(alarm.alarmId)
        // the key's event id: the series for a recurring occurrence, which the calendar
        // app opens at this instance's begin/end. The alarm is dismissed either way and
        // this activity is about to close, so a toast is what can still say "no app".
        if (!openInCalendar(alarm.key.eventId, alarm.begin, alarm.end)) {
            Toast.makeText(this, R.string.open_in_calendar_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /** Runs [action] at once, or once the keyguard has gone away for it (never, if the user backs out of unlocking). */
    private fun whenUnlocked(action: () -> Unit) {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!keyguard.isKeyguardLocked) {
            action()
            return
        }
        keyguard.requestDismissKeyguard(
            this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    action()
                }
            },
        )
    }
}
