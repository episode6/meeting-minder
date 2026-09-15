package com.episode6.meetingminder.ui.theme

import androidx.compose.ui.graphics.Color

// The episode6 orange (#FF6600) is the brand colour and the point of the palette, so
// dynamic colour stays off (see Theme.kt). Event chips take the *calendar's* own colour
// from the provider — orange is reserved for chrome: app bar accents, the FAB, checks,
// selected states of non-calendar controls, and the alarm screen.
//
// The first block of each scheme is TODO.md §3.7's table. The rest (outline, secondary
// container, inverse and surface-container roles) was filled in by the PR-13 dark theme
// pass: left unset, Material 3 falls back to its baseline *purple* palette for them, which
// is what switch tracks, outlined buttons, menus and snackbars are drawn with. They're
// derived from the same warm neutrals in light and the same greys in dark.

val OrangeLight = Color(0xFFE65C00)
val OrangeDark = Color(0xFFFF6600)

val LightPrimary = OrangeLight
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFFFDBC7)
val LightOnPrimaryContainer = Color(0xFF331100)
val LightSecondary = Color(0xFF765847)
val LightBackground = Color(0xFFFFF8F5)
val LightOnBackground = Color(0xFF201A17)
val LightSurface = Color(0xFFFFF8F5)
val LightOnSurface = Color(0xFF201A17)
val LightSurfaceVariant = Color(0xFFF3E8E1)
val LightOnSurfaceVariant = Color(0xFF58423A)
val LightOutlineVariant = Color(0xFFEADDD5)
val LightError = Color(0xFFBA1A1A)

val LightOnSecondary = Color(0xFFFFFFFF)
val LightSecondaryContainer = Color(0xFFFFDBC9)
val LightOnSecondaryContainer = Color(0xFF2B1709)
val LightOutline = Color(0xFF8C7166)
val LightInverseSurface = Color(0xFF362F2B)
val LightInverseOnSurface = Color(0xFFFBEEE8)
val LightInversePrimary = Color(0xFFFFB68F)
val LightSurfaceDim = Color(0xFFE4D7D0)
val LightSurfaceBright = Color(0xFFFFF8F5)
val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
val LightSurfaceContainerLow = Color(0xFFFEF1EA)
val LightSurfaceContainer = Color(0xFFF8EBE4)
val LightSurfaceContainerHigh = Color(0xFFF2E6DF)
val LightSurfaceContainerHighest = Color(0xFFECE0D9)

val DarkPrimary = OrangeDark
val DarkOnPrimary = Color(0xFFFFFFFF)
val DarkPrimaryContainer = Color(0xFF5C2600)
val DarkOnPrimaryContainer = Color(0xFFFFDBC7)
val DarkSecondary = Color(0xFFE0BCA8)
val DarkBackground = Color(0xFF121214)
val DarkOnBackground = Color(0xFFE6E1E1)
val DarkSurface = Color(0xFF1B1B1D)
val DarkOnSurface = Color(0xFFE6E1E1)
val DarkSurfaceVariant = Color(0xFF29292C)
val DarkOnSurfaceVariant = Color(0xFFCAC4C4)
val DarkOutlineVariant = Color(0xFF3A3A3E)
val DarkError = Color(0xFFFFB4AB)

val DarkOnSecondary = Color(0xFF442B1C)
val DarkSecondaryContainer = Color(0xFF5D4131)
val DarkOnSecondaryContainer = Color(0xFFFFDBC9)
val DarkOutline = Color(0xFF948F8F)
val DarkInverseSurface = Color(0xFFE6E1E1)
val DarkInverseOnSurface = Color(0xFF313033)
val DarkInversePrimary = OrangeLight
val DarkSurfaceDim = Color(0xFF121214)
val DarkSurfaceBright = Color(0xFF39393C)
val DarkSurfaceContainerLowest = Color(0xFF0D0D0F)
val DarkSurfaceContainerLow = Color(0xFF1B1B1D)
val DarkSurfaceContainer = Color(0xFF1F1F21)
val DarkSurfaceContainerHigh = Color(0xFF29292C)
val DarkSurfaceContainerHighest = Color(0xFF343437)

// The ringing screen (render 5) is always dark, on the dark scheme above, plus two warmer
// oranges of its own: the pulsing core behind the alarm icon and the big Dismiss button.
val AlarmPulseCore = Color(0xFF8F3A00)
val AlarmDismissContainer = Color(0xFFFFB38A)
val AlarmOnDismissContainer = Color(0xFF331100)
