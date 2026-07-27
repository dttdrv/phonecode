package dev.phonecode.tools.interaction

import dev.phonecode.tools.ToolContext
import dev.phonecode.tools.UserAnswer
import dev.phonecode.tools.UserQuestion
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionToolTest {

    private fun args(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject

    private class FakeContext(val responder: (List<UserQuestion>) -> List<UserAnswer>) : ToolContext {
        override val workspacePath = "/tmp"
        var received: List<UserQuestion>? = null
        override suspend fun requestPermission(tool: String, summary: String) = true
        override suspend fun askUser(questions: List<UserQuestion>): List<UserAnswer> {
            received = questions
            return responder(questions)
        }
    }

    @Test fun parsesQuestionsAndFormatsAnswers() = runBlocking {
        val ctx = FakeContext { qs -> qs.map { UserAnswer(it.question, listOf("Kotlin")) } }
        val result = QuestionTool().execute(
            args("""{"questions":[{"question":"Which language?","header":"Lang","options":[{"label":"Kotlin","description":"JVM"},{"label":"Rust"}]}]}"""),
            ctx,
        )
        assertFalse(result.isError)
        val q = ctx.received!!.single()
        assertEquals("Which language?", q.question)
        assertEquals("Lang", q.header)
        assertEquals(listOf("Kotlin", "Rust"), q.options.map { it.label })
        assertTrue(result.output.contains("\"Which language?\" = \"Kotlin\""))
    }

    @Test fun parsesMultiSelectAndJoinsAnswers() = runBlocking {
        val ctx = FakeContext { qs -> qs.map { UserAnswer(it.question, listOf("a", "b")) } }
        val result = QuestionTool().execute(
            args("""{"questions":[{"question":"Pick","multiSelect":true,"options":[{"label":"a"},{"label":"b"}]}]}"""),
            ctx,
        )
        assertTrue(ctx.received!!.single().multiSelect)
        assertTrue(result.output.contains("\"Pick\" = \"a, b\""))
    }

    @Test fun emptyAnswerRendersUnanswered() = runBlocking {
        val ctx = FakeContext { qs -> qs.map { UserAnswer(it.question, emptyList()) } }
        val result = QuestionTool().execute(args("""{"questions":[{"question":"Q?"}]}"""), ctx)
        assertTrue(result.output.contains("\"Q?\" = \"Unanswered\""))
    }

    @Test fun missingQuestionsIsError() = runBlocking {
        val result = QuestionTool().execute(args("""{}"""), FakeContext { emptyList() })
        assertTrue(result.isError)
    }

    @Test fun rejectsTooManyQuestions() = runBlocking {
        val questions = List(9) { """{"question":"Question $it"}""" }.joinToString(",")

        val result = QuestionTool().execute(
            args("""{"questions":[$questions]}"""),
            FakeContext { emptyList() },
        )

        assertTrue(result.isError)
        assertTrue(result.output.contains("at most 8 questions"))
    }

    @Test fun rejectsTooManyOptions() = runBlocking {
        val options = List(21) { """{"label":"Option $it"}""" }.joinToString(",")

        val result = QuestionTool().execute(
            args("""{"questions":[{"question":"Choose","options":[$options]}]}"""),
            FakeContext { emptyList() },
        )

        assertTrue(result.isError)
        assertTrue(result.output.contains("at most 20 options"))
    }

    @Test fun rejectsOversizedQuestionFields() = runBlocking {
        val cases = listOf(
            """{"questions":[{"question":"${"q".repeat(1001)}"}]}""" to
                "question text exceeds 1000 characters",
            """{"questions":[{"question":"Choose","header":"${"h".repeat(121)}"}]}""" to
                "header exceeds 120 characters",
            """{"questions":[{"question":"Choose","options":[{"label":"${"l".repeat(241)}"}]}]}""" to
                "option 1 label exceeds 240 characters",
            """{"questions":[{"question":"Choose","options":[{"label":"One","description":"${"d".repeat(1001)}"}]}]}""" to
                "option 1 description exceeds 1000 characters",
        )

        cases.forEach { (json, expectedMessage) ->
            val result = QuestionTool().execute(args(json), FakeContext { emptyList() })

            assertTrue("Expected rejection for $expectedMessage", result.isError)
            assertTrue(result.output, result.output.contains(expectedMessage))
        }
    }
}
