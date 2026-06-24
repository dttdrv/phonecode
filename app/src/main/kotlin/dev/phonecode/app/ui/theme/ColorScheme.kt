package dev.phonecode.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// PhoneCode monochrome palette - Grok-ramp revision (design/specs/grok-design.md). Depth comes
// from TONE STEPS, not shadows: soft-black canvas, one step for surfaces, one for elevated chrome.
// Black and white ARE the accent - no hue except iOS system red for errors. Reference roles via
// MaterialTheme.colorScheme, never these primitives.
private val Black = Color(0xFF000000)
private val White = Color(0xFFFFFFFF)

private val Surface0 = Color(0xFF141414) // canvas (soft black - avoids OLED smear, reads premium)
private val Surface1 = Color(0xFF1E1E1E) // cards / tool steps
private val Surface2 = Color(0xFF212121) // composer, chips, user bubble
private val Surface3 = Color(0xFF2A2A2A) // elevated popovers/menus
private val Surface4 = Color(0xFF333333)

private val LightBg = Color(0xFFFDFDFD)
private val LightSurf = Color(0xFFF5F5F5)
private val LightElev = Color(0xFFF8F8F8)
private val LightInput = Color(0xFFEFEFEF)
private val LightField = Color(0xFFE9E9E9)

private val DarkInk1 = Color(0xFFFFFFFF)
private val DarkInk2 = Color(0x9EEBEBF5)
private val DarkInk3 = Color(0x66EBEBF5)
private val LightInk1 = Color(0xFF000000)
private val LightInk2 = Color(0x9E3C3C43)
private val LightInk3 = Color(0x6B3C3C43)

// Edge-definition rings (Grok: a solid one-step ring, not an alpha hairline).
private val DarkHair = Color(0xFF2A2A2A)
private val DarkHair2 = Color(0xFF383838)
private val LightHair = Color(0xFFE5E5E5)
private val LightHair2 = Color(0xFFD9D9D9)

private val ErrorRed = Color(0xFFFF453A)
private val ErrorRedDk = Color(0xFF3A0A07)
private val ErrorRedLt = Color(0xFFFF3B30)
private val ErrorRedLtC = Color(0xFFFFE5E3)

fun phoneDarkColorScheme(): ColorScheme = darkColorScheme(
    primary = White, onPrimary = Black, primaryContainer = Surface3, onPrimaryContainer = White,
    secondary = DarkInk2, onSecondary = Black, secondaryContainer = Surface2, onSecondaryContainer = DarkInk2,
    tertiary = DarkInk3, onTertiary = Black, tertiaryContainer = Surface1, onTertiaryContainer = DarkInk3,
    background = Surface0, onBackground = White,
    surface = Surface1, onSurface = White, surfaceVariant = Surface2, onSurfaceVariant = DarkInk2,
    surfaceTint = White, surfaceBright = Surface4, surfaceDim = Surface0,
    surfaceContainerLowest = Surface0, surfaceContainerLow = Surface1, surfaceContainer = Surface2,
    surfaceContainerHigh = Surface3, surfaceContainerHighest = Surface4,
    inverseSurface = LightSurf, inverseOnSurface = Black, inversePrimary = Black,
    outline = DarkHair, outlineVariant = DarkHair2, scrim = Color(0x80000000),
    error = ErrorRed, onError = Black, errorContainer = ErrorRedDk, onErrorContainer = ErrorRed,
)

fun phoneLightColorScheme(): ColorScheme = lightColorScheme(
    primary = Black, onPrimary = White, primaryContainer = LightInput, onPrimaryContainer = Black,
    secondary = LightInk2, onSecondary = White, secondaryContainer = LightSurf, onSecondaryContainer = LightInk2,
    tertiary = LightInk3, onTertiary = White, tertiaryContainer = LightBg, onTertiaryContainer = LightInk3,
    background = LightBg, onBackground = Black,
    surface = LightSurf, onSurface = Black, surfaceVariant = LightElev, onSurfaceVariant = LightInk2,
    surfaceTint = Black, surfaceBright = LightBg, surfaceDim = LightSurf,
    surfaceContainerLowest = LightBg, surfaceContainerLow = LightSurf, surfaceContainer = LightElev,
    surfaceContainerHigh = LightInput, surfaceContainerHighest = LightField,
    inverseSurface = Surface2, inverseOnSurface = White, inversePrimary = White,
    outline = LightHair, outlineVariant = LightHair2, scrim = Color(0x80000000),
    error = ErrorRedLt, onError = White, errorContainer = ErrorRedLtC, onErrorContainer = Color(0xFF8B0000),
)
