package com.episode6.meetingminder.permissions

/**
 * Which permission grants we currently hold, mirrored into [com.episode6.meetingminder.store.AppState].
 *
 * Only [calendarGranted] is tracked as of PR-4 (calendar covers both `READ_CALENDAR` and
 * `WRITE_CALENDAR`: they share the `CALENDAR` permission group, so one runtime dialog
 * grants both, TODO.md §4.1). The onboarding rows for alarms, notifications, full-screen
 * intents and battery-optimisation arrive with the PRs that add those permissions
 * (PR-8, PR-8b, PR-10, PR-13) and stub as "coming soon" until then.
 */
data class PermissionState(
    val calendarGranted: Boolean = false,
)
