package com.episode6.meetingminder.data.calendar

import android.content.Context
import android.net.Uri
import android.provider.CalendarContract
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ContentResolverCalendarChangeSourceTest {

    private val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
    private val observers get() = shadowOf(resolver).getContentObservers(CalendarContract.CONTENT_URI)

    @Test
    fun observesTheProviderRoot_includingDescendantUris_onlyWhileCollected() = runTest {
        val source = ContentResolverCalendarChangeSource(resolver)
        assertThat(observers).isEmpty()

        source.changes().test {
            yield()
            assertThat(observers).hasSize(1)

            // the provider notifies the root, but a descendant must count too
            resolver.notifyChange(CalendarContract.CONTENT_URI, null)
            assertThat(awaitItem()).isEqualTo(Unit)
            resolver.notifyChange(Uri.withAppendedPath(CalendarContract.CONTENT_URI, "events"), null)
            assertThat(awaitItem()).isEqualTo(Unit)

            cancel()
        }

        assertThat(observers).isEmpty()
    }
}
