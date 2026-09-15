package com.episode6.meetingminder.model

import java.time.Instant
import java.time.LocalDate

/** One day's events as last read from the provider (`CalendarRepository.eventsOn`), and when. */
data class DayEvents(
    val date: LocalDate,
    val events: List<CalendarEvent>,
    val loadedAt: Instant,
)
