package com.episode6.meetingminder.di

import android.content.Context
import com.episode6.meetingminder.permissions.AndroidPermissionChecker
import com.episode6.meetingminder.permissions.PermissionChecker
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/** Binds the OS-backed [PermissionChecker]. */
@ContributesTo(AppScope::class)
interface PermissionsModule {
    @Provides
    @SingleIn(AppScope::class)
    fun permissionChecker(context: Context): PermissionChecker = AndroidPermissionChecker(context)
}
