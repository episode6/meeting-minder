package com.episode6.meetingminder.data.settings

import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Duration

internal class FakeSettingsRepository(initial: Settings = Settings()) : SettingsRepository {
    override val settings = MutableStateFlow(initial)

    override suspend fun setLeadTime(leadTime: Duration) {
        settings.value = settings.value.copy(leadTime = leadTime)
    }

    override suspend fun setSnoozeLength(snoozeLength: Duration) {
        settings.value = settings.value.copy(snoozeLength = snoozeLength)
    }

    override suspend fun setAutoTimeout(autoTimeout: Duration) {
        settings.value = settings.value.copy(autoTimeout = autoTimeout)
    }

    override suspend fun setSoundPool(soundPool: SoundPool) {
        settings.value = settings.value.copy(soundPool = soundPool)
    }

    override suspend fun setShowDeclined(showDeclined: Boolean) {
        settings.value = settings.value.copy(showDeclined = showDeclined)
    }

    override suspend fun setCalendarOverride(calendarId: Long, included: Boolean?) {
        val overrides = settings.value.calendarOverrides.toMutableMap()
        if (included == null) overrides.remove(calendarId) else overrides[calendarId] = included
        settings.value = settings.value.copy(calendarOverrides = overrides)
    }
}
