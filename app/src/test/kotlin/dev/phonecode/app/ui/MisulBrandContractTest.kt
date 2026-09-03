package dev.phonecode.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import dev.phonecode.app.ui.theme.MisulCobaltDark
import dev.phonecode.app.ui.theme.MisulCobaltLight
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MisulBrandContractTest {

    @Test
    fun cobaltTokensPreserveMisulOklchCoordinates() {
        assertOklch(MisulCobaltDark, lightness = 0.720f, chroma = 0.190f, hueDegrees = 255f)
        assertOklch(MisulCobaltLight, lightness = 0.500f, chroma = 0.220f, hueDegrees = 255f)
    }

    @Test
    fun userFacingSurfacesUseMisulAgentName() {
        val surfaces = listOf(
            "app/src/main/kotlin/dev/phonecode/app/ui/onboarding/ModelSetupScreen.kt",
            "app/src/main/kotlin/dev/phonecode/app/ui/chat/ChatScreen.kt",
            "app/src/main/kotlin/dev/phonecode/app/ui/settings/SettingsScreen.kt",
            "app/src/main/kotlin/dev/phonecode/app/agent/ChatViewModel.kt",
            "app/src/main/kotlin/dev/phonecode/app/agent/ExtensionConfigTools.kt",
            "app/src/main/kotlin/dev/phonecode/app/agent/ProjectInstructions.kt",
            "app/src/main/kotlin/dev/phonecode/app/agent/TurnService.kt",
            "app/src/main/kotlin/dev/phonecode/app/auth/CodexAuth.kt",
        )
        val staleName = Regex("\"[^\n\"]*PhoneCode[^\n\"]*\"")
        val matches = surfaces.flatMap { path ->
            staleName.findAll(source(path)).map { "$path: ${it.value}" }.toList()
        }.filterNot { it.endsWith(": \"PhoneCode:turn\"") }

        assertTrue(matches.joinToString("\n"), matches.isEmpty())
    }

    @Test
    fun editingSurfacesUseMisulAccentForCarets() {
        val fields = source("app/src/main/kotlin/dev/phonecode/app/ui/components/Fields.kt")
        val chat = source("app/src/main/kotlin/dev/phonecode/app/ui/chat/ChatScreen.kt")
        val composer = source("app/src/main/kotlin/dev/phonecode/app/ui/chat/ChatComposer.kt")
        val overlays = source("app/src/main/kotlin/dev/phonecode/app/ui/chat/ChatOverlays.kt")
        val accentedCaret = "cursorBrush = SolidColor(LocalMisulAccent.current)"

        assertEquals(2, fields.windowed(accentedCaret.length).count { it == accentedCaret })
        assertEquals(0, chat.windowed(accentedCaret.length).count { it == accentedCaret })
        assertEquals(1, composer.windowed(accentedCaret.length).count { it == accentedCaret })
        assertEquals(2, overlays.windowed(accentedCaret.length).count { it == accentedCaret })
    }

    private fun assertOklch(
        color: Color,
        lightness: Float,
        chroma: Float,
        hueDegrees: Float,
    ) {
        assertEquals(ColorSpaces.Oklab, color.colorSpace)
        assertEquals(lightness, color.red, 0.0005f)
        assertEquals(chroma, hypot(color.green, color.blue), 0.0005f)

        val actualHue = ((atan2(color.blue, color.green) * 180f / PI.toFloat()) + 360f) % 360f
        assertEquals(hueDegrees, actualHue, 0.05f)
    }

    private fun source(relativePath: String): String = File(projectRoot, relativePath).readText()

    private val projectRoot: File = generateSequence(
        File(requireNotNull(System.getProperty("user.dir"))).absoluteFile,
    ) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
}
