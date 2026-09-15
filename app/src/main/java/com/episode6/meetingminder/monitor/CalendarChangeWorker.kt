package com.episode6.meetingminder.monitor

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.episode6.meetingminder.appGraph

/**
 * The background change check (TODO.md §4.3): every unique work
 * [WorkManagerChangeWorkScheduler] enqueues runs this, and it just hands over to
 * [ChangeMonitor.runCheck], which diffs every shared day, notifies, and re-arms (or
 * disarms) the works. Reaches the graph through `Context.appGraph` like the receivers. A
 * check never fails the work: a failure is logged and monitoring stays armed, since a
 * failed one-time work would also fail the trigger appended behind it.
 */
class CalendarChangeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val reason = inputData.getString(KEY_REASON)
            ?.let { name -> ChangeCheckReason.entries.firstOrNull { it.name == name } }
            ?: ChangeCheckReason.PERIODIC
        applicationContext.appGraph.changeMonitor.runCheck(reason)
        return Result.success()
    }

    companion object {
        const val KEY_REASON = "reason"
    }
}
