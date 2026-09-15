package com.episode6.meetingminder.share

import android.content.Context
import androidx.core.app.ShareCompat

/**
 * Opens the system share sheet with [text] (TODO.md §4.2). Always called from
 * `Navigation.kt` — never from a receiver or a side effect, which run detached from an
 * Activity: Android 12+ bans notification trampolines, and `ShareCompat` needs a real
 * Activity context for the chooser to appear above the app.
 */
fun Context.shareSchedule(text: String) {
    ShareCompat.IntentBuilder(this)
        .setType("text/plain")
        .setText(text)
        .startChooser()
}
