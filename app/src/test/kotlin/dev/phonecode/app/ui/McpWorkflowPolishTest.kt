package dev.phonecode.app.ui

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import dev.phonecode.app.MainActivity
import dev.phonecode.app.PhoneCodeApplication
import dev.phonecode.app.agent.ChatUiState
import dev.phonecode.app.data.McpSkillRepository
import dev.phonecode.app.data.SecretValueStore
import dev.phonecode.app.ui.settings.SettingsNavigation
import dev.phonecode.app.ui.settings.SettingsRoute
import dev.phonecode.app.ui.settings.SettingsScreen
import dev.phonecode.app.ui.theme.PhoneCodeTheme
import dev.phonecode.tools.mcp.McpServerConfig
import dev.phonecode.tools.mcp.McpServerSnapshot
import dev.phonecode.tools.mcp.McpToolDef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [34],
    qualifiers = "w412dp-h915dp-xhdpi",
    shadows = [UiTestSecureKeyStore::class],
)
class McpWorkflowPolishTest {

    private lateinit var settingsNavController: NavHostController

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
        val settingsViewModel = SettingsViewModel(application)
        compose.activity.setContent {
            PhoneCodeTheme(darkTheme = false) {
                SettingsScreen(
                    vm = application.chatViewModel,
                    settingsVm = settingsViewModel,
                    onBack = {},
                    initialPage = "mcp",
                )
            }
        }
        compose.waitForIdle()
    }

    private fun showMcpServerRoute(id: String) {
        val application = app()
        val settingsViewModel = SettingsViewModel(application)
        compose.activity.setContent {
            PhoneCodeTheme(darkTheme = false) {
                val navController = rememberNavController()
                SideEffect { settingsNavController = navController }
                SettingsNavigation(
                    vm = application.chatViewModel,
                    settingsVm = settingsViewModel,
                    onExit = {},
                    startRoute = SettingsRoute.Mcp,
                    navController = navController,
                )
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { settingsNavController.navigate(SettingsRoute.McpServer(id)) }
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
    fun existingServerSeedsItsFirstEditorCompositionAndRouteBackKeepsOrDiscardsTheDraft() {
        stateFlow().value = stateFlow().value.copy(
            sessionLoading = false,
            mcpInventoryLoaded = true,
            mcpServers = mapOf(
                "Docs" to McpServerConfig(url = "https://example.com/mcp", enabled = false),
            ),
        )
        showMcp()

        compose.onNodeWithContentDescription("Docs details").performClick()
        compose.onNodeWithContentDescription("Remote URL")
            .assertTextEquals("https://example.com/mcp")
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onAllNodesWithText("Reload latest").assertCountEquals(0)

        compose.onNodeWithContentDescription("Remote URL")
            .performTextReplacement("https://changed.example/mcp")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Discard changes?").assertIsDisplayed()
        compose.onNodeWithText("Keep editing").performClick()
        compose.onNodeWithContentDescription("Remote URL")
            .assertTextEquals("https://changed.example/mcp")

        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Discard").performClick()
        compose.onNodeWithText("MCP servers").assertIsDisplayed()
        compose.onNodeWithContentDescription("Docs details").assertIsDisplayed()
    }

    @Test
    fun restoredMcpRouteWaitsForItsInventoryThenRendersThePersistedServer() {
        val state = stateFlow()
        val original = state.value
        state.value = original.copy(
            sessionLoading = false,
            mcpInventoryLoaded = false,
            mcpServers = emptyMap(),
            mcpConfigError = null,
        )
        try {
            showMcpServerRoute("Docs")
            compose.onNodeWithText("Loading MCP servers…").assertIsDisplayed()

            state.value = state.value.copy(
                mcpInventoryLoaded = true,
                mcpServers = mapOf("Docs" to McpServerConfig(url = "https://example.com/mcp")),
            )
            compose.onNodeWithContentDescription("Remote URL")
                .assertTextEquals("https://example.com/mcp")
        } finally {
            state.value = original
        }
    }

    @Test
    fun loadedMissingMcpRoutePopsToTheMcpInventory() {
        val state = stateFlow()
        val original = state.value
        state.value = original.copy(
            sessionLoading = false,
            mcpInventoryLoaded = true,
            mcpServers = emptyMap(),
            mcpConfigError = null,
        )
        try {
            showMcpServerRoute("missing")
            compose.onNodeWithText("MCP servers").assertIsDisplayed()
        } finally {
            state.value = original
        }
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
    fun changingAnEnabledServerRequiresAnotherSuccessfulTestBeforeSave() {
        stateFlow().value = stateFlow().value.copy(
            mcpServers = mapOf(
                "Docs" to McpServerConfig(url = "https://old.example/mcp", enabled = true),
            ),
        )
        showMcp()
        compose.onNodeWithContentDescription("Docs details").performClick()

        compose.onNodeWithContentDescription("Remote URL")
            .performTextReplacement("https://new.example/mcp")

        compose.onNodeWithText("Test this changed configuration", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun successfulTestForDraftADoesNotUnlockChangedDraftB() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setBodyDelay(500, TimeUnit.MILLISECONDS)
                    .setBody(
                        """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-11-25","capabilities":{},"serverInfo":{"name":"draft-a"}}}""",
                    ),
            )
            server.enqueue(MockResponse().setResponseCode(202))
            stateFlow().value = stateFlow().value.copy(
                mcpServers = mapOf(
                    "Docs" to McpServerConfig(
                        url = server.url("/mcp").toString(),
                        enabled = true,
                    ),
                ),
            )
            showMcp()
            compose.onNodeWithContentDescription("Docs details").performClick()

            compose.onNodeWithText("Test").performClick()
            val loading = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Loading")
            compose.onNodeWithText("Test").assert(loading)
            compose.onNodeWithContentDescription("Remote URL")
                .performTextReplacement("https://draft-b.example/mcp")
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("Test").fetchSemanticsNodes().none {
                    SemanticsProperties.StateDescription in it.config &&
                        it.config[SemanticsProperties.StateDescription] == "Loading"
                }
            }

            compose.onNodeWithText("Save").assertIsNotEnabled()
            compose.onNodeWithText("Test this changed configuration", substring = true)
                .assertIsDisplayed()
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun connectedResultRequiresExplicitToolReviewBeforeEnable() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse().setBody(
                    """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-11-25","capabilities":{"tools":{}},"serverInfo":{"name":"review-me"}}}""",
                ),
            )
            server.enqueue(MockResponse().setResponseCode(202))
            server.enqueue(
                MockResponse().setBody(
                    """{"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"publish","description":"Publish content","inputSchema":{"type":"object"}}]}}""",
                ),
            )
            stateFlow().value = stateFlow().value.copy(
                mcpServers = mapOf(
                    "Docs" to McpServerConfig(
                        url = server.url("/mcp").toString(),
                        enabled = false,
                    ),
                ),
            )
            showMcp()
            compose.onNodeWithContentDescription("Docs details").performClick()

            compose.onNodeWithText("Test").performClick()
            val loading = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Loading")
            compose.onNodeWithText("Test").assert(loading)
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("I reviewed the reported tools").fetchSemanticsNodes().isNotEmpty()
            }

            compose.onNodeWithText("Enabled").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("I reviewed the reported tools")
                .performScrollTo()
                .assertIsDisplayed()
                .performClick()
            compose.onNodeWithText("Enabled").performScrollTo().assertIsEnabled()

            compose.onNodeWithContentDescription("Remote URL")
                .performScrollTo()
                .performTextReplacement("https://changed.example/mcp")

            compose.onNodeWithText("Enabled").performScrollTo().assertIsNotEnabled()
            compose.onAllNodesWithText("I reviewed the reported tools")
                .assertCountEquals(0)
        } finally {
            server.shutdown()
        }
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
        compose.onNodeWithText("Available in Misul Agent").performScrollTo().assertIsDisplayed()
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
