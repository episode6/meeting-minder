package com.episode6.meetingminder.data.calendar

import android.content.ContentResolver
import android.database.ContentObserver
import android.provider.CalendarContract
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow

/** Tells whoever is collecting that something in the Calendar Provider changed. */
fun interface CalendarChangeSource {
    /**
     * Emits once per provider change notification (bursts are conflated, not debounced) for
     * as long as it is collected; nothing is registered while nobody collects.
     */
    fun changes(): Flow<Unit>
}

/**
 * [CalendarChangeSource] over a `ContentObserver` (TODO.md §4.3, mechanism 1). The calendar
 * provider only ever notifies its **root** URI, so the observer is registered on
 * [CalendarContract.CONTENT_URI] with `notifyForDescendants = true`, and it can't say what
 * changed: every notification means "reload and diff". Registered on collection and
 * unregistered when the collector is cancelled.
 */
class ContentResolverCalendarChangeSource(private val contentResolver: ContentResolver) : CalendarChangeSource {
    override fun changes(): Flow<Unit> = callbackFlow {
        // a null handler delivers onChange on the binder thread; trySend is thread-safe
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer)
        awaitClose { contentResolver.unregisterContentObserver(observer) }
    }.buffer(Channel.CONFLATED)
}
