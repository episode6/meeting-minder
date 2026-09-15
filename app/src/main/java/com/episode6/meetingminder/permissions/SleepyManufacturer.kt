package com.episode6.meetingminder.permissions

/**
 * Phone makers whose own battery managers put apps to sleep, or cancel their alarms, beyond
 * anything Android itself does (TODO.md §4.4/§4.5 row 6). This isn't detectable from the
 * app, so onboarding shows an instructions card on these devices, linking to
 * [dontkillmyapp.com](https://dontkillmyapp.com) (opened in the browser: the app has no
 * network access of its own).
 */
enum class SleepyManufacturer(val displayName: String, private val slug: String) {
    Samsung("Samsung", "samsung"),
    Xiaomi("Xiaomi", "xiaomi"),
    Huawei("Huawei", "huawei"),
    OnePlus("OnePlus", "oneplus"),
    ;

    /** The maker's page on dontkillmyapp.com. */
    val guideUrl: String get() = "https://dontkillmyapp.com/$slug"

    companion object {
        /** The entry for `Build.MANUFACTURER` [manufacturer] (case-insensitive), or null for every other maker. */
        fun of(manufacturer: String?): SleepyManufacturer? =
            entries.firstOrNull { it.slug.equals(manufacturer?.trim(), ignoreCase = true) }
    }
}
