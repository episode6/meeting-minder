package com.episode6.meetingminder.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import com.episode6.meetingminder.model.EventKey
import java.time.LocalDate

/**
 * `selected_event` (TODO.md §3.4): one row per (date, [EventKey]) the user has picked for
 * that day. [title]/[beginMillis]/[endMillis] are copied from the provider at selection
 * time (see [com.episode6.meetingminder.model.SelectedEvent]); [alarmId]/[alarmAt] stay
 * null until PR-8 schedules an alarm, and [rsvpState]/[rsvpEventId] stay
 * `"NOT_APPLICABLE"`/null until PR-8b writes the RSVP. Neither pair is read back by this
 * PR's mapping into [com.episode6.meetingminder.model.SelectedEvent].
 */
@Entity(
    tableName = "selected_event",
    primaryKeys = ["date", "event_id", "instance_time"],
)
data class SelectedEventEntity(
    val date: LocalDate,
    @ColumnInfo(name = "event_id") val eventId: Long,
    @ColumnInfo(name = "instance_time") val instanceTime: Long,
    val title: String,
    @ColumnInfo(name = "begin_millis") val beginMillis: Long,
    @ColumnInfo(name = "end_millis") val endMillis: Long,
    @ColumnInfo(name = "alarm_id") val alarmId: Long? = null,
    @ColumnInfo(name = "alarm_at") val alarmAt: Long? = null,
    @ColumnInfo(name = "rsvp_state") val rsvpState: String = "NOT_APPLICABLE",
    @ColumnInfo(name = "rsvp_event_id") val rsvpEventId: Long? = null,
) {
    val key: EventKey get() = EventKey(eventId, instanceTime)
}
