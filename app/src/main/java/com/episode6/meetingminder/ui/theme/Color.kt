package com.episode6.meetingminder.ui.theme

import androidx.compose.ui.graphics.Color

// The episode6 orange (#FF6600) is the brand colour and the point of the palette, so
// dynamic colour stays off (see Theme.kt). Event chips take the *calendar's* own colour
// from the provider — orange is reserved for chrome: app bar accents, the FAB, checks,
// selected states of non-calendar controls, and the alarm screen.

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
