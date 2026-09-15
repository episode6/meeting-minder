package com.episode6.meetingminder.data.db

import java.time.LocalDate

/** In-memory [ChangeSnapshotDao] for side-effect tests: no Room, just a map keyed by date. */
internal class FakeChangeSnapshotDao(entities: List<ChangeSnapshotEntity> = emptyList()) : ChangeSnapshotDao {
    val entries = entities.associateBy { it.date }.toMutableMap()

    override suspend fun upsert(entity: ChangeSnapshotEntity) {
        entries[entity.date] = entity
    }

    override suspend fun forDate(date: LocalDate): ChangeSnapshotEntity? = entries[date]

    override suspend fun delete(date: LocalDate) {
        entries.remove(date)
    }
}
