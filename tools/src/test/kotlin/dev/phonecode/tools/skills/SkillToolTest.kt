package dev.phonecode.tools.skills

import dev.phonecode.tools.ToolContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillToolTest {

    private object Ctx : ToolContext {
        override val workspacePath = "/tmp"
        override suspend fun requestPermission(tool: String, summary: String) = true
    }

    private val skills = listOf(SkillManifest("pdf", "Work with PDFs", "# PDF\nUse pdftk."))

    @Test fun descriptionListsNameAndDescription() {
        val description = SkillTool(skills).description
        assertTrue(description.contains("pdf"))
        assertTrue(description.contains("Work with PDFs"))
    }

    @Test fun loadsSkillBodyWrappedInSkillContent() = runBlocking {
        val result = SkillTool(skills).execute(buildJsonObject { put("name", "pdf") }, Ctx)
        assertFalse(result.isError)
        assertTrue(result.output.contains("<skill_content name=\"pdf\">"))
        assertTrue(result.output.contains("pdftk"))
    }

    @Test fun unknownSkillIsError() = runBlocking {
        val result = SkillTool(skills).execute(buildJsonObject { put("name", "nope") }, Ctx)
        assertTrue(result.isError)
    }

    @Test fun missingNameIsError() = runBlocking {
        val result = SkillTool(skills).execute(JsonObject(emptyMap()), Ctx)
        assertTrue(result.isError)
    }
}
