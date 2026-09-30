package com.episode6.meetingminder.data.settings

import dev.zacsweers.metro.Inject
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Whether the day view has shown today's page yet today, kept in
 * [SettingsRepository.todayLastShownAt] so it outlives the process. Until it has, every
 * `MainActivity` start opens on today ([todayIfNotYetShown]); the pager settling on today —
 * after that jump, a swipe, or the Today action — records it ([onPageSettled], from
 * `TodayShownSideEffects`). So a start that showed something else (a notification's link to
 * another day, or Onboarding) doesn't use up the day's jump: the next start still makes it.
 * "Today" is the date in the zone the device is in now.
 */
@Inject
class TodayShownLog(private val settings: SettingsRepository, private val clock: Clock) {

    /** Today, if its page hasn't been shown yet today; null once it has. */
    suspend fun todayIfNotYetShown(): LocalDate? {
        val now = clock.instant()
        val zone = clock.zone
        return now.atZone(zone).toLocalDate().takeUnless { sameDate(settings.todayLastShownAt(), now, zone) }
    }

    /** The day pager settled on [date]: if that's today, records today as shown, once a day. */
    suspend fun onPageSettled(date: LocalDate) {
        val now = clock.instant()
        val zone = clock.zone
        if (date != now.atZone(zone).toLocalDate() || sameDate(settings.todayLastShownAt(), now, zone)) return
        settings.recordTodayShown(now)
    }
}

/**
 * Whether [earlier] (null if there's none) falls on the same date as [now], both read in
 * [zone]: the zone the device is in now, so after flying east overnight "today" is the new
 * zone's date.
 */
internal fun sameDate(earlier: Instant?, now: Instant, zone: ZoneId): Boolean =
    earlier != null && earlier.atZone(zone).toLocalDate() == now.atZone(zone).toLocalDate()
