package com.episode6.meetingminder.store

import androidx.annotation.StringRes
import java.time.LocalDate

/**
 * The single app-wide state behind [AppStore] (TODO.md §3.2). Every screen's ViewModel,
 * and later the alarm receivers, services and the change-detection worker, read and
 * write this one object.
 *
 * Fields arrive with the PR that first needs them: permissions (PR-4), calendars and
 * `eventsByDay` (PR-6), `dayPlans` (PR-7), `ringing` (PR-10) and `scheduleChanges`
 * (PR-11) are added here alongside their model types, each with a default so existing
 * call sites keep compiling.
 */
data class AppState(
    /** The day the day pager is anchored to: today at launch (midnight rollover is PR-13). */
    val anchorDate: LocalDate,
    /** The day the user is looking at: the pager's settled page. */
    val settledDate: LocalDate = anchorDate,
    /** One-shot snackbar text; ViewModels expose it as a one-shot `Flow` and clear it by id once shown. */
    val transientMessage: UiMessage? = null,
)

/**
 * A snackbar message. [id] is chosen by whoever dispatches [ShowMessage] (see
 * [UiMessage.next]) so [ClearMessage] can clear exactly the message that was shown and
 * never a newer one that replaced it in the meantime.
 */
data class UiMessage(
    val id: Long,
    @param:StringRes val text: Int,
    val formatArgs: List<Any> = emptyList(),
) {
    companion object {
        private var lastId = 0L

        /** A message with a fresh, process-unique id. */
        @Synchronized
        fun next(@StringRes text: Int, vararg formatArgs: Any): UiMessage =
            UiMessage(id = ++lastId, text = text, formatArgs = formatArgs.toList())
    }
}
