package com.episode6.meetingminder.data.db

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.LocalDate

/** In-memory [BusyBlockDao] for the syncer and side-effect tests: no Room, just an observable map keyed by event id. */
internal class FakeBusyBlockDao(rows: List<BusyBlockEntity> = emptyList()) : BusyBlockDao {
    private val rows = MutableStateFlow(rows.associateBy { it.eventId })

    /** Every row, in event-id order. */
    val entries: List<BusyBlockEntity> get() = rows.value.values.sortedBy { it.eventId }

    override suspend fun blocksOn(date: LocalDate): List<BusyBlockEntity> =
        entries.filter { it.date == date }.sortedWith(compareBy({ it.beginMillis }, { it.eventId }))

    override suspend fun blocksFrom(date: LocalDate): List<BusyBlockEntity> =
        entries.filter { it.date >= date }.sortedWith(compareBy({ it.date }, { it.beginMillis }, { it.eventId }))

    override fun observeEventIdRows(): Flow<List<Long>> = rows.map { it.keys.sorted() }.distinctUntilChanged()

    override suspend fun eventIdRows(): List<Long> = rows.value.keys.sorted()

    override suspend fun upsert(entity: BusyBlockEntity) {
        rows.value += entity.eventId to entity
    }

    override suspend fun delete(eventId: Long): Int {
        val had = eventId in rows.value
        rows.value -= eventId
        return if (had) 1 else 0
    }

    override suspend fun deleteOn(date: LocalDate): Int {
        val before = rows.value
        rows.value = before.filterValues { it.date != date }
        return before.size - rows.value.size
    }
}
