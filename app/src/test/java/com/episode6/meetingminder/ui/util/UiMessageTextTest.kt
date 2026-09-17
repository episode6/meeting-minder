package com.episode6.meetingminder.ui.util

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.episode6.meetingminder.R
import com.episode6.meetingminder.store.UiMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** [resolve] against real resources: the plain, formatted and plurals shapes of a snackbar. */
@RunWith(RobolectricTestRunner::class)
class UiMessageTextTest {

    private val resources = RuntimeEnvironment.getApplication().resources

    @Test
    fun aMessageWithAFormatArgument_namesTheCalendarTheSyncFailedOn() {
        val message = UiMessage.next(R.string.busy_sync_failed, "Family")

        assertThat(message.resolve(resources)).isEqualTo("Couldn't sync busy times to Family")
    }

    @Test
    fun aPlainMessage_isJustTheString() {
        assertThat(UiMessage.next(R.string.respond_failed).resolve(resources))
            .isEqualTo(resources.getString(R.string.respond_failed))
    }

    @Test
    fun aPluralMessage_picksTheQuantity() {
        assertThat(UiMessage.nextPlural(R.plurals.day_alarms_set_today, 2, 2).resolve(resources))
            .isEqualTo(resources.getQuantityString(R.plurals.day_alarms_set_today, 2, 2))
    }
}
