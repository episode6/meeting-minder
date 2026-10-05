package com.episode6.meetingminder.share

import android.content.Context
import android.content.Intent
import androidx.core.app.ShareCompat

/**
 * The system share sheet (chooser) for [text] (TODO.md §4.2), with [subject] as
 * `Intent.EXTRA_SUBJECT` — an email app's subject line; the targets are matched on the
 * action and MIME type alone, so every app that takes plain text still appears and one
 * with no notion of a subject just ignores it. Built as an intent for
 * `Navigation.kt` to launch — always from there, never from a receiver or a side effect,
 * which run detached from an Activity: Android 12+ bans notification trampolines, and
 * `ShareCompat` needs a real Activity context for the chooser to appear above the app.
 * It is launched for a result only so the wiring layer hears the sheet close and can end
 * the share in flight (`AppState.shareInFlight`).
 */
fun Context.shareScheduleIntent(text: String, subject: String): Intent = ShareCompat.IntentBuilder(this)
    .setType("text/plain")
    .setSubject(subject)
    .setText(text)
    .createChooserIntent()
