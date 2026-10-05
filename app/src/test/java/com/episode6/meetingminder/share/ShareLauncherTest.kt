package com.episode6.meetingminder.share

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ShareLauncherTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Suppress("DEPRECATION")
    private fun Intent.target(): Intent = getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!

    @Test
    fun shareScheduleIntent_isAPlainTextSend_withTheSubjectBesideTheText() {
        val chooser = context.shareScheduleIntent("• 9:00 – 9:30 AM", "My schedule for Monday, September 14th, 2026")

        assertThat(chooser.action).isEqualTo(Intent.ACTION_CHOOSER)
        val send = chooser.target()
        assertThat(send).isNotNull()
        // the action and type decide which apps are offered; the subject never narrows them
        assertThat(send.action).isEqualTo(Intent.ACTION_SEND)
        assertThat(send.type).isEqualTo("text/plain")
        assertThat(send.getCharSequenceExtra(Intent.EXTRA_TEXT).toString()).isEqualTo("• 9:00 – 9:30 AM")
        assertThat(send.getStringExtra(Intent.EXTRA_SUBJECT)).isEqualTo("My schedule for Monday, September 14th, 2026")
    }
}
