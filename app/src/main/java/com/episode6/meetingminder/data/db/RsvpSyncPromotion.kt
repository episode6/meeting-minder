package com.episode6.meetingminder.data.db

import android.util.Log
import com.episode6.meetingminder.data.calendar.CalendarRepository
import com.episode6.meetingminder.model.RsvpState
import kotlinx.coroutines.CancellationException
import java.time.LocalDate

private const val TAG = "MeetingMinderRsvp"

/**
 * A selection whose write went through is `SYNCED` once the row it was written to
 * ([com.episode6.meetingminder.model.SelectedEvent.rsvpEventId]) reads `DIRTY = 0`: the
 * sync adapter has uploaded the response (TODO.md §4.6). Called wherever a day is reloaded:
 * the foreground reload (`RsvpAcceptSideEffects`) and the background change check
 * (`monitor/ChangeMonitor`). One-way; a later local edit that dirties the event again
 * doesn't demote it. Nothing is queried unless a row is actually waiting, and a read that
 * fails (calendar access revoked under us) just leaves it waiting.
 */
internal suspend fun DayPlanDao.promoteSyncedRsvps(date: LocalDate, repository: CalendarRepository) {
    val awaitingSync = selectedEventsOn(date).filter { it.rsvpState == RsvpState.ACCEPTED_LOCALLY && it.rsvpEventId != null }
    if (awaitingSync.isEmpty()) return
    val synced = try {
        repository.syncedEventIds(awaitingSync.mapNotNull { it.rsvpEventId })
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "could not check RSVP sync state", e)
        return
    }
    for (selection in awaitingSync) {
        if (selection.rsvpEventId in synced) {
            setRsvp(date, selection.eventId, selection.instanceTime, RsvpState.SYNCED, selection.rsvpEventId)
        }
    }
}
