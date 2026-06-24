package dev.phonecode.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/**
 * PhoneCode's monochrome, Apple-HIG-inspired theme (design/specs/design-tokens.md): AMOLED-dark and
 * pure-white light, Inter typography, Apple-leaning rounded shapes. The new UI reads from
 * MaterialTheme.colorScheme / typography / shapes; black & white are the only accent.
 */
@Composable
fun PhoneCodeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) phoneDarkColorScheme() else phoneLightColorScheme(),
        typography = PhoneTypography,
        shapes = PhoneShapes,
        content = content,
    )
}
