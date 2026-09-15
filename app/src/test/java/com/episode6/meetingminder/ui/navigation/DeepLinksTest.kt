package com.episode6.meetingminder.ui.navigation

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.episode6.meetingminder.MainActivity
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class DeepLinksTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val date = LocalDate.of(2026, 9, 14)

    @Test
    fun dayAndShareLinks_haveTheSpecsShape_andParseBack() {
        assertThat(DeepLinks.day(date).toString()).isEqualTo("meetingminder://day/2026-09-14")
        assertThat(DeepLinks.share(date).toString()).isEqualTo("meetingminder://share/2026-09-14")
        assertThat(DeepLinks.parse(DeepLinks.day(date).toString())).isEqualTo(DeepLink.Day(date))
        assertThat(DeepLinks.parse(DeepLinks.share(date).toString())).isEqualTo(DeepLink.Share(date))
    }

    @Test
    fun parse_ignoresAnythingElse() {
        assertThat(DeepLinks.parse(null)).isNull()
        assertThat(DeepLinks.parse("meetingminder://alarm/7")).isNull()
        assertThat(DeepLinks.parse("meetingminder://day/2026-13-40")).isNull()
        assertThat(DeepLinks.parse("https://day/2026-09-14")).isNull()
        assertThat(DeepLinks.parse("meetingminder://share/2026-09-14/extra")).isNull()
    }

    @Test
    fun activityIntent_bringsTheRunningMainActivityForward() {
        val intent = DeepLinks.activityIntent(context, DeepLinks.share(date))

        assertThat(intent.component?.className).isEqualTo(MainActivity::class.java.name)
        assertThat(intent.data).isEqualTo(DeepLinks.share(date))
        assertThat(intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0).isTrue()
        assertThat(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0).isTrue()
    }

    @Test
    fun fromIntent_readsANotificationsIntent() {
        assertThat(DeepLinks.fromIntent(DeepLinks.activityIntent(context, DeepLinks.share(date)))).isEqualTo(DeepLink.Share(date))
        assertThat(DeepLinks.fromIntent(Intent(context, MainActivity::class.java))).isNull()
        assertThat(DeepLinks.fromIntent(null)).isNull()
    }

    @Test
    fun fromIntent_ignoresALinkReplayedFromRecents() {
        // reopening the task after Back re-delivers the "Share update" that cold-started it
        val replayed = DeepLinks.activityIntent(context, DeepLinks.share(date)).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)

        assertThat(DeepLinks.fromIntent(replayed)).isNull()
    }

    @Test
    fun inbox_holdsALinkOfferedBeforeAnyoneTakesIt_andHandsItOverOnce() {
        val inbox = DeepLinkInbox()

        inbox.offer(DeepLinks.activityIntent(context, DeepLinks.share(date)))

        assertThat(inbox.links.tryReceive().getOrNull()).isEqualTo(DeepLink.Share(date))
        assertThat(inbox.links.tryReceive().getOrNull()).isNull()
    }

    @Test
    fun inbox_keepsOnlyTheLatestWaitingLink() {
        val inbox = DeepLinkInbox()

        inbox.offer(DeepLinks.activityIntent(context, DeepLinks.share(date)))
        inbox.offer(DeepLinks.activityIntent(context, DeepLinks.day(date.plusDays(1))))

        assertThat(inbox.links.tryReceive().getOrNull()).isEqualTo(DeepLink.Day(date.plusDays(1)))
        assertThat(inbox.links.tryReceive().getOrNull()).isNull()
    }

    @Test
    fun inbox_ignoresIntentsThatArentLinks_andLinksReplayedFromRecents() {
        val inbox = DeepLinkInbox()

        inbox.offer(Intent(context, MainActivity::class.java))
        inbox.offer(DeepLinks.activityIntent(context, DeepLinks.share(date)).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY))
        inbox.offer(null)

        assertThat(inbox.links.tryReceive().getOrNull()).isNull()
    }
}
