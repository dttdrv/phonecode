package dev.phonecode.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phonecode.app.MainActivity
import dev.phonecode.app.PhoneCodeApplication
import dev.phonecode.app.agent.providerDeleteOperationKey
import dev.phonecode.app.data.AppSettingsStore
import dev.phonecode.app.data.CustomModel
import dev.phonecode.app.data.CustomProvider
import dev.phonecode.app.data.CustomProviderRepository
import dev.phonecode.app.data.McpSkillRepository
import dev.phonecode.app.data.SecretValueStore
import dev.phonecode.app.data.customProviderSecretName
import dev.phonecode.tools.mcp.McpServerConfig
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [34],
    qualifiers = "w412dp-h915dp-xhdpi",
    shadows = [UiTestSecureKeyStore::class],
)
class SettingsReliabilityTest {

    private val seedSettings = object : ExternalResource() {
        override fun before() {
            val filesDir = androidx.test.core.app.ApplicationProvider
                .getApplicationContext<android.content.Context>().filesDir
            UiTestSecureKeyStore.replaceWith(emptyMap())
            java.io.File(filesDir, "app_settings.json").writeText("""{"onboarded":true}""")
            java.io.File(filesDir, "config/skills/hot-skill/SKILL.md").apply {
                parentFile?.mkdirs()
                writeText(
                    """
                    ---
                    name: hot-skill
                    description: Settings reliability fixture
                    ---

                    Keep this skill active.
                    """.trimIndent(),
                )
            }
        }
    }

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(seedSettings).around(compose)

    private fun dismissOnboardingIfPresent() {
        if (compose.onAllNodesWithText("Get started").fetchSemanticsNodes().isEmpty()) return
        compose.onNodeWithText("Get started").performClick()
        compose.onNodeWithText("Explore without a model").performClick()
        compose.waitForIdle()
    }

    private fun openSettingsPage(page: String) {
        dismissOnboardingIfPresent()
        compose.onNodeWithContentDescription("Menu").performClick()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText(page).performClick()
    }

    @Test
    fun automaticApprovalConfirmationStaysBusyUntilTheSettingIsPersisted() {
        openSettingsPage("Files & permissions")
        compose.onNodeWithText("Allow changes automatically").performClick()

        val lock = AppSettingsStore::class.java.getDeclaredField("LOCK").apply {
            isAccessible = true
        }.get(null) ?: error("AppSettingsStore lock unavailable")
        val acquired = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = Thread {
            synchronized(lock) {
                acquired.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }.apply { start() }
        assertTrue(acquired.await(5, TimeUnit.SECONDS))

        try {
            compose.onNodeWithText("Enable automatic approval").performClick()

            compose.onNodeWithText("Enabling…").assertIsDisplayed().assertIsNotEnabled()
            compose.onNodeWithText("Cancel").assertIsNotEnabled()
            compose.onNodeWithText("Ask before each change").assertIsSelected()
        } finally {
            release.countDown()
            holder.join(5_000)
        }

        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Enabling…").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText("Allow changes automatically").assertIsSelected()
    }

    @Test
    fun failedMcpDeleteKeepsTheConfirmationAndFailureInContext() {
        dismissOnboardingIfPresent()
        val app = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<PhoneCodeApplication>()
        val vm = app.chatViewModel
        runBlocking {
            vm.saveMcpServerAndWait(
                "fragile-server",
                McpServerConfig(url = "https://example.com/mcp"),
            ).getOrThrow()
        }

        val repoField = vm.javaClass.getDeclaredField("repo").apply { isAccessible = true }
        val keyStoreField = vm.javaClass.getDeclaredField("keyStore").apply { isAccessible = true }
        val originalRepo = repoField.get(vm)
        val keyStore = keyStoreField.get(vm) as SecretValueStore
        val failingRepo = McpSkillRepository(
            java.io.File(app.filesDir, "config"),
            keyStore,
        ) { _, _ -> error("Storage is read only") }
        repoField.set(vm, failingRepo)

        try {
            compose.onNodeWithContentDescription("Menu").performClick()
            compose.onNodeWithContentDescription("Settings").performClick()
            compose.onNodeWithText("MCP servers").performClick()
            compose.onNodeWithContentDescription("fragile-server details").performClick()
            compose.onNodeWithText("Delete server").performClick()
            compose.onAllNodesWithText("Delete server").onLast().performClick()

            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("Storage is read only", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Delete MCP server?").assertIsDisplayed()
            compose.onNodeWithText("Storage is read only", substring = true).assertIsDisplayed()
            compose.onAllNodesWithText("Delete server").onLast().assertIsDisplayed()
        } finally {
            repoField.set(vm, originalRepo)
        }
    }

    @Test
    fun mcpDeleteConfirmationExplicitlyWarnsThatThereIsNoUndo() {
        dismissOnboardingIfPresent()
        val app = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<PhoneCodeApplication>()
        runBlocking {
            app.chatViewModel.saveMcpServerAndWait(
                "temporary-server",
                McpServerConfig(url = "https://example.com/mcp"),
            ).getOrThrow()
        }

        compose.onNodeWithContentDescription("Menu").performClick()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("MCP servers").performClick()
        compose.onNodeWithContentDescription("temporary-server details").performClick()
        compose.onNodeWithText("Delete server").performClick()

        compose.onNodeWithText("cannot be undone", substring = true, ignoreCase = true)
            .assertIsDisplayed()
    }

    @Test
    fun mcpToggleShowsPendingStateAndDisablesRepeatSubmission() {
        dismissOnboardingIfPresent()
        val app = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<PhoneCodeApplication>()
        val vm = app.chatViewModel
        runBlocking {
            vm.saveMcpServerAndWait(
                "pending-server",
                McpServerConfig(url = "http://127.0.0.1:1/mcp", enabled = true, timeout = 1_000),
            ).getOrThrow()
        }
        val repoField = vm.javaClass.getDeclaredField("repo").apply { isAccessible = true }
        val keyStoreField = vm.javaClass.getDeclaredField("keyStore").apply { isAccessible = true }
        val originalRepo = repoField.get(vm)
        val keyStore = keyStoreField.get(vm) as SecretValueStore
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        repoField.set(
            vm,
            McpSkillRepository(java.io.File(app.filesDir, "config"), keyStore) { file, text ->
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
                file.writeText(text)
            },
        )

        try {
            openSettingsPage("MCP servers")
            compose.onNodeWithContentDescription("pending-server enabled").assertIsOn().performClick()
            assertTrue(started.await(5, TimeUnit.SECONDS))

            compose.onNodeWithText("Updating…").assertIsDisplayed()
            compose.onNodeWithContentDescription("pending-server enabled").assertIsNotEnabled()
        } finally {
            release.countDown()
            repoField.set(vm, originalRepo)
        }

        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Updating…").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithContentDescription("pending-server enabled").assertIsOff()
    }

    @Test
    fun failedMcpToggleRollsBackAndShowsAnInlineRetryableError() {
        dismissOnboardingIfPresent()
        val app = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<PhoneCodeApplication>()
        val vm = app.chatViewModel
        runBlocking {
            vm.saveMcpServerAndWait(
                "fragile-toggle",
                McpServerConfig(url = "http://127.0.0.1:1/mcp", enabled = true, timeout = 1_000),
            ).getOrThrow()
        }
        val repoField = vm.javaClass.getDeclaredField("repo").apply { isAccessible = true }
        val keyStoreField = vm.javaClass.getDeclaredField("keyStore").apply { isAccessible = true }
        val originalRepo = repoField.get(vm)
        repoField.set(
            vm,
            McpSkillRepository(
                java.io.File(app.filesDir, "config"),
                keyStoreField.get(vm) as SecretValueStore,
            ) { _, _ -> error("Storage is read only") },
        )

        try {
            openSettingsPage("MCP servers")
            compose.onNodeWithContentDescription("fragile-toggle enabled").performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("Could not update fragile-toggle", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Could not update fragile-toggle", substring = true).assertIsDisplayed()
            compose.onNodeWithContentDescription("fragile-toggle enabled").assertIsOn()
        } finally {
            repoField.set(vm, originalRepo)
        }
    }

    @Test
    fun reconnectShowsPendingStateAndDisablesRepeatSubmission() {
        dismissOnboardingIfPresent()
        val app = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<PhoneCodeApplication>()
        val vm = app.chatViewModel
        runBlocking {
            vm.saveMcpServerAndWait(
                "offline-server",
                McpServerConfig(url = "http://127.0.0.1:1/mcp", enabled = false, timeout = 1_000),
            ).getOrThrow()
        }
        val mutexField = vm.javaClass.getDeclaredField("mcpReloadMutex").apply { isAccessible = true }
        val mutex = mutexField.get(vm) as Mutex
        runBlocking { mutex.lock() }

        try {
            openSettingsPage("MCP servers")
            compose.onNodeWithText("Reconnect enabled servers").performClick()

            compose.onNodeWithText("Reconnect enabled servers").assertIsDisplayed().assertIsNotEnabled()
        } finally {
            mutex.unlock()
        }
        compose.waitUntil(5_000) {
            runCatching {
                compose.onNodeWithText("Reconnect enabled servers").assertIsEnabled()
                true
            }.getOrDefault(false)
        }
    }

    @Test
    fun skillToggleShowsPendingStateAndDisablesRepeatSubmission() {
        openSettingsPage("Skills")
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("hot-skill").fetchSemanticsNodes().isNotEmpty()
        }
        val app = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<PhoneCodeApplication>()
        val vm = app.chatViewModel
        val repoField = vm.javaClass.getDeclaredField("repo").apply { isAccessible = true }
        val repo = repoField.get(vm) ?: error("MCP and skill repository unavailable")
        val acquired = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = Thread {
            synchronized(repo) {
                acquired.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }.apply { start() }
        assertTrue(acquired.await(5, TimeUnit.SECONDS))

        try {
            compose.onAllNodesWithText("hot-skill").onLast().performClick()
            compose.onNodeWithText("Enabled").assertIsOn().performClick()
            compose.onNodeWithText("Updating…").assertIsDisplayed()
            compose.onNodeWithText("Enabled").assertIsNotEnabled()
        } finally {
            release.countDown()
            holder.join(5_000)
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Updating…").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText("Enabled").assertIsOff()
    }

    @Test
    fun failedSkillDeleteKeepsTheConfirmationAndFailureInContext() {
        openSettingsPage("Skills")
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("hot-skill").fetchSemanticsNodes().isNotEmpty()
        }
        val app = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<PhoneCodeApplication>()
        val vm = app.chatViewModel
        val repoField = vm.javaClass.getDeclaredField("repo").apply { isAccessible = true }
        val keyStoreField = vm.javaClass.getDeclaredField("keyStore").apply { isAccessible = true }
        val originalRepo = repoField.get(vm)
        repoField.set(
            vm,
            McpSkillRepository(
                java.io.File(app.filesDir, "missing-skill-config"),
                keyStoreField.get(vm) as SecretValueStore,
            ),
        )

        try {
            compose.onAllNodesWithText("hot-skill").onLast().performClick()
            compose.onNodeWithText("Delete skill").performClick()
            compose.onAllNodesWithText("Delete skill").onLast().performClick()

            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("Could not delete hot-skill", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Delete skill?").assertIsDisplayed()
            compose.onNodeWithText("Could not delete hot-skill", substring = true).assertIsDisplayed()
        } finally {
            repoField.set(vm, originalRepo)
        }
    }

    @Test
    fun failedCustomProviderDeleteLeavesTheProviderAvailableForRetry() {
        dismissOnboardingIfPresent()
        val app = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<PhoneCodeApplication>()
        val vm = app.chatViewModel
        runBlocking {
            vm.saveCustomProvider(
                "fragile-provider",
                CustomProvider(
                    name = "Fragile Provider",
                    baseUrl = "https://example.com/v1",
                    models = mapOf("model" to CustomModel("Model")),
                ),
            ).getOrThrow()
        }
        val providersField = vm.javaClass.getDeclaredField("customProviders").apply { isAccessible = true }
        val originalProviders = providersField.get(vm)
        val brokenDir = java.io.File(app.filesDir, "broken-providers").apply { mkdirs() }
        java.io.File(brokenDir, "providers.json").writeText("{")

        providersField.set(vm, CustomProviderRepository(brokenDir))
        try {
            val result = runBlocking { vm.deleteCustomProviderAndWait("fragile-provider") }

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message?.contains("Provider configuration is invalid") == true)
            assertTrue(vm.isCustomProvider("fragile-provider"))
        } finally {
            providersField.set(vm, originalProviders)
        }
    }

    @Test
    fun failedCredentialRemovalRollsBackCustomProviderDeletion() {
        dismissOnboardingIfPresent()
        val app = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<PhoneCodeApplication>()
        val vm = app.chatViewModel
        val id = "rollback-provider"
        runBlocking {
            vm.saveCustomProvider(
                id,
                CustomProvider(
                    name = "Rollback Provider",
                    baseUrl = "https://example.com/v1",
                    models = mapOf("model" to CustomModel("Model")),
                ),
            ).getOrThrow()
        }
        val providersField = vm.javaClass.getDeclaredField("customProviders").apply { isAccessible = true }
        val providers = providersField.get(vm) as CustomProviderRepository
        UiTestSecureKeyStore.replaceWith(
            mapOf(
                customProviderSecretName(id) to "custom-secret",
                id to "legacy-secret",
            ),
        )
        UiTestSecureKeyStore.failNextWrite()

        val result = runBlocking { vm.deleteCustomProviderAndWait(id) }

        assertTrue(result.isFailure)
        assertTrue(id in providers.load().provider)
        assertEquals("custom-secret", UiTestSecureKeyStore.stored(customProviderSecretName(id)))
        assertEquals("legacy-secret", UiTestSecureKeyStore.stored(id))
        assertTrue(vm.isCustomProvider(id))
    }

    @Test
    fun customProviderDeletionOutlivesTheCallingUiScope() {
        dismissOnboardingIfPresent()
        val app = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<PhoneCodeApplication>()
        val vm = app.chatViewModel
        val id = "lifecycle-provider"
        runBlocking {
            vm.saveCustomProvider(
                id,
                CustomProvider(
                    name = "Lifecycle Provider",
                    baseUrl = "https://example.com/v1",
                    models = mapOf("model" to CustomModel("Model")),
                ),
            ).getOrThrow()
        }
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        UiTestSecureKeyStore.blockNextWrite(started, release)
        val callerJob = Job()
        val caller = CoroutineScope(callerJob + Dispatchers.Default)
        caller.async { vm.deleteCustomProviderAndWait(id) }

        assertTrue(started.await(5, TimeUnit.SECONDS))
        callerJob.cancel()
        release.countDown()

        compose.waitUntil(5_000) { !vm.isCustomProvider(id) }
    }

    @Test
    fun providerDeleteFailureRemainsVisibleAcrossActivityRecreation() {
        val app = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<PhoneCodeApplication>()
        val vm = app.chatViewModel
        val id = "recreated-provider"
        runBlocking {
            vm.saveCustomProvider(
                id,
                CustomProvider(
                    name = "Recreated Provider",
                    baseUrl = "https://example.com/v1",
                    models = mapOf("model" to CustomModel("Model")),
                ),
            ).getOrThrow()
        }
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        UiTestSecureKeyStore.blockNextWrite(started, release)
        UiTestSecureKeyStore.failNextWrite()
        val callerJob = Job()
        CoroutineScope(callerJob + Dispatchers.Default)
            .async { vm.deleteCustomProviderAndWait(id) }
        assertTrue(started.await(5, TimeUnit.SECONDS))

        try {
            compose.activityRule.scenario.recreate()
            callerJob.cancel()
            release.countDown()

            compose.waitUntil(5_000) {
                vm.state.value.settingsOperations[providerDeleteOperationKey(id)]
                    ?.error
                    ?.contains("Secure storage update failed") == true
            }
            assertTrue(id in (providersField(vm).load().provider))
            assertTrue(vm.isCustomProvider(id))
        } finally {
            release.countDown()
        }
    }

    private fun providersField(vm: Any): CustomProviderRepository =
        vm.javaClass.getDeclaredField("customProviders")
            .apply { isAccessible = true }
            .get(vm) as CustomProviderRepository

    @Test
    fun storedMcpHeaderValuesStayConcealedAndSurviveUnchangedSave() {
        dismissOnboardingIfPresent()
        val app = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<PhoneCodeApplication>()
        val vm = app.chatViewModel
        runBlocking {
            vm.saveMcpServerAndWait(
                "secret-server",
                McpServerConfig(
                    url = "http://127.0.0.1:1/mcp",
                    headers = mapOf("Authorization" to "Bearer top-secret"),
                    enabled = false,
                    timeout = 1_000,
                ),
            ).getOrThrow()
        }
        val repoField = vm.javaClass.getDeclaredField("repo").apply { isAccessible = true }
        val repo = repoField.get(vm) as McpSkillRepository

        openSettingsPage("MCP servers")
        compose.onNodeWithContentDescription("secret-server details").performClick()
        val exposedHeaders = compose.onNodeWithContentDescription("Header value 1")
            .fetchSemanticsNode().config.toString()
        assertFalse(exposedHeaders.contains("top-secret"))
        compose.onNodeWithContentDescription("Connection timeout in milliseconds")
            .performTextReplacement("2000")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("secret-server").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(repo.loadMcpConfig().mcp.getValue("secret-server").headers["Authorization"] == "Bearer top-secret")

        compose.onNodeWithContentDescription("secret-server details").performClick()
        compose.onNodeWithContentDescription("Header value 1")
            .performTextReplacement("Bearer replacement")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(5_000) {
            repo.loadMcpConfig().mcp.getValue("secret-server").headers["Authorization"] == "Bearer replacement"
        }
    }
}
