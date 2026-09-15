package com.episode6.meetingminder.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight

/** The default M3 ramp with bold titles, like podcast-hacker (TODO.md §3.7). */
internal val MeetingMinderTypography = Typography().run {
    copy(
        titleLarge = titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Bold),
    )
}
