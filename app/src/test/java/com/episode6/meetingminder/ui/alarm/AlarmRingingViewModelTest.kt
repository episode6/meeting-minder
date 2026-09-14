package com.episode6.meetingminder.ui.alarm

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isLessThan
import com.episode6.meetingminder.model.EventKey
import com.episode6.meetingminder.model.RingingAlarm
import com.episode6.meetingminder.store.AppState
import com.episode6.meetingminder.store.DismissAlarm
import com.episode6.meetingminder.store.SetRinging
import com.episode6.meetingminder.store.SnoozeAlarm
import com.episode6.meetingminder.store.createAppStore
import com.episode6.meetingminder.store.sideeffects.sideEffect
import com.episode6.redux.Action
import com.episode6.redux.sideeffects.SideEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class AlarmRingingViewModelTest {

    private val today = LocalDate.of(2026, 9, 14)
    private val clock = Clock.fixed(Instant.parse("2026-09-14T09:55:20Z"), ZoneOffset.UTC)
    private val alarm = RingingAlarm(
        alarmId = 3,
        date = today,
        key = EventKey(1, 0),
        title = "Design review: alarms flow",
        location = "Google Meet",
        begin = Instant.parse("2026-09-14T10:00:00Z"),
        end = Instant.parse("2026-09-14T11:00:00Z"),
        soundIndex = 1,
        snoozeLength = Duration.ofMinutes(2),
        soundName = "Siren sweep",
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.store(ringing: RingingAlarm?, sideEffects: Set<SideEffect<AppState>> = emptySet()) =
        createAppStore(backgroundScope, AppState(anchorDate = today, ringing = ringing), sideEffects)

    private suspend fun ReceiveTurbine<AlarmRingingUiState>.awaitMatching(predicate: (AlarmRingingUiState) -> Boolean): AlarmRingingUiState {
        while (true) {
            val item = awaitItem()
            if (predicate(item)) return item
        }
    }

    @Test
    fun aRingingAlarm_isShownWithTheClockAndTheCountdown() = runTest {
        val viewModel = AlarmRingingViewModel(store(alarm), clock)

        viewModel.state.test {
            assertThat(awaitMatching { it is AlarmRingingUiState.Ringing }).isEqualTo(
                AlarmRingingUiState.Ringing(
                    alarm,
                    AlarmRingingScreenState(
                        title = "Design review: alarms flow",
                        location = "Google Meet",
                        begin = LocalTime.of(10, 0),
                        end = LocalTime.of(11, 0),
                        now = LocalTime.of(9, 55, 20),
                        minutesUntilStart = 5,
                        snoozeMinutes = 2,
                        soundName = "Siren sweep",
                    ),
                ),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun nothingRinging_waitsForTheStoreToCatchUp_thenFinishes() = runTest {
        val viewModel = AlarmRingingViewModel(store(ringing = null), clock)

        viewModel.state.test {
            assertThat(awaitItem()).isEqualTo(AlarmRingingUiState.Waiting)
            assertThat(awaitMatching { it != AlarmRingingUiState.Waiting }).isEqualTo(AlarmRingingUiState.Finished)
            assertThat(testScheduler.currentTime).isGreaterThanOrEqualTo(RINGING_WAIT_MILLIS)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun anAlarmPublishedDuringTheWait_isShown() = runTest {
        val store = store(ringing = null)
        val viewModel = AlarmRingingViewModel(store, clock)

        viewModel.state.test {
            assertThat(awaitItem()).isEqualTo(AlarmRingingUiState.Waiting)

            store.dispatch(SetRinging(alarm))

            assertThat((awaitMatching { it != AlarmRingingUiState.Waiting } as AlarmRingingUiState.Ringing).alarm).isEqualTo(alarm)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun theAlarmStoppingRinging_finishesAtOnce() = runTest {
        val store = store(alarm)
        val viewModel = AlarmRingingViewModel(store, clock)

        viewModel.state.test {
            awaitMatching { it is AlarmRingingUiState.Ringing }
            val clearedAt = testScheduler.currentTime

            store.dispatch(SetRinging(null))

            assertThat(awaitMatching { it !is AlarmRingingUiState.Ringing }).isEqualTo(AlarmRingingUiState.Finished)
            assertThat(testScheduler.currentTime - clearedAt).isLessThan(RINGING_WAIT_MILLIS)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun aNewlyRingingQueuedAlarm_replacesTheOneShown() = runTest {
        val store = store(alarm)
        val viewModel = AlarmRingingViewModel(store, clock)
        val next = alarm.copy(alarmId = 4, title = "Standup")

        viewModel.state.test {
            awaitMatching { it is AlarmRingingUiState.Ringing }

            store.dispatch(SetRinging(next))

            val shown = awaitMatching { (it as? AlarmRingingUiState.Ringing)?.alarm == next } as AlarmRingingUiState.Ringing
            assertThat(shown.screen.title).isEqualTo("Standup")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun callbacks_dispatchToTheAlarmTheyWereGiven() = runTest {
        val recorded = mutableListOf<Action>()
        val recorder = sideEffect { actions.onEach { recorded += it }.filter { false } }
        val viewModel = AlarmRingingViewModel(store(alarm, setOf(recorder)), clock)
        runCurrent()

        viewModel.onSnooze(3)
        viewModel.onDismiss(4)
        viewModel.onOpenMeeting(5)
        runCurrent()

        assertThat(recorded.filter { it is SnoozeAlarm || it is DismissAlarm })
            .containsExactly(SnoozeAlarm(3), DismissAlarm(4), DismissAlarm(5))
    }

    @Test
    fun minutesUntil_roundsUpBeforeTheStart_andCountsWholeMinutesAfter() {
        val begin = Instant.parse("2026-09-14T10:00:00Z")

        assertThat(minutesUntil(begin.minusSeconds(300), begin)).isEqualTo(5L)
        assertThat(minutesUntil(begin.minusSeconds(270), begin)).isEqualTo(5L)
        assertThat(minutesUntil(begin.minusSeconds(1), begin)).isEqualTo(1L)
        assertThat(minutesUntil(begin, begin)).isEqualTo(0L)
        assertThat(minutesUntil(begin.plusSeconds(59), begin)).isEqualTo(0L)
        assertThat(minutesUntil(begin.plusSeconds(60), begin)).isEqualTo(-1L)
        assertThat(minutesUntil(begin.plusSeconds(130), begin)).isEqualTo(-2L)
    }
}
