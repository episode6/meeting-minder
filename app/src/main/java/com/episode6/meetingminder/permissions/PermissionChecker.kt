package com.episode6.meetingminder.permissions

import android.Manifest
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.episode6.meetingminder.alarm.AlarmNotifications

/** Checks the OS permission grants Meeting Minder cares about; see [PermissionState]. */
interface PermissionChecker {
    fun currentState(): PermissionState
}

/**
 * Bound in `di/PermissionsModule.kt`. [canUseFullScreenIntent] is the platform check by
 * default; tests substitute it because Robolectric doesn't model the full-screen-intent
 * app op.
 */
class AndroidPermissionChecker(
    private val context: Context,
    private val canUseFullScreenIntent: () -> Boolean = { context.canUseFullScreenIntent() },
) : PermissionChecker {
    override fun currentState(): PermissionState = PermissionState(
        calendarGranted = context.hasGrantedPermission(Manifest.permission.READ_CALENDAR) &&
            context.hasGrantedPermission(Manifest.permission.WRITE_CALENDAR),
        notificationsGranted = AlarmNotifications.enabled(context),
        exactAlarmsGranted = context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms(),
        fullScreenIntentGranted = canUseFullScreenIntent(),
        ignoringBatteryOptimizations = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName),
        backgroundRestricted = context.isBackgroundRestricted(),
    )
}

private fun Context.hasGrantedPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

private fun Context.canUseFullScreenIntent(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
        getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

/**
 * The user's "Restricted" battery setting (which is what `isBackgroundRestricted` reports on
 * 12+), or the system having put the app in the restricted standby bucket on its own. Both
 * need no permission for the app's own state.
 */
private fun Context.isBackgroundRestricted(): Boolean =
    getSystemService(ActivityManager::class.java).isBackgroundRestricted ||
        getSystemService(UsageStatsManager::class.java).appStandbyBucket == UsageStatsManager.STANDBY_BUCKET_RESTRICTED
