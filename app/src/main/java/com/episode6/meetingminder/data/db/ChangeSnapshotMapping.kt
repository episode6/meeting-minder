package com.episode6.meetingminder.data.db

import com.episode6.meetingminder.model.CalendarEvent
import com.episode6.meetingminder.model.EventKey
import com.episode6.meetingminder.model.EventStatus
import com.episode6.meetingminder.model.SelfStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * One `change_snapshot.events_json` row (TODO.md §3.4): `EventKey → (begin, end,
 * cancelled, declinedByMe, selected, isMeeting)`. A plain serializable DTO — kept
 * separate from [CalendarEvent] and [EventKey] so the stored shape doesn't drift with an
 * unrelated model change — [Instant] is stored as epoch millis like every other Room
 * column in this app.
 */
@Serializable
internal data class ChangeSnapshotEventDto(
    val eventId: Long,
    val instanceTime: Long,
    val beginMillis: Long,
    val endMillis: Long,
    val cancelled: Boolean,
    val declinedByMe: Boolean,
    val selected: Boolean,
    val isMeeting: Boolean,
) {
    val key: EventKey get() = EventKey(eventId, instanceTime)
}

private val SnapshotJson = Json { ignoreUnknownKeys = true }

/** [ChangeSnapshotEntity.eventsJson] for every event on a day, per PR-11's §4.3 baseline. */
internal fun encodeChangeSnapshotEvents(events: List<CalendarEvent>, selectedKeys: Set<EventKey>): String =
    SnapshotJson.encodeToString(
        events.map {
            ChangeSnapshotEventDto(
                eventId = it.key.eventId,
                instanceTime = it.key.instanceTime,
                beginMillis = it.begin.toEpochMilli(),
                endMillis = it.end.toEpochMilli(),
                cancelled = it.status == EventStatus.CANCELED,
                declinedByMe = it.selfStatus == SelfStatus.DECLINED,
                selected = it.key in selectedKeys,
                isMeeting = it.isMeeting,
            )
        },
    )

internal fun decodeChangeSnapshotEvents(json: String): List<ChangeSnapshotEventDto> = SnapshotJson.decodeFromString(json)
