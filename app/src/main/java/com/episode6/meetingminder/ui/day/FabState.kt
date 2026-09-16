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

    /**
     * [syncs] is true while busy-calendar sync (TODO.md §4.7) is effective — the toggle is
     * on and it points at a calendar that's still writable — which swaps the FAB's label to
     * "Sync & Share". The write itself is wired in PR-15c; sharing already runs unconditionally.
     */
    data class Share(val syncs: Boolean) : FabState
}
