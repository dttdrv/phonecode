package dev.phonecode.tools

/**
 * The interactive-question primitive shared by the `question` tool and the plan-approval flow.
 * A tool builds [UserQuestion]s, calls [ToolContext.askUser], and suspends until the user answers
 * (or the turn is cancelled, in which case answers come back empty / "unanswered").
 */
data class UserOption(val label: String, val description: String = "")

data class UserQuestion(
    val question: String,
    /** Short chip/label shown beside the question (e.g. "Auth method"); optional. */
    val header: String = "",
    /** When true the user may pick several options; otherwise exactly one. */
    val multiSelect: Boolean = false,
    /** Suggested answers; the user may always type a custom one instead. */
    val options: List<UserOption> = emptyList(),
)

/** The user's reply to one [UserQuestion]: zero answers means they declined / it was cancelled. */
data class UserAnswer(val question: String, val answers: List<String>)
