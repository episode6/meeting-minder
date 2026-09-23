package com.episode6.meetingminder.monitor

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.episode6.meetingminder.alarm.AlarmMaintainer
import com.episode6.meetingminder.appGraph
import kotlinx.coroutines.CancellationException

/**
 * The background change check (TODO.md §4.3): every unique work
 * [WorkManagerChangeWorkScheduler] enqueues runs this. It first runs the automatic alarm
 * reconcile ([AlarmMaintainer.maintain], TODO.md §4.4 `MaintainAlarms`), so a meeting that
 * moved while the app was away has its alarm re-timed by the same check that reports the
 * move, rather than ringing for the old time until the app is next opened; then it hands
 * over to [ChangeMonitor.runCheck], which diffs every shared day, notifies, and re-arms (or
 * disarms) the works. Reaches the graph through `Context.appGraph` like the receivers. A
 * check never fails the work: a failure is logged and monitoring stays armed, since a
 * failed one-time work would also fail the trigger appended behind it.
 */
class CalendarChangeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val reason = inputData.getString(KEY_REASON)
            ?.let { name -> ChangeCheckReason.entries.firstOrNull { it.name == name } }
            ?: ChangeCheckReason.PERIODIC
        val graph = applicationContext.appGraph
        try {
            graph.alarmMaintainer.maintain()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("MeetingMinderAlarms", "alarm maintenance failed", e)
        }
        graph.changeMonitor.runCheck(reason)
        return Result.success()
    }

    companion object {
        const val KEY_REASON = "reason"
    }
}
