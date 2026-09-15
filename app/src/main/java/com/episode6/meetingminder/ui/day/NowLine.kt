package com.episode6.meetingminder.ui.day

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color

/**
 * The red "now" marker on today's timeline: a 2dp line across the events column with a
 * 12dp dot centred on its start edge. The composable is [DayViewDefaults.NowDotSize] tall
 * with the line through its middle, so place it with its centre on the current time.
 */
@Composable
fun NowLine(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.error,
) {
    Canvas(
        modifier
            .fillMaxWidth()
            .height(DayViewDefaults.NowDotSize),
    ) {
        val y = size.height / 2
        drawLine(
            color = color,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = DayViewDefaults.NowLineThickness.toPx(),
        )
        drawCircle(color = color, radius = DayViewDefaults.NowDotSize.toPx() / 2, center = Offset(0f, y))
    }
}
