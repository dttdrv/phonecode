package dev.phonecode.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.rememberPlatformOverscrollFactory
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * PhoneCode's monochrome, Apple-HIG-inspired theme (design/specs/design-tokens.md): AMOLED-dark and
 * pure-white light and rounded shapes. The UI reads from
 * MaterialTheme.colorScheme / typography / shapes; black & white are the only accent.
 */
@Composable
fun PhoneCodeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val overscrollFactory = rememberPlatformOverscrollFactory()
    val colors = if (darkTheme) phoneDarkColorScheme() else phoneLightColorScheme()
    CompositionLocalProvider(
        LocalOverscrollFactory provides overscrollFactory,
        LocalMisulAccent provides colors.primary,
    ) {
        MaterialTheme(
            colorScheme = colors,
            typography = PhoneTypography,
            shapes = PhoneShapes,
            content = content,
        )
    }
}
