package dev.phonecode.app.ui

import dev.phonecode.app.ui.settings.monogram
import org.junit.Assert.assertEquals
import org.junit.Test

class PluginMonogramTest {
    @Test fun providersSharingAFirstLetterStayDistinct() {
        assertEquals("OR", monogram("OpenRouter"))
        assertEquals("OZ", monogram("OpenCode Zen"))
        assertEquals("OG", monogram("OpenCode Go"))
    }

    @Test fun plainAndEmptyNames() {
        assertEquals("S", monogram("Stripe"))
        assertEquals("AK", monogram("AWS Knowledge"))
        assertEquals("?", monogram("  "))
    }
}
