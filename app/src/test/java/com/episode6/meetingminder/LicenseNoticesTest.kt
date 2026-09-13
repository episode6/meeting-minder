package com.episode6.meetingminder

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.startsWith
import org.junit.Test

/**
 * [LicenseNotices] is generated from THIRD_PARTY_LICENSES.md by the
 * `generateLicenseNotices` task at build time. This asserts the escaping round-trips,
 * so the in-app licences screen can't silently ship an empty or mangled document.
 */
class LicenseNoticesTest {

    @Test
    fun markdown_matchesTheCheckedInDocument() {
        assertThat(LicenseNotices.MARKDOWN).startsWith("# Third-party license notices")
        assertThat(LicenseNotices.MARKDOWN).contains("Apache License 2.0")
    }
}
