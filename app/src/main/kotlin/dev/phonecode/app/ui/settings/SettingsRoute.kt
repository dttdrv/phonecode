package dev.phonecode.app.ui.settings

import kotlinx.serialization.Serializable

@Serializable
sealed interface SettingsRoute {
    companion object

    @Serializable data object Home : SettingsRoute
    @Serializable data object AgentTools : SettingsRoute
    @Serializable data object Files : SettingsRoute
    @Serializable data object Appearance : SettingsRoute
    @Serializable data object Personalization : SettingsRoute
    @Serializable data object Providers : SettingsRoute
    @Serializable data object Mcp : SettingsRoute
    @Serializable data object Skills : SettingsRoute
    @Serializable data object Git : SettingsRoute
    @Serializable data object Data : SettingsRoute
    @Serializable data object About : SettingsRoute

    /** IDs identify persisted resources only. Draft content and credentials never enter a route. */
    @Serializable data class Provider(val id: String) : SettingsRoute
    @Serializable data class McpServer(val id: String) : SettingsRoute
    @Serializable data class Skill(val id: String) : SettingsRoute
    @Serializable data object NewSkill : SettingsRoute
    @Serializable data class EditSkill(val id: String) : SettingsRoute
    @Serializable data class Document(val name: String) : SettingsRoute
}

internal fun SettingsRoute.parent(): SettingsRoute? = when (this) {
    SettingsRoute.Home -> null
    SettingsRoute.AgentTools,
    SettingsRoute.Files,
    SettingsRoute.Appearance,
    SettingsRoute.Personalization,
    SettingsRoute.Providers,
    SettingsRoute.Mcp,
    SettingsRoute.Skills,
    SettingsRoute.Git,
    SettingsRoute.Data,
    SettingsRoute.About -> SettingsRoute.Home
    is SettingsRoute.Provider -> SettingsRoute.Providers
    is SettingsRoute.McpServer -> SettingsRoute.Mcp
    is SettingsRoute.Skill -> SettingsRoute.Skills
    SettingsRoute.NewSkill -> SettingsRoute.Skills
    is SettingsRoute.EditSkill -> SettingsRoute.Skill(id)
    is SettingsRoute.Document -> SettingsRoute.About
}

/**
 * Transitional boundary for root string destinations and existing Settings deep links.
 * The NavHost never receives these strings; dynamic IDs stay in typed route values.
 */
internal fun SettingsRoute.Companion.fromLegacyPage(page: String): SettingsRoute = when {
    page.startsWith("provider:") -> SettingsRoute.Provider(page.removePrefix("provider:"))
    page.startsWith("doc:") -> SettingsRoute.Document(page.removePrefix("doc:"))
    else -> when (page) {
        "home" -> SettingsRoute.Home
        "tools" -> SettingsRoute.AgentTools
        "files" -> SettingsRoute.Files
        "appearance" -> SettingsRoute.Appearance
        "personal" -> SettingsRoute.Personalization
        "providers" -> SettingsRoute.Providers
        "mcp" -> SettingsRoute.Mcp
        "skills" -> SettingsRoute.Skills
        "git" -> SettingsRoute.Git
        "export" -> SettingsRoute.Data
        "about" -> SettingsRoute.About
        else -> SettingsRoute.Home
    }
}
