package com.episode6.meetingminder.data.db

import com.episode6.meetingminder.model.CalendarEvent
import com.episode6.meetingminder.model.EventKey
import com.episode6.meetingminder.model.EventStatus
import com.episode6.meetingminder.model.ScheduleChange
import com.episode6.meetingminder.model.SelfStatus
import com.episode6.meetingminder.model.SnapshotEvent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate

/**
 * One `change_snapshot.events_json` row (TODO.md §3.4): `EventKey → (begin, end,
 * cancelled, declinedByMe, selected, isMeeting)`, plus [allDay] since PR-11 (defaulting to
 * false for rows written before it). A plain serializable DTO — kept separate from
 * [CalendarEvent] and [EventKey] so the stored shape doesn't drift with an unrelated model
 * change — [Instant] is stored as epoch millis like every other Room column in this app.
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
    val allDay: Boolean = false,
) {
    val key: EventKey get() = EventKey(eventId, instanceTime)

    fun toSnapshotEvent() = SnapshotEvent(
        key = key,
        begin = Instant.ofEpochMilli(beginMillis),
        end = Instant.ofEpochMilli(endMillis),
        cancelled = cancelled,
        declinedByMe = declinedByMe,
        selected = selected,
        isMeeting = isMeeting,
        allDay = allDay,
    )
}

private val SnapshotJson = Json { ignoreUnknownKeys = true }

/** [ChangeSnapshotEntity.eventsJson] for every event on a day, per the §4.3 baseline. */
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
                allDay = it.allDay,
            )
        },
    )

internal fun decodeChangeSnapshotEvents(json: String): List<ChangeSnapshotEventDto> = SnapshotJson.decodeFromString(json)

/** The baseline as the differ reads it. */
internal fun ChangeSnapshotEntity.baseline(): List<SnapshotEvent> = decodeChangeSnapshotEvents(eventsJson).map { it.toSnapshotEvent() }

/** [ChangeSnapshotEntity.changesJson]'s rows: one [ScheduleChange] with its kind, key and times (epoch millis). */
@Serializable
private data class ScheduleChangeDto(
    val kind: Kind,
    val eventId: Long,
    val instanceTime: Long,
    val beginMillis: Long,
    val endMillis: Long,
    val newBeginMillis: Long? = null,
    val newEndMillis: Long? = null,
) {
    @Serializable
    enum class Kind {
        @SerialName("new") NEW,
        @SerialName("moved") MOVED,
        @SerialName("cancelled") CANCELLED,
        @SerialName("declined") DECLINED,
    }
}

internal fun encodeScheduleChanges(changes: List<ScheduleChange>): String = SnapshotJson.encodeToString(
    changes.map { change ->
        val key = change.key
        when (change) {
            is ScheduleChange.New -> ScheduleChangeDto(ScheduleChangeDto.Kind.NEW, key.eventId, key.instanceTime, change.begin.toEpochMilli(), change.end.toEpochMilli())
            is ScheduleChange.Cancelled -> ScheduleChangeDto(ScheduleChangeDto.Kind.CANCELLED, key.eventId, key.instanceTime, change.begin.toEpochMilli(), change.end.toEpochMilli())
            is ScheduleChange.Declined -> ScheduleChangeDto(ScheduleChangeDto.Kind.DECLINED, key.eventId, key.instanceTime, change.begin.toEpochMilli(), change.end.toEpochMilli())
            is ScheduleChange.Moved -> ScheduleChangeDto(
                ScheduleChangeDto.Kind.MOVED, key.eventId, key.instanceTime,
                change.oldBegin.toEpochMilli(), change.oldEnd.toEpochMilli(),
                change.newBegin.toEpochMilli(), change.newEnd.toEpochMilli(),
            )
        }
    },
)

/**
 * [ChangeSnapshotEntity.changesJson] back into changes on [date]. A Moved row without its
 * new times is dropped, and unreadable JSON reads as "nothing changed" rather than failing
 * the banner stream or the check: the next check rewrites it anyway.
 */
internal fun decodeScheduleChanges(date: LocalDate, json: String): List<ScheduleChange> = try {
    SnapshotJson.decodeFromString<List<ScheduleChangeDto>>(json)
} catch (_: IllegalArgumentException) {
    // kotlinx.serialization's SerializationException is an IllegalArgumentException
    emptyList()
}.mapNotNull { dto ->
    val key = EventKey(dto.eventId, dto.instanceTime)
    val begin = Instant.ofEpochMilli(dto.beginMillis)
    val end = Instant.ofEpochMilli(dto.endMillis)
    when (dto.kind) {
        ScheduleChangeDto.Kind.NEW -> ScheduleChange.New(date, key, begin, end)
        ScheduleChangeDto.Kind.CANCELLED -> ScheduleChange.Cancelled(date, key, begin, end)
        ScheduleChangeDto.Kind.DECLINED -> ScheduleChange.Declined(date, key, begin, end)
        ScheduleChangeDto.Kind.MOVED -> {
            val newBegin = dto.newBeginMillis ?: return@mapNotNull null
            val newEnd = dto.newEndMillis ?: return@mapNotNull null
            ScheduleChange.Moved(date, key, begin, end, Instant.ofEpochMilli(newBegin), Instant.ofEpochMilli(newEnd))
        }
    }
}
