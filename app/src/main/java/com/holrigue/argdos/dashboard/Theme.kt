package com.holrigue.argdos.dashboard

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * DotOS charter for the companion app: the same monochrome-with-red language as
 * the watch (white / grey text on black, a single red accent). Kept to Material3
 * tokens so every stock control (Button, Switch, Card, Slider...) picks it up
 * without per-widget styling.
 */

// Palette — mirrors the firmware's dot_* colours (see the watch's theme).
val DotBlack   = Color(0xFF000000)
val DotSurface = Color(0xFF111111)
val DotWhite   = Color(0xFFFFFFFF)
val DotGrey    = Color(0xFF9A9A9A)
val DotRed     = Color(0xFFE02020)

private val DotColors = darkColorScheme(
    primary            = DotRed,      // accents: filled buttons, switch-on, slider
    onPrimary          = DotWhite,
    secondary          = DotWhite,
    onSecondary        = DotBlack,
    background          = DotBlack,
    onBackground        = DotWhite,
    surface            = DotSurface,  // cards
    onSurface          = DotWhite,
    surfaceVariant     = DotSurface,
    onSurfaceVariant   = DotGrey,     // secondary text
    outline            = Color(0xFF333333),
    error              = DotRed,
    onError            = DotWhite,
)

// Monospace throughout evokes the dot-matrix / technical Nothing-OS feel without
// bundling a custom font. Headline gets extra tracking to read as a mark.
private val Mono = FontFamily.Monospace

private val DotTypography = Typography().let { base ->
    base.copy(
        headlineSmall = base.headlineSmall.copy(
            fontFamily = Mono, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
        ),
        titleMedium = base.titleMedium.copy(fontFamily = Mono, fontWeight = FontWeight.Bold),
        bodyMedium  = base.bodyMedium.copy(fontFamily = Mono),
        bodySmall   = base.bodySmall.copy(fontFamily = Mono),
        labelLarge  = base.labelLarge.copy(fontFamily = Mono, letterSpacing = 1.sp), // button text
    )
}

@Composable
fun DotTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DotColors,
        typography = DotTypography,
        content = content,
    )
}

// Small caption style for the branding subtitle.
val DotCaption = TextStyle(fontFamily = Mono, fontSize = 12.sp, letterSpacing = 3.sp)
