package com.episode6.meetingminder.alarm

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.episode6.meetingminder.appGraph
import com.episode6.meetingminder.model.RingingAlarm
import com.episode6.meetingminder.store.SetRinging
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import java.time.ZoneId

/** How long a ringing alarm keeps the CPU awake at most; comfortably past the auto-timeout, released as soon as it stops. */
private const val RINGING_WAKE_LOCK_MILLIS = 10 * 60_000L

private const val LOG_TAG = "AlarmRingingService"

/**
 * The foreground service that rings (TODO.md §4.4). [AlarmReceiver] starts it with
 * `startForegroundService` the moment an alarm fires; it then posts the ringing
 * notification (channel `alarms`, `CATEGORY_ALARM`, a full-screen intent to
 * [AlarmActivity], Snooze/Dismiss actions back into this service), plays the randomised
 * sound ([AlarmSoundPlayer], `USAGE_ALARM` throughout) and vibrates ([AlarmVibration]),
 * and publishes `SetRinging` so the ringing screen can render it. Type `mediaPlayback`: no
 * timeout, no runtime prerequisite, and what Android 17's background-audio rules expect.
 *
 * The rules — the foreground deadline, the queue, awaited snooze writes, the auto-timeout
 * — live in [AlarmRingingSession]; this class only adapts them to Android. `START_NOT_STICKY`:
 * a ringing that died with its process is not resumed (the row stays `FIRED`).
 */
class AlarmRingingService : Service(), RingingOutputs {

    private val scope = MainScope()
    private lateinit var session: AlarmRingingSession
    private lateinit var player: AlarmSoundPlayer
    private lateinit var vibration: AlarmVibration
    private var lastStartId = 0

    override fun onCreate() {
        super.onCreate()
        val graph = appGraph
        session = AlarmRingingSession(scope, graph.alarmRinger, this)
        player = AlarmSoundPlayer(this, graph.recentAlarmSounds, graph.settingsRepository)
        vibration = AlarmVibration(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val alarmId = intent?.data?.let(AlarmUris::alarmIdOf) ?: NO_ALARM
        when (intent?.action) {
            ACTION_FIRE -> {
                // the receiver's lock may already have been released by a previous alarm's
                // stop() (the lock isn't reference counted); hold it from here, not only
                // from showRinging, so the row and settings reads can't run without it
                AlarmWakeLock.acquire(this, RINGING_WAKE_LOCK_MILLIS)
                session.fire(alarmId)
            }
            ACTION_SNOOZE -> session.snooze(alarmId)
            else -> session.dismiss(alarmId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        session.release()
        scope.cancel()
        player.stop()
        vibration.stop()
        AlarmWakeLock.release()
        super.onDestroy()
    }

    override fun showRinging(alarm: RingingAlarm, alert: Boolean) {
        AlarmWakeLock.acquire(this, RINGING_WAKE_LOCK_MILLIS)
        enterForeground(AlarmNotifications.ringing(this, alarm, ZoneId.systemDefault(), alert))
    }

    override fun showPlaceholder() {
        enterForeground(AlarmNotifications.placeholder(this))
    }

    override fun startSound(alarm: RingingAlarm) {
        player.start(scope, alarm) { soundName -> session.onSoundStarted(alarm.alarmId, soundName) }
        vibration.start(alarmVibrationTimings(alarm.soundIndex))
    }

    override fun stopSound() {
        player.stop()
        vibration.stop()
    }

    override fun publish(ringing: RingingAlarm?) {
        appGraph.appStore.dispatch(SetRinging(ringing))
    }

    override fun postMissed(alarm: RingingAlarm) {
        AlarmNotifications.postMissed(this, alarm, ZoneId.systemDefault())
    }

    override fun stop() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf(lastStartId)
        AlarmWakeLock.release()
    }

    private fun enterForeground(notification: Notification) {
        try {
            ServiceCompat.startForeground(
                this,
                AlarmNotifications.RINGING_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException: shouldn't happen with the
            // alarm-clock allowlist, but ringing without the foreground beats crashing, and
            // posting the notification directly still gets the full-screen intent out
            Log.w(LOG_TAG, "startForeground refused", e)
            try {
                NotificationManagerCompat.from(this).notify(AlarmNotifications.RINGING_NOTIFICATION_ID, notification)
            } catch (_: SecurityException) {
                // POST_NOTIFICATIONS revoked; the sound still plays
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "com.episode6.meetingminder.action.RING"
        const val ACTION_SNOOZE = "com.episode6.meetingminder.action.SNOOZE"
        const val ACTION_DISMISS = "com.episode6.meetingminder.action.DISMISS"

        private const val NO_ALARM = -1L

        /** For `startForegroundService` from [AlarmReceiver] only: the service must go foreground. */
        fun fireIntent(context: Context, alarmId: Long): Intent = intent(context, ACTION_FIRE, alarmId)

        fun snoozeIntent(context: Context, alarmId: Long): Intent = intent(context, ACTION_SNOOZE, alarmId)

        fun dismissIntent(context: Context, alarmId: Long): Intent = intent(context, ACTION_DISMISS, alarmId)

        private fun intent(context: Context, action: String, alarmId: Long): Intent =
            Intent(context, AlarmRingingService::class.java).setAction(action).setData(AlarmUris.alarm(alarmId))
    }
}
