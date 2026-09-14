package com.episode6.meetingminder.permissions

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Intents for permission flows the UI can't drive with a plain runtime-permission
 * launcher. [appSettingsIntent] is the "two denials -> Open settings" escape hatch for
 * the calendar row (TODO.md §4.1): once `shouldShowRequestPermissionRationale` reports
 * false after an actual denial, Android won't show the dialog again. Special-access
 * intents for the rows this PR stubs (exact alarms, full-screen intents, battery
 * optimisation) arrive with the PRs that wire those rows up (PR-8, PR-10, PR-13).
 */
object PermissionRequester {
    fun appSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
}
