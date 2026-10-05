package com.episode6.meetingminder.share

import android.content.Context
import android.content.Intent
import androidx.core.content.IntentCompat
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ShareLauncherTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun Intent.target(): Intent = IntentCompat.getParcelableExtra(this, Intent.EXTRA_INTENT, Intent::class.java)!!

    @Test
    fun shareScheduleIntent_isAPlainTextSend_withTheSubjectBesideTheText() {
        val chooser = context.shareScheduleIntent("• 9:00 – 9:30 AM", "My schedule for Monday, September 14th, 2026")

        assertThat(chooser.action).isEqualTo(Intent.ACTION_CHOOSER)
        val send = chooser.target()
        // the action and type decide which apps are offered; the subject never narrows them
        assertThat(send.action).isEqualTo(Intent.ACTION_SEND)
        assertThat(send.type).isEqualTo("text/plain")
        assertThat(send.getCharSequenceExtra(Intent.EXTRA_TEXT).toString()).isEqualTo("• 9:00 – 9:30 AM")
        assertThat(send.getStringExtra(Intent.EXTRA_SUBJECT)).isEqualTo("My schedule for Monday, September 14th, 2026")
    }
}
