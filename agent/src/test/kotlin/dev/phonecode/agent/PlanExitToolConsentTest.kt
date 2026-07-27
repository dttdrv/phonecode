package dev.phonecode.agent

import dev.phonecode.tools.ToolContext
import dev.phonecode.tools.UserAnswer
import dev.phonecode.tools.UserQuestion
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanExitToolConsentTest {
    private class AnsweringContext(private val answers: List<String>) : ToolContext {
        override val workspacePath = "/workspace"
        override suspend fun requestPermission(tool: String, summary: String) = true
        override suspend fun askUser(questions: List<UserQuestion>) =
            questions.map { UserAnswer(it.question, answers) }
    }

    @Test
    fun exactStructuredYesEntersBuildMode() = runBlocking {
        var enteredBuild = false

        PlanExitTool {
            enteredBuild = true
            true
        }
            .execute(buildJsonObject {}, AnsweringContext(listOf("Yes")))

        assertTrue(enteredBuild)
    }

    @Test
    fun customConditionalOrAmbiguousTextDoesNotEnterBuildMode() = runBlocking {
        listOf(
            "Custom: Yes",
            "Yes, but do not implement yet",
            "Yesterday after tests",
            "Yesn't",
            "No",
            "No" to "Custom: Yes",
        ).forEach { answer ->
            var enteredBuild = false
            val answers = when (answer) {
                is Pair<*, *> -> listOf(answer.first.toString(), answer.second.toString())
                else -> listOf(answer.toString())
            }

            PlanExitTool {
                enteredBuild = true
                true
            }
                .execute(buildJsonObject {}, AnsweringContext(answers))

            assertFalse("Unexpected authorization from $answers", enteredBuild)
        }
    }

    @Test
    fun approvedPlanDoesNotClaimBuildAuthorityWhenPersistenceFails() = runBlocking {
        val result = PlanExitTool { false }
            .execute(buildJsonObject {}, AnsweringContext(listOf("Yes")))

        assertTrue(result.isError)
        assertTrue(result.output.contains("remains in plan mode", ignoreCase = true))
    }
}
