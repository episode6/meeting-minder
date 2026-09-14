package com.episode6.meetingminder.ui.day

import androidx.compose.runtime.Immutable

/** The day view's FAB (TODO.md §3.5): hidden, "Set alarms (N)", or (from PR-8/9) "Share schedule". */
@Immutable
sealed interface FabState {
    data object Hidden : FabState

    /**
     * Tapping reconciles the day's alarms against its [count] selected events. A [count] of
     * 0 means every selection was removed while alarms are still armed, so the tap only
     * cancels; the FAB reads "Clear alarms" then.
     */
    data class SetAlarms(val count: Int) : FabState
    data object Share : FabState
}
