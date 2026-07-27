package dev.phonecode.app.ui

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phonecode.app.MainActivity
import dev.phonecode.app.PhoneCodeApplication
import dev.phonecode.app.agent.ChatUiState
import dev.phonecode.app.data.McpSkillRepository
import dev.phonecode.app.data.SecretValueStore
import dev.phonecode.app.ui.settings.SettingsScreen
import dev.phonecode.app.ui.theme.PhoneCodeTheme
import dev.phonecode.tools.mcp.McpServerConfig
import dev.phonecode.tools.mcp.McpServerSnapshot
import dev.phonecode.tools.mcp.McpToolDef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [34],
    qualifiers = "w412dp-h915dp-xhdpi",
    shadows = [UiTestSecureKeyStore::class],
)
class McpWorkflowPolishTest {

    private val seedSettings = object : ExternalResource() {
        override fun before() {
            val filesDir = ApplicationProvider.getApplicationContext<Context>().filesDir
            UiTestSecureKeyStore.replaceWith(emptyMap())
            java.io.File(filesDir, "app_settings.json").writeText("""{"onboarded":true}""")
        }
    }

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(seedSettings).around(compose)

    private fun app() = ApplicationProvider.getApplicationContext<PhoneCodeApplication>()

    private fun showMcp() {
        val application = app()
        compose.activity.setContent {
            PhoneCodeTheme(darkTheme = false) {
                SettingsScreen(
                    vm = application.chatViewModel,
                    settingsVm = SettingsViewModel(application),
                    onBack = {},
                    initialPage = "mcp",
                )
            }
        }
        compose.waitForIdle()
    }

    private fun stateFlow(): MutableStateFlow<ChatUiState> {
        val field = app().chatViewModel.javaClass.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return field.get(app().chatViewModel) as MutableStateFlow<ChatUiState>
    }

    @Test
    fun newServerStartsOffAndSaveRequiresAValidDirtyDraft() {
        showMcp()
        compose.onNodeWithText("Add server").performClick()

        compose.onNodeWithText("Enabled").assertIsOff().assertIsNotEnabled()
        compose.onNodeWithText("Test successfully before enabling").assertIsDisplayed()
        compose.onNodeWithText("Save").assertIsNotEnabled()

        compose.onNodeWithContentDescription("Server name").performTextReplacement("docs")
        compose.onNodeWithContentDescription("Remote URL").performTextReplacement("https://example.com/mcp")

        compose.onNodeWithText("Save").assertIsEnabled()
    }

    @Test
    fun serverDetailsAndEnabledSwitchAreSeparateAccessibilityActions() {
        stateFlow().value = stateFlow().value.copy(
            mcpServers = linkedMapOf(
                "Docs" to McpServerConfig(url = "https://example.com/mcp", enabled = false),
            ),
        )
        showMcp()

        compose.onNodeWithContentDescription("Docs details").assertIsDisplayed()
        compose.onNodeWithContentDescription("Docs enabled").assertIsDisplayed()
    }

    @Test
    fun savedDisabledServerStillRequiresAReviewBeforeFirstEnable() {
        stateFlow().value = stateFlow().value.copy(
            mcpServers = mapOf(
                "Docs" to McpServerConfig(url = "https://example.com/mcp", enabled = false),
            ),
        )
        showMcp()
        compose.onNodeWithContentDescription("Docs details").performClick()

        compose.onNodeWithText("Enabled").assertIsOff().assertIsNotEnabled()
        compose.onNodeWithText("Test successfully before enabling").assertIsDisplayed()
    }

    @Test
    fun savedDisabledServerCannotBypassReviewFromTheServerList() {
        stateFlow().value = stateFlow().value.copy(
            mcpServers = mapOf(
                "Docs" to McpServerConfig(url = "https://example.com/mcp", enabled = false),
            ),
        )
        showMcp()

        compose.onNodeWithContentDescription("Docs enabled").assertIsOff().assertIsNotEnabled()
        compose.onNodeWithContentDescription("Docs details").assertIsEnabled()
    }

    @Test
    fun connectedServerCanRevealAndSearchItsCompleteToolInventory() {
        val tools = (1..35).map { index ->
            McpToolDef(
                name = "tool_$index",
                title = "Tool $index",
                description = "Description $index",
                inputSchema = JsonObject(emptyMap()),
            )
        }
        stateFlow().value = stateFlow().value.copy(
            mcpServers = mapOf("Docs" to McpServerConfig(url = "https://example.com/mcp")),
            mcpSnapshots = mapOf(
                "Docs" to McpServerSnapshot(
                    connected = true,
                    capabilities = setOf("resources", "tools"),
                    tools = tools,
                ),
            ),
            mcpToolCount = tools.size,
        )
        showMcp()
        compose.onNodeWithContentDescription("Docs details").performClick()

        compose.onNodeWithText("Show all 35 tools").performScrollTo().performClick()
        compose.onNodeWithText("Tool 35").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Search server tools")
            .performScrollTo()
            .performTextReplacement("Tool 34")
        compose.onAllNodesWithText("Tool 34").onLast().performScrollTo().assertIsDisplayed()
    }

    @Test
    fun connectedServerSeparatesAdvertisedAndAvailableCapabilities() {
        stateFlow().value = stateFlow().value.copy(
            mcpServers = mapOf("Docs" to McpServerConfig(url = "https://example.com/mcp")),
            mcpSnapshots = mapOf(
                "Docs" to McpServerSnapshot(
                    connected = true,
                    capabilities = setOf("resources", "tools"),
                    tools = listOf(
                        McpToolDef("search", "Search", "Search docs", JsonObject(emptyMap())),
                    ),
                ),
            ),
            mcpToolCount = 1,
        )
        showMcp()
        compose.onNodeWithContentDescription("Docs details").performClick()

        compose.onNodeWithText("Advertised capabilities").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Available in PhoneCode").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun headerValuesUseASeparateSecureField() {
        showMcp()
        compose.onNodeWithText("Add server").performClick()
        compose.onNodeWithText("Add header").performClick()

        compose.onNodeWithContentDescription("Header name 1").assertIsDisplayed()
        val value = compose.onNodeWithContentDescription("Header value 1").assertIsDisplayed()
            .fetchSemanticsNode()
        assertTrue(SemanticsProperties.Password in value.config)
    }

    @Test
    fun failedSaveDoesNotMisclassifyTheExistingConfigAsMalformed() {
        val vm = app().chatViewModel
        val repoField = vm.javaClass.getDeclaredField("repo").apply { isAccessible = true }
        val keyStoreField = vm.javaClass.getDeclaredField("keyStore").apply { isAccessible = true }
        val originalRepo = repoField.get(vm)
        repoField.set(
            vm,
            McpSkillRepository(
                java.io.File(app().filesDir, "config"),
                keyStoreField.get(vm) as SecretValueStore,
            ) { _, _ -> error("Storage is read only") },
        )

        try {
            val result = runBlocking {
                vm.saveMcpServerAndWait(
                    "docs",
                    McpServerConfig(url = "https://example.com/mcp", enabled = false),
                )
            }

            assertTrue(result.isFailure)
            assertNull(vm.state.value.mcpConfigError)
        } finally {
            repoField.set(vm, originalRepo)
        }
    }
}
