package dev.phonecode.tools.todo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TodoStatus(val wire: String) {
    PENDING("pending"),
    IN_PROGRESS("in_progress"),
    COMPLETED("completed"),
    CANCELLED("cancelled");

    companion object {
        fun from(value: String?): TodoStatus =
            entries.firstOrNull { it.wire.equals(value, ignoreCase = true) } ?: PENDING
    }
}

enum class TodoPriority(val wire: String) {
    HIGH("high"), MEDIUM("medium"), LOW("low");

    companion object {
        fun from(value: String?): TodoPriority =
            entries.firstOrNull { it.wire.equals(value, ignoreCase = true) } ?: MEDIUM
    }
}

data class TodoItem(val id: String, val content: String, val status: TodoStatus, val priority: TodoPriority)

/**
 * Per-session todo list, shared by the `todowrite` and `todoread` tools so writes on one turn are
 * visible to reads on the next. Exposed as a [StateFlow] so the UI can render the same list the model edits.
 */
class TodoStore {
    private val _items = MutableStateFlow<List<TodoItem>>(emptyList())
    val items: StateFlow<List<TodoItem>> = _items.asStateFlow()

    fun replace(newItems: List<TodoItem>) { _items.value = newItems }
    fun snapshot(): List<TodoItem> = _items.value
}
