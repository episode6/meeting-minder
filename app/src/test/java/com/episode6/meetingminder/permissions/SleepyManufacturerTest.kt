package com.episode6.meetingminder.permissions

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import org.junit.Test

class SleepyManufacturerTest {

    @Test
    fun of_matchesBuildManufacturerCaseInsensitively() {
        assertThat(SleepyManufacturer.of("samsung")).isEqualTo(SleepyManufacturer.Samsung)
        assertThat(SleepyManufacturer.of("Xiaomi")).isEqualTo(SleepyManufacturer.Xiaomi)
        assertThat(SleepyManufacturer.of("HUAWEI")).isEqualTo(SleepyManufacturer.Huawei)
        assertThat(SleepyManufacturer.of("OnePlus ")).isEqualTo(SleepyManufacturer.OnePlus)
    }

    @Test
    fun of_otherMakersAndUnknown_getNoCard() {
        assertThat(SleepyManufacturer.of("Google")).isNull()
        assertThat(SleepyManufacturer.of("")).isNull()
        assertThat(SleepyManufacturer.of(null)).isNull()
    }

    @Test
    fun guideUrl_isTheMakersDontKillMyAppPage() {
        assertThat(SleepyManufacturer.Samsung.guideUrl).isEqualTo("https://dontkillmyapp.com/samsung")
    }
}
