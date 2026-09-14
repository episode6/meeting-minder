package com.episode6.meetingminder.ui.day

import androidx.compose.ui.unit.dp
import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.Test

class ChipContentLayoutTest {

    // vertical padding 3dp × 2 = 6dp; TwoLine needs 6 + 20 + 16 = 42dp, Tall 6 + 40 + 16 = 62dp
    private val title = 20.dp
    private val detail = 16.dp

    @Test
    fun below_twoLines_isCompact() {
        assertThat(chipContentLayout(41.dp, title, detail)).isEqualTo(ChipContentLayout.Compact)
        assertThat(chipContentLayout(DayViewDefaults.MinChipHeight, title, detail)).isEqualTo(ChipContentLayout.Compact)
    }

    @Test
    fun exactly_twoLines_isTwoLine() {
        assertThat(chipContentLayout(42.dp, title, detail)).isEqualTo(ChipContentLayout.TwoLine)
        assertThat(chipContentLayout(61.dp, title, detail)).isEqualTo(ChipContentLayout.TwoLine)
    }

    @Test
    fun room_for_aWrappedTitle_isTall() {
        assertThat(chipContentLayout(62.dp, title, detail)).isEqualTo(ChipContentLayout.Tall)
    }

    @Test
    fun larger_fontScale_needsTallerChips() {
        // a 63dp one-hour chip is Tall at 1× line heights but only TwoLine at 1.5×
        assertThat(chipContentLayout(63.dp, title, detail)).isEqualTo(ChipContentLayout.Tall)
        assertThat(chipContentLayout(63.dp, title * 1.5f, detail * 1.5f)).isEqualTo(ChipContentLayout.TwoLine)
    }

    @Test
    fun chip_height_isProportionalMinusTheGap_withAFloor() {
        assertThat(DayViewDefaults.chipHeight(MinuteSpan(540, 600))).isEqualTo(63.dp)
        assertThat(DayViewDefaults.chipHeight(MinuteSpan(540, 545))).isEqualTo(DayViewDefaults.MinChipHeight)
    }
}
