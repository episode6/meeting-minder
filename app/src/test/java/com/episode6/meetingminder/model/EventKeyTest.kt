package com.episode6.meetingminder.model

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.Test

class EventKeyTest {

    private val begin = 1_789_000_000_000L
    private val originalBegin = 1_788_990_000_000L

    @Test
    fun plainEvent_usesItsIdAndZero() {
        assertThat(EventKey.fromInstance(eventId = 5, begin = begin, rrule = null, rdate = null, originalId = null, originalInstanceTime = null))
            .isEqualTo(EventKey(5, 0))
    }

    @Test
    fun plainEvent_movedToANewTime_keepsItsKey() {
        val before = EventKey.fromInstance(5, begin, null, null, null, null)
        val after = EventKey.fromInstance(5, begin + 30 * 60 * 1000, null, null, null, null)
        assertThat(after).isEqualTo(before)
    }

    @Test
    fun recurringOccurrence_usesTheSeriesIdAndItsBegin() {
        assertThat(EventKey.fromInstance(5, begin, rrule = "FREQ=DAILY", rdate = null, originalId = null, originalInstanceTime = null))
            .isEqualTo(EventKey(5, begin))
        assertThat(EventKey.fromInstance(5, begin, rrule = null, rdate = "20260914T130000Z", originalId = null, originalInstanceTime = null))
            .isEqualTo(EventKey(5, begin))
    }

    @Test
    fun exceptionEvent_mapsBackToTheOccurrenceItReplaced() {
        val occurrence = EventKey.fromInstance(5, originalBegin, "FREQ=DAILY", null, null, null)
        val exception = EventKey.fromInstance(eventId = 9, begin = begin, rrule = null, rdate = null, originalId = 5, originalInstanceTime = originalBegin)
        assertThat(exception).isEqualTo(occurrence)
    }

    @Test
    fun emptyRrule_countsAsNotRecurring() {
        assertThat(EventKey.fromInstance(5, begin, rrule = "", rdate = "", originalId = null, originalInstanceTime = null))
            .isEqualTo(EventKey(5, 0))
    }
}
