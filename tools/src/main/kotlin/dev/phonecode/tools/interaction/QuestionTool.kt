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
                put("maxItems", MAX_QUESTIONS)
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("question") {
                            put("type", "string")
                            put("maxLength", MAX_QUESTION_CHARS)
                            put("description", "The question to ask.")
                        }
                        putJsonObject("header") {
                            put("type", "string")
                            put("maxLength", MAX_HEADER_CHARS)
                            put("description", "Short label shown beside the question (optional).")
                        }
                        putJsonObject("multiSelect") {
                            put("type", "boolean")
                            put("description", "Allow selecting multiple options (default false).")
                        }
                        putJsonObject("options") {
                            put("type", "array")
                            put("maxItems", MAX_OPTIONS_PER_QUESTION)
                            put("description", "Suggested answers (optional); the user may also type their own.")
                            putJsonObject("items") {
                                put("type", "object")
                                putJsonObject("properties") {
                                    putJsonObject("label") {
                                        put("type", "string")
                                        put("maxLength", MAX_OPTION_LABEL_CHARS)
                                        put("description", "The option text.")
                                    }
                                    putJsonObject("description") {
                                        put("type", "string")
                                        put("maxLength", MAX_OPTION_DESCRIPTION_CHARS)
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
        val questions = try {
            parseQuestions(args)
        } catch (invalid: InvalidQuestionInput) {
            return ToolResult("question: ${invalid.message}", isError = true)
        }
        val answers = context.askUser(questions)
        val body = answers.joinToString("\n") { answer ->
            val value = answer.answers.filter { it.isNotBlank() }.joinToString(", ").ifEmpty { "Unanswered" }
            "\"${answer.question}\" = \"$value\""
        }
        return ToolResult(body.ifEmpty { "Unanswered" })
    }

    private fun parseQuestions(args: JsonObject): List<UserQuestion> {
        val array = args["questions"] as? JsonArray
            ?: throw InvalidQuestionInput("provide a non-empty 'questions' array")
        if (array.isEmpty()) throw InvalidQuestionInput("provide a non-empty 'questions' array")
        if (array.size > MAX_QUESTIONS) {
            throw InvalidQuestionInput("provide at most $MAX_QUESTIONS questions")
        }
        return array.mapIndexed { questionIndex, element ->
            val number = questionIndex + 1
            val obj = element as? JsonObject
                ?: throw InvalidQuestionInput("question $number must be an object")
            val question = (obj["question"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw InvalidQuestionInput("question $number text is required")
            if (question.length > MAX_QUESTION_CHARS) {
                throw InvalidQuestionInput(
                    "question text exceeds $MAX_QUESTION_CHARS characters (question $number)",
                )
            }
            val header = (obj["header"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
            if (header.length > MAX_HEADER_CHARS) {
                throw InvalidQuestionInput("question $number header exceeds $MAX_HEADER_CHARS characters")
            }
            val multiSelect = (obj["multiSelect"] as? JsonPrimitive)?.booleanOrNull ?: false
            val optionArray = (obj["options"] as? JsonArray).orEmpty()
            if (optionArray.size > MAX_OPTIONS_PER_QUESTION) {
                throw InvalidQuestionInput(
                    "question $number must provide at most $MAX_OPTIONS_PER_QUESTION options",
                )
            }
            val options = optionArray.mapIndexed { optionIndex, opt ->
                val optionNumber = optionIndex + 1
                val optObj = opt as? JsonObject
                    ?: throw InvalidQuestionInput("question $number option $optionNumber must be an object")
                val label = (optObj["label"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: throw InvalidQuestionInput("question $number option $optionNumber label is required")
                if (label.length > MAX_OPTION_LABEL_CHARS) {
                    throw InvalidQuestionInput(
                        "question $number option $optionNumber label exceeds $MAX_OPTION_LABEL_CHARS characters",
                    )
                }
                val desc = (optObj["description"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
                if (desc.length > MAX_OPTION_DESCRIPTION_CHARS) {
                    throw InvalidQuestionInput(
                        "question $number option $optionNumber description exceeds " +
                            "$MAX_OPTION_DESCRIPTION_CHARS characters",
                    )
                }
                UserOption(label, desc)
            }
            UserQuestion(question, header, multiSelect, options)
        }
    }

    private class InvalidQuestionInput(message: String) : IllegalArgumentException(message)

    private companion object {
        const val MAX_QUESTIONS = 8
        const val MAX_OPTIONS_PER_QUESTION = 20
        const val MAX_QUESTION_CHARS = 1_000
        const val MAX_HEADER_CHARS = 120
        const val MAX_OPTION_LABEL_CHARS = 240
        const val MAX_OPTION_DESCRIPTION_CHARS = 1_000
    }
}
