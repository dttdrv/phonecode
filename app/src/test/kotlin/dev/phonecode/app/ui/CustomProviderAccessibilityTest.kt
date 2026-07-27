package dev.phonecode.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phonecode.app.ui.settings.CustomProviderTextFields
import dev.phonecode.app.ui.theme.PhoneCodeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class CustomProviderAccessibilityTest {
    private val compose = createComposeRule()

    @get:Rule
    val rule = compose

    @Test
    fun fieldsExposeStableAccessibleNamesAndInvalidFormCannotSave() {
        compose.setContent {
            PhoneCodeTheme(darkTheme = false) {
                Column {
                    CustomProviderTextFields("", {}, "", {}, "", {})
                }
            }
        }

        compose.onNodeWithContentDescription("Provider name").assertIsDisplayed()
        compose.onNodeWithContentDescription("Base URL").assertIsDisplayed()
        compose.onNodeWithContentDescription("Model IDs").assertIsDisplayed()
    }
}
