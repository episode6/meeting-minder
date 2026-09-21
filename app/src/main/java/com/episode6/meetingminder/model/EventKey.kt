package com.episode6.meetingminder.model

/**
 * Stable identity of one event occurrence that SURVIVES the occurrence being moved
 * (TODO.md §3.4):
 *  - non-recurring event:     `EventKey(eventId, instanceTime = 0)`
 *  - occurrence of a series:  `EventKey(seriesId, originalInstanceTime)`, where
 *    `originalInstanceTime` is `ORIGINAL_INSTANCE_TIME` for an exception event
 *    (`ORIGINAL_ID != null`) and `Instances.BEGIN` for a not-yet-excepted occurrence.
 *
 * A plain event dragged to a new time keeps its key; a single occurrence edited in Google
 * (which becomes an exception event with a new `Events._ID`) maps back to the same key; a
 * whole series shifted by its organizer changes every occurrence's key and is treated as
 * gone + new (unless its times didn't move — "this and following events" — which
 * `ChangeDetector` pairs back up as no change). The key deliberately never contains the *current* start time, and never an
 * `Instances._ID` (regenerated whenever the provider re-expands).
 */
data class EventKey(val eventId: Long, val instanceTime: Long) {

    companion object {
        /** Normalises one `Instances` row's identity columns into its key (see the class doc). */
        fun fromInstance(
            eventId: Long,
            begin: Long,
            rrule: String?,
            rdate: String?,
            originalId: Long?,
            originalInstanceTime: Long?,
        ): EventKey = when {
            // The provider requires ORIGINAL_INSTANCE_TIME whenever ORIGINAL_ID is set, so the
            // `?: begin` branch is unreachable in practice and only there for null safety —
            // an exception without it would get a key that changes when it moves.
            originalId != null -> EventKey(originalId, originalInstanceTime ?: begin)
            !rrule.isNullOrEmpty() || !rdate.isNullOrEmpty() -> EventKey(eventId, begin)
            else -> EventKey(eventId, instanceTime = 0)
        }
    }
}
