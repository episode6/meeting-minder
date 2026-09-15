package com.episode6.meetingminder.ui.day

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp

/** Every dimension and alpha the day timeline uses (TODO.md §3.5); no magic numbers in layout code. */
object DayViewDefaults {
    /** Height of one hour on the timeline; event heights are proportional to it. */
    val HourHeight = 64.dp

    /** Width of the hour-label gutter left of the events column (and of the all-day label). */
    val GutterWidth = 56.dp

    /** Space between an hour label and the grid. */
    val GutterLabelEndPadding = 8.dp

    /**
     * Gutter and all-day labels shrink toward this size when they don't fit [GutterWidth]
     * (large font scales, long localised labels) and ellipsize below it.
     */
    val GutterLabelMinFontSize = 8.sp

    /** Hour the day view opens at when today has no meeting (see `initialFirstVisibleHour`). */
    const val DefaultFirstVisibleHour = 8

    /** Pages in the day pager: about 27 years either side of the anchor, effectively unbounded. */
    const val PagerPageCount = 20_000

    /** The pager page that shows `AppState.anchorDate`. */
    const val PagerAnchorPage = 10_000

    /** Neighbouring days kept composed (and loaded) either side of the settled page. */
    const val PagerBeyondViewportPageCount = 1

    /** Space above midnight so the first grid line isn't flush with the all-day divider. */
    val TimelineTopPadding = 8.dp

    /** Bottom padding of the scrolled timeline so the last events clear the FAB. */
    val TimelineBottomPadding = 88.dp

    val GridLineThickness = 1.dp

    /** Left inset of the events column from the grid's start, so chips don't touch the gutter. */
    val EventsStartPadding = 4.dp

    /** Right inset of the events column; the grid and now-line still run to the edge. */
    val EventsEndPadding = 16.dp

    /** Horizontal space between side-by-side (overlapping) chips. */
    val ColumnGap = 4.dp

    /** Vertical space between back-to-back chips. */
    val ChipVerticalGap = 1.dp

    /** Visual floor for short events; shorter events keep this height (and are packed as if they were this long). */
    val MinChipHeight = 24.dp

    val ChipCornerRadius = 6.dp
    val ChipBorderWidth = 1.5.dp
    val ChipDeclinedBorderWidth = 1.dp
    val ChipDeclinedDashOn = 4.dp
    val ChipDeclinedDashOff = 3.dp
    val ChipHorizontalPadding = 8.dp
    val ChipVerticalPadding = 3.dp
    val ChipIconSize = 16.dp
    val ChipInlineIconSize = 12.dp
    val ChipIconSpacing = 4.dp

    /** Nudges the check icon down to sit on the title's first line. */
    val ChipCheckIconTopPadding = 2.dp

    /** Space between the inline bell and the alarm time. */
    val ChipInlineIconSpacing = 2.dp

    /** The app bar subtitle's bell once alarms are set (render 3). */
    val SubtitleIconSize = 14.dp
    val SubtitleIconSpacing = 4.dp

    /**
     * Height of the chip for [span]: proportional to its duration minus [ChipVerticalGap], floored at
     * [MinChipHeight]. The one formula both [DayEventsLayout] (placement) and the chip content choice use.
     */
    fun chipHeight(span: MinuteSpan, hourHeight: Dp = HourHeight): Dp =
        max(hourHeight * (span.duration / 60f) - ChipVerticalGap, MinChipHeight)

    /** Unselected chips: the calendar colour at this alpha as fill, plus a border in the full colour. */
    const val UnselectedFillAlpha = 0.12f

    /** Unselected chips you've answered "maybe" to. */
    const val TentativeFillAlpha = 0.4f

    /** Events on today that have already ended. */
    const val PastEventAlpha = 0.6f

    /** A solid calendar colour lighter than this gets dark chip text instead of white. */
    const val LightChipLuminance = 0.5f

    val NowLineThickness = 2.dp
    val NowDotSize = 12.dp

    val AllDayRowVerticalPadding = 4.dp
    val AllDayChipSpacing = 2.dp
    val AllDayChipHeight = 24.dp

    // the "changed since you shared" banner (render 6)
    val BannerOuterHorizontalPadding = 12.dp
    val BannerOuterVerticalPadding = 4.dp
    val BannerCornerRadius = 12.dp
    val BannerContentStartPadding = 16.dp
    val BannerContentEndPadding = 8.dp
    val BannerContentVerticalPadding = 8.dp
    val BannerContentSpacing = 12.dp
    const val BannerDetailMaxLines = 2
}
