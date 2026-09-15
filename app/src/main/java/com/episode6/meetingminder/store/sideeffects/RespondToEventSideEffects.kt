package com.episode6.meetingminder.store.sideeffects

import android.util.Log
import com.episode6.meetingminder.R
import com.episode6.meetingminder.data.calendar.CalendarRepository
import com.episode6.meetingminder.data.db.DayPlanDao
import com.episode6.meetingminder.model.EventResponse
import com.episode6.meetingminder.model.RsvpState
import com.episode6.meetingminder.model.canRespond
import com.episode6.meetingminder.model.response
import com.episode6.meetingminder.store.AppState
import com.episode6.meetingminder.store.CalendarContentChanged
import com.episode6.meetingminder.store.PermissionsMaybeChanged
import com.episode6.meetingminder.store.RespondToEvent
import com.episode6.meetingminder.store.RsvpAccepted
import com.episode6.meetingminder.store.RsvpResult
import com.episode6.meetingminder.store.ShowMessage
import com.episode6.meetingminder.store.UiMessage
import com.episode6.redux.Action
import com.episode6.redux.sideeffects.SideEffect
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow

private const val TAG = "MeetingMinderRespond"

/**
 * The long-press menu's "Respond Yes / No / Maybe" (TODO.md §4.6, the explicit half): looks
 * the event up in the loaded window, refuses one the menu shouldn't have offered
 * ([canRespond], which the chip also checks — the window may have reloaded under the
 * open menu), writes the answer through [CalendarRepository.respondToInstance] and says
 * so in a snackbar. Picking the answer the calendar already holds is confirmed without a
 * write: a recurring occurrence would otherwise get a fresh exception, and anything else a
 * pointless dirty flag for the sync adapter. The write changes `SELF_ATTENDEE_STATUS` at
 * once, so the day is reloaded straight away ([CalendarContentChanged]) rather than
 * waiting on the debounced `ContentObserver` — which also runs `MaintainAlarms`, so a
 * "No" on an armed selection cancels its alarm in the same reload.
 *
 * The selection row's RSVP bookkeeping follows the answer: a "Yes" is reported as
 * [RsvpAccepted], so an armed selection gets its "sent" tick and the `DIRTY` promotion to
 * `SYNCED`; a "No" or "Maybe" resets the row to `NOT_APPLICABLE` ([DayPlanDao.setRsvp], a
 * plain update that does nothing for an unselected event), since a tick that means "Yes,
 * going was sent" would otherwise stay on a declined or tentative chip and the reload
 * would keep polling the row for a Yes that no longer exists. Failures are one snackbar,
 * never retried; a `SecurityException` (calendar access revoked under us) also re-checks
 * permissions, as the loads do.
 */
@ContributesTo(AppScope::class)
interface RespondToEventSideEffects {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Provides @IntoSet
    fun respondToEvent(repository: CalendarRepository, dao: DayPlanDao): SideEffect<AppState> = sideEffect {
        actions.filterIsInstance<RespondToEvent>().flatMapMerge { respond ->
            flow<Action> {
                val event = currentState().eventsByDay[respond.date]?.events?.firstOrNull { it.key == respond.key }
                if (event == null || !canRespond(event)) {
                    emit(ShowMessage(UiMessage.next(R.string.respond_failed)))
                    return@flow
                }
                if (event.selfStatus.response == respond.response) {
                    emit(ShowMessage(UiMessage.next(respond.response.sentMessage)))
                    return@flow
                }
                val rsvpEventId = try {
                    repository.respondToInstance(event, respond.response)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SecurityException) {
                    Log.w(TAG, "Responding ${respond.response} to event ${event.eventId} refused", e)
                    emit(ShowMessage(UiMessage.next(R.string.respond_failed)))
                    emit(PermissionsMaybeChanged)
                    return@flow
                } catch (e: Exception) {
                    Log.w(TAG, "Responding ${respond.response} to event ${event.eventId} failed", e)
                    emit(ShowMessage(UiMessage.next(R.string.respond_failed)))
                    return@flow
                }
                if (respond.response == EventResponse.YES) {
                    emit(RsvpAccepted(respond.date, respond.key, RsvpResult.Accepted(rsvpEventId)))
                } else {
                    dao.setRsvp(respond.date, respond.key.eventId, respond.key.instanceTime, RsvpState.NOT_APPLICABLE, rsvpEventId = null)
                }
                emit(ShowMessage(UiMessage.next(respond.response.sentMessage)))
                emit(CalendarContentChanged)
            }
        }
    }
}

private val EventResponse.sentMessage: Int
    get() = when (this) {
        EventResponse.YES -> R.string.respond_sent_yes
        EventResponse.NO -> R.string.respond_sent_no
        EventResponse.MAYBE -> R.string.respond_sent_maybe
    }
