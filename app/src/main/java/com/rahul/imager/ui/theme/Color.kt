package com.rahul.imager.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The "ink and paper" palette.
 *
 * Used as the hand-tuned fallback on devices without dynamic colour (API < 31) and whenever the
 * user turns dynamic colour off. It is built from what this app is actually about: warm off-white
 * paper, near-black thermal ink, and one saturated accent the colour of a heat-set mark.
 */

// --- Light: warm paper -------------------------------------------------------------------------
val PaperWhite = Color(0xFFFBF8F3)
val PaperSurface = Color(0xFFF4EFE7)
val PaperSurfaceHigh = Color(0xFFEDE6DA)
val InkBlack = Color(0xFF1A1714)
val InkMuted = Color(0xFF5A524A)
val InkOutline = Color(0xFFCFC5B8)

// --- Dark: ink ---------------------------------------------------------------------------------
val NightBackground = Color(0xFF13110F)
val NightSurface = Color(0xFF1D1A17)
val NightSurfaceHigh = Color(0xFF272320)
val NightInk = Color(0xFFF2ECE3)
val NightInkMuted = Color(0xFFB3A99C)
val NightOutline = Color(0xFF3C3733)

// --- Accent: heat mark -------------------------------------------------------------------------
val Ember = Color(0xFFB4531B)
val EmberLight = Color(0xFFFF9A5C)
val EmberContainer = Color(0xFFFFDBC7)
val EmberContainerDark = Color(0xFF5C2600)

// --- Secondary: a cool counterweight so warnings do not all read as "warm" ----------------------
val Slate = Color(0xFF4A6572)
val SlateLight = Color(0xFF9FBAC7)
val SlateContainer = Color(0xFFD3E4EC)
val SlateContainerDark = Color(0xFF23383F)

// --- Status colours reused by the printer state dot --------------------------------------------
val StatusConnected = Color(0xFF2E7D4F)
val StatusConnectedDark = Color(0xFF6FD79B)
val StatusWarning = Color(0xFFB58B00)
val StatusWarningDark = Color(0xFFF2C94C)
