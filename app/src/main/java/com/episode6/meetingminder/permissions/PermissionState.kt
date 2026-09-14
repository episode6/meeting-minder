package com.episode6.meetingminder.permissions

/**
 * Which permission grants we currently hold, mirrored into [com.episode6.meetingminder.store.AppState].
 *
 * [calendarGranted] covers both `READ_CALENDAR` and `WRITE_CALENDAR` (they share the
 * `CALENDAR` permission group, so one runtime dialog grants both, TODO.md §4.1).
 * [notificationsGranted] and [exactAlarmsGranted] are the other two *required* rows of
 * onboarding (TODO.md §4.5); the full-screen-intent and battery-optimisation rows arrive
 * with PR-10 and PR-13 and stub as "coming soon" until then.
 */
data class PermissionState(
    val calendarGranted: Boolean = false,
    /** App-level notifications on **and** the `alarms` channel not silenced. */
    val notificationsGranted: Boolean = false,
    /** `AlarmManager.canScheduleExactAlarms()`; auto-granted on 33+ via `USE_EXACT_ALARM`, revocable on 12/12L. */
    val exactAlarmsGranted: Boolean = false,
) {
    /** Every required onboarding row is granted: the launch destination is the day view. */
    val allRequiredGranted: Boolean get() = calendarGranted && notificationsGranted && exactAlarmsGranted
}
