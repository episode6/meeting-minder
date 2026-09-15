package com.episode6.meetingminder.model

import java.time.Instant

/**
 * One merged busy range that went into a share (TODO.md §4.2): the times, never the
 * titles. [DayPlan.sharedSnapshot] is a list of these — what the last "Share schedule"
 * (or "Share again") message actually said — used to build the "Update:" re-share text
 * once PR-11 lands.
 */
data class BusyRange(val begin: Instant, val end: Instant)
