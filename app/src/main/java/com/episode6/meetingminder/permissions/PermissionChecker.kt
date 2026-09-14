package com.episode6.meetingminder.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/** Checks the OS permission grants Meeting Minder cares about; see [PermissionState]. */
interface PermissionChecker {
    fun currentState(): PermissionState
}

/** Bound in `di/PermissionsModule.kt`. */
class AndroidPermissionChecker(private val context: Context) : PermissionChecker {
    override fun currentState(): PermissionState = PermissionState(
        calendarGranted = context.hasGrantedPermission(Manifest.permission.READ_CALENDAR) &&
            context.hasGrantedPermission(Manifest.permission.WRITE_CALENDAR),
    )
}

private fun Context.hasGrantedPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
