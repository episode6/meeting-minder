package com.episode6.meetingminder.ui.navigation

import com.episode6.meetingminder.data.settings.SettingsRepository
import dev.zacsweers.metro.Inject
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The wall-clock time of every `MainActivity` start (the app coming to the foreground: the
 * same moment [com.episode6.meetingminder.monitor.MainUiVisibility] turns on, so a ringing
 * `AlarmActivity` doesn't count), kept in [SettingsRepository.recordForegrounded] so it
 * outlives the process. The first start of a local day opens the day view on today:
 * `MainActivity` offers [record]'s date to its [DeepLinkInbox].
 */
@Inject
class ForegroundLog(private val settings: SettingsRepository, private val clock: Clock) {

    /** Records now as the latest foreground; returns today if no earlier foreground happened on it. */
    suspend fun record(): LocalDate? {
        val now = clock.instant()
        return firstForegroundOfDay(settings.recordForegrounded(now), now, clock.zone)
    }
}

/**
 * The date of [now] in [zone] when [previous] (the foreground before it, null if there never
 * was one) fell on another date in that same zone, else null. Both are read in the zone the
 * device is in now: after flying east overnight, "today" is the new zone's date.
 */
internal fun firstForegroundOfDay(previous: Instant?, now: Instant, zone: ZoneId): LocalDate? {
    val today = now.atZone(zone).toLocalDate()
    return today.takeIf { previous == null || previous.atZone(zone).toLocalDate() != today }
}
