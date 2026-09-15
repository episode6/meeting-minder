package com.episode6.meetingminder.permissions

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Intents for permission flows the UI can't drive with a plain runtime-permission
 * launcher (TODO.md §4.5). [appSettingsIntent] is the "two denials -> Open settings"
 * escape hatch for the calendar row (§4.1): once `shouldShowRequestPermissionRationale`
 * reports false after an actual denial, Android won't show the dialog again.
 * [appNotificationSettingsIntent] is the same escape hatch for notifications, and the
 * only route on 12/12L where `POST_NOTIFICATIONS` doesn't exist. [exactAlarmSettingsIntent]
 * is the "Alarms & reminders" special-access page, only ever needed on 12/12L (33+
 * auto-grants through `USE_EXACT_ALARM`, §4.4). [fullScreenIntentSettingsIntent] is the
 * "Full-screen alarms" special-access page (34+). The battery-optimisation intent arrives
 * with PR-13.
 */
object PermissionRequester {
    fun appSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context))

    fun appNotificationSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    fun exactAlarmSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri(context))

    /**
     * `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` for this app. The grant only exists on 34+
     * (the row reads as granted below that), so older versions just get the app's details
     * page.
     */
    fun fullScreenIntentSettingsIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, packageUri(context))
        } else {
            appSettingsIntent(context)
        }

    private fun packageUri(context: Context): Uri = Uri.fromParts("package", context.packageName, null)
}
