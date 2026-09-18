package com.episode6.meetingminder.data.db

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.time.LocalDate

/**
 * In-memory [ScheduledAlarmDao]: rows keyed by `alarm_id`, ids handed out from 1 like
 * SQLite's autoincrement. [observeScheduled] re-emits after every write made through the
 * DAO methods; a test that pokes [rows] directly should do so before collecting.
 */
internal class FakeScheduledAlarmDao(rows: List<ScheduledAlarmEntity> = emptyList()) : ScheduledAlarmDao {
    val rows: MutableMap<Long, ScheduledAlarmEntity> = rows.associateBy { it.alarmId }.toMutableMap()
    private val version = MutableStateFlow(0)

    private fun changed() = version.update { it + 1 }

    override suspend fun insert(entity: ScheduledAlarmEntity): Long {
        val id = (rows.keys.maxOrNull() ?: 0L) + 1
        rows[id] = entity.copy(alarmId = id)
        changed()
        return id
    }

    override suspend fun update(entity: ScheduledAlarmEntity) {
        rows[entity.alarmId] = entity
        changed()
    }

    override fun observeScheduled(): Flow<List<ScheduledAlarmEntity>> = version.map { allScheduled() }

    override suspend fun byId(alarmId: Long): ScheduledAlarmEntity? = rows[alarmId]

    override suspend fun scheduledOn(date: LocalDate): List<ScheduledAlarmEntity> =
        rows.values.filter { it.date == date && it.state.armed }

    override suspend fun allScheduled(): List<ScheduledAlarmEntity> = rows.values.filter { it.state.armed }

    override suspend fun latestOn(date: LocalDate, eventId: Long): ScheduledAlarmEntity? =
        rows.values.filter { it.date == date && it.eventId == eventId }.maxByOrNull { it.alarmId }

    override suspend fun transition(alarmId: Long, from: AlarmState, to: AlarmState): Int {
        val row = rows[alarmId]?.takeIf { it.state == from } ?: return 0
        rows[alarmId] = row.copy(state = to)
        changed()
        return 1
    }

    override suspend fun setState(alarmId: Long, state: AlarmState) {
        rows[alarmId]?.let { rows[alarmId] = it.copy(state = state) }
        changed()
    }
}
