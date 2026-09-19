package com.episode6.meetingminder.ui.day

import androidx.compose.runtime.Immutable
import com.episode6.meetingminder.data.calendar.ShareMode

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
     * What the tap does, and so the label ([ShareMode], TODO.md §4.7): "Share schedule",
     * "Sync & Share" while busy-calendar sync is effective, or "Sync busy times" when the
     * sync is effective and Settings' "Also send a schedule text" is off.
     */
    data class Share(val mode: ShareMode = ShareMode.TEXT) : FabState

    /**
     * A [ShareMode.SYNC_ONLY] day whose alarms are set and that has been synced: no button.
     * Syncing again only means something once the day changes, and the "changed since you
     * synced" banner's "Re-sync" says so with the changes (the overflow's "Sync again"
     * forces it). The subtitle still reads "synced 8:12 AM" with its bell.
     */
    data object Synced : FabState
}
