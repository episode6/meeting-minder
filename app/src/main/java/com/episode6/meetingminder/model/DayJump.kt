package com.episode6.meetingminder.model

import java.time.LocalDate

/**
 * A request for the day pager to show [date] (`ShowDay`: a notification's deep link, or the
 * app coming forward before today has been shown), pending until the pager lands on it
 * (`DayJumpLanded`). [id] tells one jump from the next, including two to the same date.
 */
data class DayJump(val date: LocalDate, val id: Int)
