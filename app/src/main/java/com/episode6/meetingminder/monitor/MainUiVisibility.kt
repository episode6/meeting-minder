package com.episode6.meetingminder.monitor

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Whether `MainActivity` is started, i.e. the day view (and its "changed since you shared"
 * banner) is in front of the user. `MainActivity` sets it; [ChangeMonitor] reads it so the
 * loud schedule-change alert never rings over the app itself. The check's reason alone
 * can't say: the background worker and the foreground reload both run for the same provider
 * change, and whichever gets to the lock first is the one that finds the change new.
 * Deliberately not the store's subscriber count, which `AlarmActivity` holds too.
 */
@Inject
@SingleIn(AppScope::class)
class MainUiVisibility {
    @Volatile
    var visible: Boolean = false
}
