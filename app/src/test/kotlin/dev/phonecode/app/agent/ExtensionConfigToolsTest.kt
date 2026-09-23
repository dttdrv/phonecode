package dev.phonecode.app.agent

import dev.phonecode.app.data.McpSkillRepository
import dev.phonecode.tools.ToolContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ExtensionConfigToolsTest {
    private object Context : ToolContext {
        override val workspacePath = "/workspace"
        override suspend fun requestPermission(tool: String, summary: String) = true
    }

    @Test fun inventoryRedactsHeaderValues() = withTools { repository, project ->
        repository.replaceMcpConfig(
            """{"mcp":{"private":{"type":"remote","url":"https://example.com/mcp","headers":{"Authorization":"Bearer secret-value"}}}}""",
        ).getOrThrow()
        val result = runBlocking {
            ExtensionConfigReadTool(repository) { project }.execute(buildJsonObject { put("action", "inventory") }, Context)
        }

        assertFalse(result.isError)
        assertTrue(result.output.contains("Authorization"))
        assertFalse(result.output.contains("secret-value"))
    }

    @Test fun writeToolIsMutatingAndCanRepairMcpAndWriteSkill() = withTools { repository, project ->
        project.resolve("../config/opencode.json").apply {
            parentFile?.mkdirs()
            writeText("invalid")
        }
        val tool = ExtensionConfigWriteTool(repository) { project }
        val repaired = runBlocking {
            tool.execute(
                buildJsonObject {
                    put("action", "reset_mcp_config")
                },
                Context,
            )
        }
        val skill = runBlocking {
            tool.execute(
                buildJsonObject {
                    put("action", "write_skill")
                    put("scope", "project")
                    put("name", "live")
                    put("content", "---\nname: live\ndescription: Live\n---\nBody")
                },
                Context,
            )
        }

        assertTrue(tool.mutating)
        assertFalse(repaired.isError)
        assertFalse(skill.isError)
        assertTrue(repository.discoverSkills(project).any { it.name == "live" })
    }

    @Test fun writeToolRejectsMcpHeaderValues() = withTools { repository, project ->
        val result = runBlocking {
            ExtensionConfigWriteTool(repository) { project }.execute(
                buildJsonObject {
                    put("action", "upsert_mcp")
                    put("name", "private")
                    put("url", "https://example.com/mcp")
                    put("headers", buildJsonObject { put("Authorization", "Bearer secret-value") })
                },
                Context,
            )
        }

        assertTrue(result.isError)
        assertTrue(result.output.contains("Settings"))
        assertTrue(repository.loadMcpConfig().mcp.isEmpty())
    }

    @Test fun writeToolAcceptsLoopbackHttpMcp() = withTools { repository, project ->
        val result = runBlocking {
            ExtensionConfigWriteTool(repository) { project }.execute(
                buildJsonObject {
                    put("action", "upsert_mcp")
                    put("name", "local")
                    put("url", "http://127.0.0.1:8080/mcp")
                },
                Context,
            )
        }

        assertFalse(result.isError)
        assertTrue(repository.loadMcpConfig().mcp.containsKey("local"))
    }

    @Test fun writeToolCreatesNewMcpServersDisabledUntilReviewed() = withTools { repository, project ->
        val result = runBlocking {
            ExtensionConfigWriteTool(repository) { project }.execute(
                buildJsonObject {
                    put("action", "upsert_mcp")
                    put("name", "docs")
                    put("url", "https://example.com/mcp")
                },
                Context,
            )
        }

        assertFalse(result.isError)
        assertFalse(repository.loadMcpConfig().mcp.getValue("docs").enabled)
    }

    @Test fun writeToolCannotEnableOrReconfigureAnEnabledMcpWithoutReview() = withTools { repository, project ->
        repository.replaceMcpConfig(
            """{"mcp":{"docs":{"type":"remote","url":"https://old.example/mcp","enabled":true}}}""",
        ).getOrThrow()
        val tool = ExtensionConfigWriteTool(repository) { project }

        val directEnable = runBlocking {
            tool.execute(
                buildJsonObject {
                    put("action", "set_mcp_enabled")
                    put("name", "docs")
                    put("enabled", true)
                },
                Context,
            )
        }
        val changedEndpoint = runBlocking {
            tool.execute(
                buildJsonObject {
                    put("action", "upsert_mcp")
                    put("name", "docs")
                    put("url", "https://new.example/mcp")
                },
                Context,
            )
        }

        assertTrue(directEnable.isError)
        assertTrue(directEnable.output.contains("Settings"))
        assertFalse(changedEndpoint.isError)
        assertFalse(repository.loadMcpConfig().mcp.getValue("docs").enabled)
    }

    @Test fun writeToolRejectsEnabledUpsertInsteadOfBypassingToolReview() = withTools { repository, project ->
        val result = runBlocking {
            ExtensionConfigWriteTool(repository) { project }.execute(
                buildJsonObject {
                    put("action", "upsert_mcp")
                    put("name", "docs")
                    put("url", "https://example.com/mcp")
                    put("enabled", true)
                },
                Context,
            )
        }

        assertTrue(result.isError)
        assertTrue(result.output.contains("Settings"))
        assertTrue(repository.loadMcpConfig().mcp.isEmpty())
    }

    @Test fun setToolOnlyRestrictsAndKeepsConfigurationToolsEnabled() = withTools { repository, project ->
        val settings = dev.phonecode.app.data.AppSettingsStore(project.resolve("../settings.json"))
        val tool = ExtensionConfigWriteTool(repository, settings) { project }
        fun set(vararg pairs: Pair<String, Any>) = runBlocking {
            tool.execute(buildJsonObject {
                put("action", "set_tool")
                pairs.forEach { (key, value) -> if (value is Boolean) put(key, value) else put(key, value.toString()) }
            }, Context)
        }

        assertFalse(set("name" to "webfetch", "enabled" to false).isError)
        assertFalse(set("name" to "read_file", "approval" to "always").isError)
        assertTrue(set("name" to "extension_write", "enabled" to false).isError)
        assertTrue(set("name" to "bash", "approval" to "never").isError)
        assertTrue(set("name" to "bash").isError)
        assertTrue(runBlocking {
            tool.execute(buildJsonObject { put("action", "remove_tool"); put("name", "read_file") }, Context)
        }.output.contains("cannot be removed"))
        assertTrue(settings.load().disabledTools == setOf("webfetch"))
        assertTrue(settings.load().approvalTools == setOf("read_file"))

        assertFalse(set("name" to "webfetch", "enabled" to true).isError)
        assertFalse(set("name" to "read_file", "approval" to "default").isError)
        assertTrue(settings.load().disabledTools.isEmpty() && settings.load().approvalTools.isEmpty())
    }

    private fun withTools(block: (McpSkillRepository, java.io.File) -> Unit) {
        val root = Files.createTempDirectory("phonecode-extension-tools").toFile()
        try {
            val config = root.resolve("config")
            val project = root.resolve("project").apply { mkdirs() }
            block(McpSkillRepository(config), project)
        } finally {
            root.deleteRecursively()
        }
    }
}
