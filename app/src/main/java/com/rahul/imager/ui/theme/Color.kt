package com.rahul.imager.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The palette.
 *
 * One saturated blue carries every action in the app; everything else is a cool neutral so that
 * the blue is the only thing on screen competing for a tap. Surfaces are pure white and sit on a
 * faintly tinted page, which is what gives cards their edge without needing heavy shadows.
 */

// --- Brand -------------------------------------------------------------------------------------
val BrandBlue = Color(0xFF2F6BF6)
val BrandBluePressed = Color(0xFF2457D0)
val BrandBlueLight = Color(0xFF6B9BFF)
val BrandTint = Color(0xFFE7EEFE)
val BrandTintDark = Color(0xFF1D3A78)
val BrandInk = Color(0xFF10306F)

/** The hero-card wash, and the same ramp the launcher icon uses. */
val BrandGradientStart = Color(0xFF5C8BFF)
val BrandGradientEnd = Color(0xFF2450D8)

// --- Light: paper ------------------------------------------------------------------------------
val PageLight = Color(0xFFF5F7FB)
val SurfaceLight = Color(0xFFFFFFFF)
val SurfaceMutedLight = Color(0xFFF1F4F9)
val SurfaceSunkenLight = Color(0xFFE9EEF6)
val TextLight = Color(0xFF0F1B33)
val TextMutedLight = Color(0xFF64748B)
val BorderLight = Color(0xFFE3E9F2)
val BorderStrongLight = Color(0xFFCBD5E1)

// --- Dark: night -------------------------------------------------------------------------------
val PageDark = Color(0xFF0A1020)
val SurfaceDark = Color(0xFF141C2E)
val SurfaceMutedDark = Color(0xFF1B2437)
val SurfaceSunkenDark = Color(0xFF222D44)
val TextDark = Color(0xFFE9EEF7)
val TextMutedDark = Color(0xFF97A5BC)
val BorderDark = Color(0xFF263248)
val BorderStrongDark = Color(0xFF33425E)

// --- Status ------------------------------------------------------------------------------------
// Kept off the colour scheme on purpose: "this printer is healthy" has no Material slot, and a
// green that shifts with the theme stops being readable as a state at a glance.
val StatusConnected = Color(0xFF12A150)
val StatusConnectedDark = Color(0xFF4ADE80)
val StatusWarning = Color(0xFFD08700)
val StatusWarningDark = Color(0xFFFBBF24)
val StatusError = Color(0xFFDC2626)
val StatusErrorDark = Color(0xFFFF6B6B)

/** Soft washes behind status pills and inline banners. */
val StatusConnectedTint = Color(0xFFE3F7EC)
val StatusWarningTint = Color(0xFFFDF3DC)
val StatusErrorTint = Color(0xFFFDE8E8)
val StatusConnectedTintDark = Color(0xFF10321F)
val StatusWarningTintDark = Color(0xFF3A2C08)
val StatusErrorTintDark = Color(0xFF3B1616)
