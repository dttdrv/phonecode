package dev.phonecode.app.ui

import dev.phonecode.app.ui.chat.questionAnswered
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionFlowTest {
    @Test
    fun requiresAChoiceOrCustomAnswerBeforeContinuing() {
        assertFalse(questionAnswered(emptyList(), ""))
        assertFalse(questionAnswered(emptyList(), "   "))
        assertTrue(questionAnswered(listOf("Internal testing"), ""))
        assertTrue(questionAnswered(emptyList(), "A private beta"))
    }
}
