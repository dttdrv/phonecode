package dev.phonecode.tools.mcp

import dev.phonecode.tools.skills.parseSkillMarkdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpConfigTest {

    @Test fun parsesOpenCodeStyleConfig() {
        val cfg = parseMcpConfig(
            """{"mcp":{"weather":{"type":"remote","url":"https://x/mcp","headers":{"Authorization":"Bearer k"}},
               "off":{"type":"remote","url":"https://y","enabled":false}}}""",
        )
        assertEquals(2, cfg.mcp.size)
        assertEquals("https://x/mcp", cfg.mcp.getValue("weather").url)
        assertEquals("Bearer k", cfg.mcp.getValue("weather").headers["Authorization"])
        assertTrue(cfg.mcp.getValue("weather").enabled) // default
        assertFalse(cfg.mcp.getValue("off").enabled)
    }

    @Test fun roundTripsThroughSerialize() {
        val cfg = McpConfig(mapOf("s" to McpServerConfig(url = "https://z/mcp", enabled = true)))
        assertEquals(cfg, parseMcpConfig(cfg.serialize()))
    }

    @Test fun toleratesGarbage() {
        assertEquals(0, parseMcpConfig("not json at all").mcp.size)
    }

    @Test fun skillMarkdownParsesFrontmatterAndBody() {
        val skill = parseSkillMarkdown("---\nname: pdf\ndescription: Work with PDF files\n---\n# PDF\nUse pdftk.")!!
        assertEquals("pdf", skill.name)
        assertEquals("Work with PDF files", skill.description)
        assertTrue(skill.body.contains("pdftk"))
    }

    @Test fun skillMarkdownRejectsMissingName() {
        assertNull(parseSkillMarkdown("---\ndescription: no name here\n---\nbody"))
        assertNull(parseSkillMarkdown("no frontmatter at all"))
    }
}
