package com.episode6.meetingminder.ui.navigation

import kotlinx.serialization.Serializable

/** Type-safe navigation destinations; see [MeetingMinderNavigation]. */
@Serializable
sealed interface Route {
    /** The day view; the launch destination. */
    @Serializable
    data object Day : Route

    /** Permissions checklist (TODO.md §4.5): first launch, missing grants, overflow → Permissions. */
    @Serializable
    data object Onboarding : Route

    /** Lead time, snooze, calendars, sounds (TODO.md PR-12). */
    @Serializable
    data object Settings : Route

    /** Settings → Alarm sounds: the per-sound checkboxes (TODO.md §4.4). */
    @Serializable
    data object AlarmSounds : Route

    /** THIRD_PARTY_LICENSES.md, embedded at build time. */
    @Serializable
    data object Licenses : Route
}
