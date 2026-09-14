package com.episode6.meetingminder.data.db

import java.time.LocalDate

/** In-memory [ScheduledAlarmDao]: rows keyed by `alarm_id`, ids handed out from 1 like SQLite's autoincrement. */
internal class FakeScheduledAlarmDao(rows: List<ScheduledAlarmEntity> = emptyList()) : ScheduledAlarmDao {
    val rows: MutableMap<Long, ScheduledAlarmEntity> = rows.associateBy { it.alarmId }.toMutableMap()

    override suspend fun insert(entity: ScheduledAlarmEntity): Long {
        val id = (rows.keys.maxOrNull() ?: 0L) + 1
        rows[id] = entity.copy(alarmId = id)
        return id
    }

    override suspend fun update(entity: ScheduledAlarmEntity) {
        rows[entity.alarmId] = entity
    }

    override suspend fun byId(alarmId: Long): ScheduledAlarmEntity? = rows[alarmId]

    override suspend fun scheduledOn(date: LocalDate): List<ScheduledAlarmEntity> =
        rows.values.filter { it.date == date && it.state == AlarmState.SCHEDULED }

    override suspend fun allScheduled(): List<ScheduledAlarmEntity> = rows.values.filter { it.state == AlarmState.SCHEDULED }

    override suspend fun setState(alarmId: Long, state: AlarmState) {
        rows[alarmId]?.let { rows[alarmId] = it.copy(state = state) }
    }
}
