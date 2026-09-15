package com.episode6.meetingminder.data.db

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

/** In-memory [ChangeSnapshotDao] for side-effect tests: no Room, just an observable map keyed by date. */
internal class FakeChangeSnapshotDao(entities: List<ChangeSnapshotEntity> = emptyList()) : ChangeSnapshotDao {
    private val rows = MutableStateFlow(entities.associateBy { it.date })

    val entries: Map<LocalDate, ChangeSnapshotEntity> get() = rows.value

    override suspend fun upsert(entity: ChangeSnapshotEntity) {
        rows.value += entity.date to entity
    }

    override suspend fun forDate(date: LocalDate): ChangeSnapshotEntity? = rows.value[date]

    override suspend fun all(): List<ChangeSnapshotEntity> = rows.value.values.sortedBy { it.date }

    override fun observeAll() = rows.map { it.values.sortedBy { row -> row.date } }

    override suspend fun setChanges(date: LocalDate, takenAt: Long, changesJson: String): Int {
        val row = rows.value[date]?.takeIf { it.takenAt == takenAt } ?: return 0
        rows.value += date to row.copy(changesJson = changesJson)
        return 1
    }

    override suspend fun delete(date: LocalDate) {
        rows.value -= date
    }
}
