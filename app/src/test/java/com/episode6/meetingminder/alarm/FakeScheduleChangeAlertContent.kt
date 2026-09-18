package com.episode6.meetingminder.alarm

import com.episode6.meetingminder.model.ScheduleChangeAlert
import java.time.LocalDate

/** [ScheduleChangeAlertContent] over a map: a day absent from [alerts] has nothing left to ring for. Records every [acknowledge]. */
internal class FakeScheduleChangeAlertContent(val alerts: MutableMap<LocalDate, ScheduleChangeAlert> = mutableMapOf()) : ScheduleChangeAlertContent {
    val acknowledged = mutableListOf<LocalDate>()

    override suspend fun load(date: LocalDate): ScheduleChangeAlert? = alerts[date]

    override fun acknowledge(date: LocalDate) {
        acknowledged += date
    }
}
