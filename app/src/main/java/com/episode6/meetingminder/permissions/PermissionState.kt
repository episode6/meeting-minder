package com.episode6.meetingminder.permissions

/**
 * Which permission grants we currently hold, mirrored into [com.episode6.meetingminder.store.AppState].
 *
 * [calendarGranted] covers both `READ_CALENDAR` and `WRITE_CALENDAR` (they share the
 * `CALENDAR` permission group, so one runtime dialog grants both, TODO.md §4.1).
 * [notificationsGranted], [exactAlarmsGranted] and [fullScreenIntentGranted] are the other
 * *required* rows of onboarding (TODO.md §4.5). [ignoringBatteryOptimizations] and
 * [backgroundRestricted] are optional: neither gates [allRequiredGranted], since alarm-clock
 * alarms and the ringing service's start are exempt from both (§4.4).
 */
data class PermissionState(
    val calendarGranted: Boolean = false,
    /** App-level notifications on **and** the `alarms` channel not silenced. */
    val notificationsGranted: Boolean = false,
    /** `AlarmManager.canScheduleExactAlarms()`; auto-granted on 33+ via `USE_EXACT_ALARM`, revocable on 12/12L. */
    val exactAlarmsGranted: Boolean = false,
    /**
     * `NotificationManager.canUseFullScreenIntent()` on 34+ — granted by default for a
     * sideloaded install, but revocable in Settings, and without it a ringing alarm is only
     * a heads-up instead of waking the screen. Always true below 34, where
     * `USE_FULL_SCREEN_INTENT` is granted at install.
     */
    val fullScreenIntentGranted: Boolean = false,
    /**
     * `PowerManager.isIgnoringBatteryOptimizations` — the optional "Ignore battery
     * optimization" onboarding row, a belt-and-braces for OEM builds that kill background
     * work. Only offered while false.
     */
    val ignoringBatteryOptimizations: Boolean = false,
    /**
     * The app is in the "restricted" standby bucket or the user set its battery usage to
     * Restricted (`ActivityManager.isBackgroundRestricted`): background work — change
     * monitoring's WorkManager jobs — then runs at most about once a day. Shown as a warning,
     * never required.
     */
    val backgroundRestricted: Boolean = false,
) {
    /** Every required onboarding row is granted: the launch destination is the day view. */
    val allRequiredGranted: Boolean
        get() = calendarGranted && notificationsGranted && exactAlarmsGranted && fullScreenIntentGranted
}
