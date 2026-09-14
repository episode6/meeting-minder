package com.episode6.meetingminder.ui.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/**
 * Unwraps Compose's themed/attached [Context] to the hosting [Activity]. Needed for
 * `shouldShowRequestPermissionRationale`, which only exists on [Activity]. Returns null
 * if none is found (shouldn't happen inside an activity-hosted Composable).
 */
tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
