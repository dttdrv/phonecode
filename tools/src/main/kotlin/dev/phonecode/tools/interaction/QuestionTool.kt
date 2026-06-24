package dev.phonecode.tools.interaction

import dev.phonecode.tools.Tool
import dev.phonecode.tools.ToolContext
import dev.phonecode.tools.ToolResult
import dev.phonecode.tools.UserOption
import dev.phonecode.tools.UserQuestion
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Asks the user one or more questions and waits for their reply (mirrors OpenCode's `question` tool).
 * Use only when you genuinely cannot proceed without a user decision - each question may offer
 * options, but the user can always type a custom answer. Suspends the loop until answered.
 */
class QuestionTool : Tool {
    override val name = "question"
    override val description =
        "Ask the user one or more questions and wait for their answer. Use sparingly - only when you " +
            "truly need the user to choose between options or supply information you cannot determine " +
            "yourself. Offer options when you can; the user may also type a custom answer."
    override val promptSnippet =
        "ask the user a question and wait for their reply (only when you cannot proceed without their decision)"

    // Not a state mutation, but it blocks on user input, so it must never run concurrently with other tools.
    override val mutating = false
    override val sequential = true

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("questions") {
                put("type", "array")
                put("description", "The questions to ask the user, in order.")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("question") { put("type", "string"); put("description", "The question to ask.") }
                        putJsonObject("header") {
                            put("type", "string")
                            put("description", "Short label shown beside the question (optional).")
                        }
                        putJsonObject("multiSelect") {
                            put("type", "boolean")
                            put("description", "Allow selecting multiple options (default false).")
                        }
                        putJsonObject("options") {
                            put("type", "array")
                            put("description", "Suggested answers (optional); the user may also type their own.")
                            putJsonObject("items") {
                                put("type", "object")
                                putJsonObject("properties") {
                                    putJsonObject("label") { put("type", "string"); put("description", "The option text.") }
                                    putJsonObject("description") {
                                        put("type", "string")
                                        put("description", "What choosing this option means (optional).")
                                    }
                                }
                                put("required", buildJsonArray { add("label") })
                                put("additionalProperties", false)
                            }
                        }
                    }
                    put("required", buildJsonArray { add("question") })
                    put("additionalProperties", false)
                }
            }
        }
        put("required", buildJsonArray { add("questions") })
        put("additionalProperties", false)
    }

    override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult {
        val questions = parseQuestions(args)
            ?: return ToolResult("question: provide a non-empty 'questions' array", isError = true)
        val answers = context.askUser(questions)
        val body = answers.joinToString("\n") { answer ->
            val value = answer.answers.filter { it.isNotBlank() }.joinToString(", ").ifEmpty { "Unanswered" }
            "\"${answer.question}\" = \"$value\""
        }
        return ToolResult(body.ifEmpty { "Unanswered" })
    }

    private fun parseQuestions(args: JsonObject): List<UserQuestion>? {
        val array = args["questions"] as? JsonArray ?: return null
        val parsed = array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val question = (obj["question"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
            val header = (obj["header"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
            val multiSelect = (obj["multiSelect"] as? JsonPrimitive)?.booleanOrNull ?: false
            val options = (obj["options"] as? JsonArray).orEmpty().mapNotNull { opt ->
                val optObj = opt as? JsonObject ?: return@mapNotNull null
                val label = (optObj["label"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
                val desc = (optObj["description"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
                UserOption(label, desc)
            }
            UserQuestion(question, header, multiSelect, options)
        }
        return parsed.ifEmpty { null }
    }
}
