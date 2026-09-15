package com.episode6.meetingminder.monitor

import android.content.Context
import android.provider.CalendarContract
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.time.Clock
import java.time.Duration
import java.time.LocalDate

/** Who asked for a change check (and so who may be re-arming its own unique work). */
enum class ChangeCheckReason {
    /** [CalendarChangeWorker] run by the calendar content-URI trigger. */
    CONTENT_TRIGGER,

    /** [CalendarChangeWorker] run by the 30-minute safety net. */
    PERIODIC,

    /** [CalendarChangeWorker] run at the last shared day's midnight. */
    DAY_ENDED,

    /** The app itself: the foreground `CalendarContentChanged` reload, a share, "Mark as not shared". */
    IN_APP,
}

/**
 * Keeps the background monitoring (TODO.md §4.3, mechanisms 2 and 4, and the lifecycle)
 * armed exactly while some day is shared: [update] is given every shared day that is today
 * or later, and an empty set disarms everything.
 */
interface ChangeWorkScheduler {
    fun update(sharedDays: Set<LocalDate>, reason: ChangeCheckReason)
}

/**
 * [ChangeWorkScheduler] over WorkManager, with three unique works all running
 * [CalendarChangeWorker]:
 *
 * - [TRIGGER_WORK] — one-time work with a content-URI trigger on the provider root
 *   (`CalendarContract.CONTENT_URI`, descendants included, 5 s settle, 1 min max delay). The
 *   system holds the observer, so no process is alive between changes. Content triggers
 *   are one-shot, so the worker re-arms it at the end of every run: from inside the running
 *   work that has to be `APPEND_OR_REPLACE` (a `REPLACE` would cancel the run itself, a `KEEP`
 *   would do nothing while it runs); from anywhere else `KEEP`, so a waiting trigger isn't
 *   thrown away and a share never grows the chain.
 * - [PERIODIC_WORK] — the 30-minute (flex 10) safety net, first run one interval out,
 *   `KEEP`, which also re-arms a trigger chain a failed run left dead.
 * - [EXPIRY_WORK] — one-time work delayed until the midnight that ends the last shared day,
 *   `REPLACE`d from the app (a share or "Mark as not shared" may have moved that day),
 *   `KEEP` from a background check (which only drops days that have ended, so it doesn't
 *   churn a job on every calendar sync), and appended behind itself from its own run;
 *   when it runs with nothing shared any more
 *   and the check disarms everything and cancels the notifications.
 *
 * A worker never cancels its own one-time unique work (it is finishing anyway); the
 * periodic work has to cancel itself to stop.
 */
class WorkManagerChangeWorkScheduler(private val context: Context, private val clock: Clock) : ChangeWorkScheduler {

    // resolved lazily: WorkManager is initialised by androidx.startup, which the graph must
    // not depend on having run (Robolectric tests build the graph without it)
    private val workManager: WorkManager by lazy { WorkManager.getInstance(context) }

    override fun update(sharedDays: Set<LocalDate>, reason: ChangeCheckReason) {
        if (sharedDays.isEmpty()) {
            if (reason != ChangeCheckReason.CONTENT_TRIGGER) workManager.cancelUniqueWork(TRIGGER_WORK)
            workManager.cancelUniqueWork(PERIODIC_WORK)
            if (reason != ChangeCheckReason.DAY_ENDED) workManager.cancelUniqueWork(EXPIRY_WORK)
            return
        }
        workManager.enqueueUniqueWork(
            TRIGGER_WORK,
            if (reason == ChangeCheckReason.CONTENT_TRIGGER) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
            triggerRequest(),
        )
        workManager.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, periodicRequest())
        workManager.enqueueUniqueWork(
            EXPIRY_WORK,
            when (reason) {
                ChangeCheckReason.DAY_ENDED -> ExistingWorkPolicy.APPEND_OR_REPLACE
                // a background check only drops days that have ended, so the last shared day
                // is where the waiting expiry already is: don't re-create it on every sync
                ChangeCheckReason.CONTENT_TRIGGER, ChangeCheckReason.PERIODIC -> ExistingWorkPolicy.KEEP
                ChangeCheckReason.IN_APP -> ExistingWorkPolicy.REPLACE
            },
            expiryRequest(sharedDays.max()),
        )
    }

    private fun triggerRequest(): OneTimeWorkRequest = OneTimeWorkRequestBuilder<CalendarChangeWorker>()
        .setConstraints(
            Constraints.Builder()
                .addContentUriTrigger(CalendarContract.CONTENT_URI, true)
                .setTriggerContentUpdateDelay(TRIGGER_CONTENT_UPDATE_DELAY)
                .setTriggerContentMaxDelay(TRIGGER_CONTENT_MAX_DELAY)
                .build(),
        )
        .setInputData(workDataOf(CalendarChangeWorker.KEY_REASON to ChangeCheckReason.CONTENT_TRIGGER.name))
        .addTag(WORK_TAG)
        .build()

    // first run one interval out: a periodic work with no delay runs at once, and nothing
    // needs re-checking the moment monitoring is armed (a share just took the baseline)
    private fun periodicRequest(): PeriodicWorkRequest = PeriodicWorkRequestBuilder<CalendarChangeWorker>(PERIODIC_INTERVAL, PERIODIC_FLEX)
        .setInitialDelay(PERIODIC_INTERVAL)
        .setInputData(workDataOf(CalendarChangeWorker.KEY_REASON to ChangeCheckReason.PERIODIC.name))
        .addTag(WORK_TAG)
        .build()

    private fun expiryRequest(lastSharedDay: LocalDate): OneTimeWorkRequest {
        val dayEnds = lastSharedDay.plusDays(1).atStartOfDay(clock.zone).toInstant()
        return OneTimeWorkRequestBuilder<CalendarChangeWorker>()
            .setInitialDelay(Duration.between(clock.instant(), dayEnds).coerceAtLeast(Duration.ZERO))
            .setInputData(workDataOf(CalendarChangeWorker.KEY_REASON to ChangeCheckReason.DAY_ENDED.name))
            .addTag(WORK_TAG)
            .build()
    }

    companion object {
        const val TRIGGER_WORK = "calendar-change-trigger"
        const val PERIODIC_WORK = "calendar-change-periodic"
        const val EXPIRY_WORK = "calendar-change-expiry"
        const val WORK_TAG = "calendar-change"

        val TRIGGER_CONTENT_UPDATE_DELAY: Duration = Duration.ofSeconds(5)
        val TRIGGER_CONTENT_MAX_DELAY: Duration = Duration.ofMinutes(1)
        val PERIODIC_INTERVAL: Duration = Duration.ofMinutes(30)
        val PERIODIC_FLEX: Duration = Duration.ofMinutes(10)
    }
}
