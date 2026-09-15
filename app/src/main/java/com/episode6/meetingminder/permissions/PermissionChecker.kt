package com.episode6.meetingminder.permissions

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.episode6.meetingminder.alarm.AlarmNotifications

/** Checks the OS permission grants Meeting Minder cares about; see [PermissionState]. */
interface PermissionChecker {
    fun currentState(): PermissionState
}

/** Bound in `di/PermissionsModule.kt`. */
class AndroidPermissionChecker(private val context: Context) : PermissionChecker {
    override fun currentState(): PermissionState = PermissionState(
        calendarGranted = context.hasGrantedPermission(Manifest.permission.READ_CALENDAR) &&
            context.hasGrantedPermission(Manifest.permission.WRITE_CALENDAR),
        notificationsGranted = AlarmNotifications.enabled(context),
        exactAlarmsGranted = context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms(),
    )
}

private fun Context.hasGrantedPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
