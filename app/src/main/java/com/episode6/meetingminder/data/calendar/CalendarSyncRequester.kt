package com.episode6.meetingminder.data.calendar

import android.content.ContentResolver
import android.os.Bundle
import android.provider.CalendarContract

/**
 * The app-bar "Refresh" button's other half (the first is a plain reload of the loaded
 * window): asks the sync framework to sync every account's calendars now, the way
 * Google Calendar's pull-to-refresh does. The app itself still never touches the
 * network — the request goes to the accounts' own sync adapters, and with none (a
 * `LOCAL` calendar only) it is simply a no-op. Anything the sync writes reaches the app
 * through the provider's change notification like any other change.
 */
fun interface CalendarSyncRequester {
    fun requestSync()
}

/**
 * `ContentResolver.requestSync` for the calendar authority across every account
 * (`null` account), flagged manual + expedited so it runs at once rather than when the
 * scheduler gets round to it. A one-off request like this needs no permission.
 */
class ContentResolverCalendarSyncRequester : CalendarSyncRequester {
    override fun requestSync() {
        val extras = Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
        }
        ContentResolver.requestSync(null, CalendarContract.AUTHORITY, extras)
    }
}
