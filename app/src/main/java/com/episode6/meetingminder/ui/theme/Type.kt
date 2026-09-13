package com.episode6.meetingminder.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight

/** The default M3 ramp with bold titles, like podcast-hacker (TODO.md §3.7). */
internal val MeetingMinderTypography = Typography().let {
    it.copy(
        titleLarge = it.titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = it.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        headlineMedium = it.headlineMedium.copy(fontWeight = FontWeight.Bold),
    )
}
