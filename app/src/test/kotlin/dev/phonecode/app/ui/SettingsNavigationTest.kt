package dev.phonecode.app.ui

import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.createGraph
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.test.core.app.ApplicationProvider
import dev.phonecode.app.ui.settings.SettingsRoute
import dev.phonecode.app.ui.settings.fromLegacyPage
import dev.phonecode.app.ui.settings.parent
import dev.phonecode.app.ui.settings.settingsRouteGraph
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsNavigationTest {
    private val root = generateSequence(
        File(requireNotNull(System.getProperty("user.dir"))).absoluteFile,
    ) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }

    @Test
    fun typedSettingsRoutesCoverLegacyEntriesAndDeclareTheirParents() {
        val route = File(root, "app/src/main/kotlin/dev/phonecode/app/ui/settings/SettingsRoute.kt")

        assertTrue("Settings routes must be declared before navigation can be type-safe", route.isFile)
        val source = route.readText()
        listOf(
            "data object Home",
            "data object AgentTools",
            "data object Files",
            "data object Appearance",
            "data object Personalization",
            "data object Providers",
            "data object Mcp",
            "data object Skills",
            "data object Git",
            "data object Data",
            "data object About",
            "data class Provider(val id: String)",
            "data class McpServer(val id: String)",
            "data class Skill(val id: String)",
            "data object NewSkill",
            "data class EditSkill(val id: String)",
            "data class Document(val name: String)",
            "fun SettingsRoute.parent()",
            "fun SettingsRoute.Companion.fromLegacyPage",
            "\"personal\" -> SettingsRoute.Personalization",
            "\"export\" -> SettingsRoute.Data",
            "page.startsWith(\"provider:\")",
            "page.startsWith(\"doc:\")",
        ).forEach { expected ->
            assertTrue("Missing Settings route contract: $expected", source.contains(expected))
        }
    }

    @Test
    fun settingsNavigationUsesTypedDestinationsAndSharedMotion() {
        val navigation = File(root, "app/src/main/kotlin/dev/phonecode/app/ui/settings/SettingsNavigation.kt")
        val motion = File(root, "app/src/main/kotlin/dev/phonecode/app/ui/navigation/MisulNavigationMotion.kt")

        assertTrue("Settings must own a nested NavHost", navigation.isFile)
        assertTrue("Root and Settings must share one motion source", motion.isFile)
        val source = navigation.readText()
        listOf(
            "NavHost(",
            "composable<SettingsRoute.Home>",
            "composable<SettingsRoute.Provider>",
            "composable<SettingsRoute.McpServer>",
            "composable<SettingsRoute.Skill>",
            "composable<SettingsRoute.NewSkill>",
            "composable<SettingsRoute.EditSkill>",
            "composable<SettingsRoute.Document>",
            "MisulNavigationMotion.forwardEnter()",
            "MisulNavigationMotion.backExit()",
        ).forEach { expected ->
            assertTrue("Missing Settings navigation contract: $expected", source.contains(expected))
        }
    }

    @Test
    fun productionGraphIsReusableByRouteTestsAndSeedsFromTheLiveStateFlow() {
        val navigation = File(root, "app/src/main/kotlin/dev/phonecode/app/ui/settings/SettingsNavigation.kt").readText()
        val settings = File(root, "app/src/main/kotlin/dev/phonecode/app/ui/settings/SettingsScreen.kt").readText()

        assertTrue(navigation.contains("fun NavGraphBuilder.settingsRouteGraph("))
        assertTrue(navigation.contains("composable<SettingsRoute.McpServer>"))
        assertTrue(navigation.contains("composable<SettingsRoute.EditSkill>"))
        assertTrue(settings.contains("initialValue = vm.state.value.settingsSnapshot()"))
    }

    @Test
    fun legacyDeepLinksMapToTypedRoutesWithTheSameParents() {
        assertEquals(SettingsRoute.Home, SettingsRoute.fromLegacyPage("home"))
        assertEquals(SettingsRoute.Personalization, SettingsRoute.fromLegacyPage("personal"))
        assertEquals(SettingsRoute.Data, SettingsRoute.fromLegacyPage("export"))
        assertEquals(SettingsRoute.Provider("openai"), SettingsRoute.fromLegacyPage("provider:openai"))
        assertEquals(SettingsRoute.Document("privacy"), SettingsRoute.fromLegacyPage("doc:privacy"))
        assertEquals(SettingsRoute.Providers, SettingsRoute.Provider("openai").parent())
        assertEquals(SettingsRoute.Mcp, SettingsRoute.McpServer("context7").parent())
        assertEquals(SettingsRoute.Skills, SettingsRoute.NewSkill.parent())
        assertEquals(SettingsRoute.Skill("one"), SettingsRoute.EditSkill("one").parent())
        assertEquals(SettingsRoute.About, SettingsRoute.Document("terms").parent())
    }

    @Test
    fun typedNavBackStackReturnsProviderDetailToProvidersThenHome() {
        val nav = settingsController()

        nav.navigate(SettingsRoute.Providers)
        nav.navigate(SettingsRoute.Provider("openai"))
        assertTrue(nav.currentDestination!!.hasRoute<SettingsRoute.Provider>())
        assertTrue(nav.popBackStack())
        assertTrue(nav.currentDestination!!.hasRoute<SettingsRoute.Providers>())
        assertTrue(nav.popBackStack())
        assertTrue(nav.currentDestination!!.hasRoute<SettingsRoute.Home>())
    }

    @Test
    fun productionGraphKeepsSkillsAsTheCallerForEditorBackAndNewSkillSave() {
        val nav = settingsController()

        nav.navigate(SettingsRoute.Skills)
        nav.navigate(SettingsRoute.NewSkill)
        assertTrue(nav.currentDestination!!.hasRoute<SettingsRoute.NewSkill>())
        // SkillEditorDestination calls this same pop after a successful save.
        assertTrue(nav.popBackStack())
        assertTrue(nav.currentDestination!!.hasRoute<SettingsRoute.Skills>())
        nav.navigate(SettingsRoute.Skill("project/skill"))
        nav.navigate(SettingsRoute.EditSkill("project/skill"))
        assertTrue(nav.currentDestination!!.hasRoute<SettingsRoute.EditSkill>())
        assertTrue(nav.popBackStack())
        assertTrue(nav.currentDestination!!.hasRoute<SettingsRoute.Skill>())
        assertTrue(nav.popBackStack())
        assertTrue(nav.currentDestination!!.hasRoute<SettingsRoute.Skills>())
    }

    @Test
    fun skillsDeepLinkReturnsToItsCallerAfterTheEditorReturnsToSkills() {
        val nav = settingsController(SettingsRoute.Skills)

        nav.navigate(SettingsRoute.NewSkill)
        assertTrue(nav.currentDestination!!.hasRoute<SettingsRoute.NewSkill>())
        assertTrue(nav.popBackStack())
        assertTrue(nav.currentDestination!!.hasRoute<SettingsRoute.Skills>())
        assertFalse(nav.popBackStack())
    }

    private fun settingsController(startRoute: SettingsRoute = SettingsRoute.Home): NavHostController {
        val nav = NavHostController(ApplicationProvider.getApplicationContext())
        nav.navigatorProvider.addNavigator(ComposeNavigator())
        nav.graph = nav.createGraph(startDestination = startRoute) {
            settingsRouteGraph { }
        }
        return nav
    }
}
