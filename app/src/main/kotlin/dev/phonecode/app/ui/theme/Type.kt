package dev.phonecode.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.phonecode.app.R

// SYSTEM font (Roboto + device variants) - the single biggest native-feel signal. Bundling Inter
// made the app read as a web wrapper (Grok ships zero custom fonts for exactly this reason; see
// design/specs/grok-design.md "system-font confidence"). The OS renders its own typeface with its
// own metrics; the name stays for the dozens of existing references.
val InterFamily = FontFamily.Default

/** JetBrains Mono - retained for code blocks / monospace. */
val PcMono = FontFamily(
    Font(R.font.jetbrainsmono_regular, FontWeight.Normal),
    Font(R.font.jetbrainsmono_medium, FontWeight.Medium),
    Font(R.font.jetbrainsmono_bold, FontWeight.Bold),
)

/** Apple Dynamic Type scale mapped onto Material3 roles, rendered in Inter. */
val PhoneTypography = Typography(
    displayLarge = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 57.sp, lineHeight = 64.sp, letterSpacing = (-0.25).sp),
    displayMedium = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 45.sp, lineHeight = 52.sp, letterSpacing = (-0.25).sp),
    displaySmall = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 41.sp, letterSpacing = (-0.03).sp),
    headlineLarge = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.03).sp),
    headlineMedium = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.03).sp),
    headlineSmall = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 25.sp, letterSpacing = (-0.03).sp),
    titleLarge = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = (-0.025).sp),
    titleMedium = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 20.sp, letterSpacing = (-0.02).sp),
    titleSmall = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 19.sp, letterSpacing = 0.sp),
    bodyLarge = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = (-0.025).sp),
    bodyMedium = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 20.sp, letterSpacing = (-0.02).sp),
    bodySmall = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 19.sp, letterSpacing = 0.sp),
    labelLarge = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.sp),
    labelMedium = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 16.sp, letterSpacing = 0.sp),
    labelSmall = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 15.sp, letterSpacing = 0.sp),
)

/** Legacy JetBrains-Mono role scale - still referenced by screens not yet migrated to the new design. */
object PcType {
    val appWordmark = TextStyle(fontFamily = PcMono, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 20.sp, letterSpacing = (-0.4).sp)
    val titleLarge = TextStyle(fontFamily = PcMono, fontWeight = FontWeight.Bold, fontSize = 19.sp, lineHeight = 24.sp, letterSpacing = (-0.4).sp)
    val display = TextStyle(fontFamily = PcMono, fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 23.sp, letterSpacing = (-0.3).sp)
    val titleMedium = TextStyle(fontFamily = PcMono, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 19.sp)
    val bodyLarge = TextStyle(fontFamily = PcMono, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp)
    val bodyMedium = TextStyle(fontFamily = PcMono, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp)
    val reasoning = TextStyle(fontFamily = PcMono, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 18.sp)
    val label = TextStyle(fontFamily = PcMono, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp)
    val labelSmall = TextStyle(fontFamily = PcMono, fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.2.sp)
    val sectionEyebrow = TextStyle(fontFamily = PcMono, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 1.4.sp)
    val code = TextStyle(fontFamily = PcMono, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 20.sp)
    val caption = TextStyle(fontFamily = PcMono, fontWeight = FontWeight.Normal, fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 0.3.sp)
}
