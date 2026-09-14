package com.episode6.meetingminder.monitor

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.work.WorkManager

/**
 * Change monitoring's accelerator (TODO.md §4.3, mechanism 3): the calendar provider sends
 * `PROVIDER_CHANGED` with its `content://com.android.calendar` data on every change, and adds
 * `FLAG_RECEIVER_INCLUDE_BACKGROUND` so a manifest receiver gets it. That flag is
 * undocumented, so this only speeds things up: it enqueues the same change check the
 * content-URI trigger would ([WorkManagerChangeWorkScheduler.enqueueProviderChangedCheck],
 * one unique work that a burst of broadcasts can't multiply) and nothing breaks if the
 * broadcast stops arriving.
 *
 * The component is disabled in the manifest and switched on only while some day is shared
 * ([setEnabled], called from [WorkManagerChangeWorkScheduler.update]), so a calendar sync
 * never wakes the process when there is nothing to monitor.
 */
class CalendarProviderChangedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_PROVIDER_CHANGED || intent.data?.authority != CalendarContract.AUTHORITY) return
        WorkManagerChangeWorkScheduler.enqueueProviderChangedCheck(WorkManager.getInstance(context))
    }

    companion object {
        /**
         * Enables or disables the receiver in the package manager; a no-op when it is already
         * in that state, counting a fresh install's `DEFAULT` as disabled (the manifest's
         * `android:enabled="false"`), so the first disarm doesn't write for nothing.
         */
        fun setEnabled(context: Context, enabled: Boolean) {
            val component = ComponentName(context, CalendarProviderChangedReceiver::class.java)
            val packageManager = context.packageManager
            val currentlyEnabled = packageManager.getComponentEnabledSetting(component) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            if (currentlyEnabled == enabled) return
            val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
        }
    }
}
