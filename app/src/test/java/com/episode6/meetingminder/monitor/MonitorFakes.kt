package com.episode6.meetingminder.monitor

import com.episode6.meetingminder.model.ScheduleChange
import com.episode6.meetingminder.permissions.PermissionChecker
import com.episode6.meetingminder.permissions.PermissionState
import java.time.LocalDate

/** Records every [ChangeWorkScheduler.update], in order. */
internal class FakeChangeWorkScheduler : ChangeWorkScheduler {
    val updates = mutableListOf<Pair<Set<LocalDate>, ChangeCheckReason>>()

    override fun update(sharedDays: Set<LocalDate>, reason: ChangeCheckReason) {
        updates += sharedDays to reason
    }
}

/** Records what [ScheduleChangeNotifier] was asked to show and cancel, in order. */
internal class FakeScheduleChangeNotifier : ScheduleChangeNotifier {
    data class Shown(
        val date: LocalDate,
        val changes: List<ScheduleChange>,
        val alert: Boolean,
        val silent: Boolean = false,
        val syncOnly: Boolean = false,
    )

    val shown = mutableListOf<Shown>()
    val cancelled = mutableListOf<LocalDate>()

    override fun show(date: LocalDate, changes: List<ScheduleChange>, alert: Boolean, silent: Boolean, syncOnly: Boolean) {
        shown += Shown(date, changes, alert, silent, syncOnly)
    }

    override fun cancel(date: LocalDate) {
        cancelled += date
    }
}

/** Records the loud alerts asked for and cancelled, in order; [rings] is whether one can be armed (false: no exact-alarm grant). */
internal class FakeScheduleChangeAlerter(var rings: Boolean = false) : ScheduleChangeAlerter {
    val alerted = mutableListOf<LocalDate>()
    val cancelled = mutableListOf<LocalDate>()

    override suspend fun alert(date: LocalDate): Boolean {
        alerted += date
        return rings
    }

    override suspend fun cancel(date: LocalDate) {
        cancelled += date
    }
}

/** A [PermissionChecker] reporting calendar access as [calendarGranted] (and nothing else granted). */
internal class FakeCalendarPermissionChecker(var calendarGranted: Boolean = true) : PermissionChecker {
    override fun currentState() = PermissionState(calendarGranted = calendarGranted)
}
