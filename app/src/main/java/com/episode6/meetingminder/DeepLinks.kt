package com.episode6.meetingminder

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** Where a `meetingminder://` link into [MainActivity] points (TODO.md §4.3); handled in `Navigation.kt`. */
sealed interface DeepLink {
    val date: LocalDate

    /** `meetingminder://day/{date}`: open the day view on [date] (the notifications' "Review" and body tap). */
    data class Day(override val date: LocalDate) : DeepLink

    /** `meetingminder://share/{date}`: open [date] and its share sheet (the schedule-changed notification's "Share update"). */
    data class Share(override val date: LocalDate) : DeepLink
}

/**
 * Builds and reads the app's deep links. Notifications launch [MainActivity] with one of
 * these as the intent's data through [activityIntent] (an explicit component, so no intent
 * filter is needed and no other app can send them), never through a trampoline.
 */
object DeepLinks {
    private const val SCHEME = "meetingminder"
    private const val DAY = "day"
    private const val SHARE = "share"

    fun day(date: LocalDate): Uri = Uri.Builder().scheme(SCHEME).authority(DAY).appendPath(date.toString()).build()

    fun share(date: LocalDate): Uri = Uri.Builder().scheme(SCHEME).authority(SHARE).appendPath(date.toString()).build()

    /**
     * An intent that brings [MainActivity] forward with [link]: clear-top + single-top, so an
     * already running day view gets it through `onNewIntent` instead of stacking a second
     * activity on top.
     */
    fun activityIntent(context: Context, link: Uri): Intent = Intent(context, MainActivity::class.java)
        .setAction(Intent.ACTION_VIEW)
        .setData(link)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    /** [uri] (an intent's `dataString`) as a [DeepLink], or null for anything that isn't one of ours. */
    fun parse(uri: String?): DeepLink? {
        val match = uri?.let { LinkPattern.matchEntire(it) } ?: return null
        val date = try {
            LocalDate.parse(match.groupValues[2])
        } catch (_: DateTimeParseException) {
            return null
        }
        return when (match.groupValues[1]) {
            DAY -> DeepLink.Day(date)
            else -> DeepLink.Share(date)
        }
    }

    private val LinkPattern = Regex("$SCHEME://($DAY|$SHARE)/([0-9]{4}-[0-9]{2}-[0-9]{2})/?")
}
