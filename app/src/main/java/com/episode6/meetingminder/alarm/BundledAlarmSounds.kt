package com.episode6.meetingminder.alarm

import androidx.annotation.RawRes
import com.episode6.meetingminder.R

/** One alarm sound shipped in `res/raw`; [name] is shown on the ringing screen and is its recently-used identity. */
data class BundledAlarmSound(val name: String, @param:RawRes val resId: Int)

/**
 * The OGGs bundled for the randomised alert's 30% share (TODO.md §4.4): a pick from the
 * AOSP alarm set (`frameworks/base/data/sounds/alarms/ogg`, Apache License 2.0; attributed
 * in THIRD_PARTY_LICENSES.md), so the alert has variety even on a device whose own alarm
 * ringtones are few or quiet. Proper names, not translated.
 */
object BundledAlarmSounds {
    val all: List<BundledAlarmSound> = listOf(
        BundledAlarmSound("Argon", R.raw.alarm_argon),
        BundledAlarmSound("Carbon", R.raw.alarm_carbon),
        BundledAlarmSound("Fire Drill", R.raw.alarm_fire_drill),
        BundledAlarmSound("Helium", R.raw.alarm_helium),
        BundledAlarmSound("Osmium", R.raw.alarm_osmium),
        BundledAlarmSound("Oxygen", R.raw.alarm_oxygen),
        BundledAlarmSound("Platinum", R.raw.alarm_platinum),
        BundledAlarmSound("Scandium", R.raw.alarm_scandium),
    )

    val byName: Map<String, BundledAlarmSound> = all.associateBy { it.name }
}
