package com.episode6.meetingminder.data.settings

import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Duration

internal class FakeSettingsRepository(initial: Settings = Settings()) : SettingsRepository {
    override val settings = MutableStateFlow(initial)

    override suspend fun setLeadTime(leadTime: Duration) {
        settings.value = settings.value.copy(leadTime = leadTime)
    }
}
