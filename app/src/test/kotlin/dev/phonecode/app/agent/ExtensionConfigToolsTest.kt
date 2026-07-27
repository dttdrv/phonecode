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
