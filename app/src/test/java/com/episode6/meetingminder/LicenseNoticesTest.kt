package com.episode6.meetingminder

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.Test
import java.io.File

/**
 * [LicenseNotices] is generated from THIRD_PARTY_LICENSES.md by the
 * `generateLicenseNotices` task at build time. A full comparison (not a spot check) is
 * the point: the task hand-escapes backslashes, quotes, `$` and newlines into a Kotlin
 * string literal, and a future licence entry containing any of them would otherwise
 * corrupt the in-app licences screen silently.
 */
class LicenseNoticesTest {

    @Test
    fun markdown_matchesTheCheckedInDocument() {
        // unit tests run with app/ as the working directory
        val expected = File("../THIRD_PARTY_LICENSES.md").readText().replace("\r", "")
        assertThat(LicenseNotices.MARKDOWN).isEqualTo(expected)
    }
}
