package dev.phonecode.app.ui

import dev.phonecode.app.ui.chat.AppendOnlyFenceParser
import dev.phonecode.app.ui.chat.AppendOnlyMarkdownParser
import dev.phonecode.app.ui.chat.parseBlocks
import dev.phonecode.app.ui.chat.splitFenced
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPerformanceRegressionTest {
    private val chat = File("src/main/kotlin/dev/phonecode/app/ui/chat/ChatScreen.kt").readText()
    private val status = File("src/main/kotlin/dev/phonecode/app/ui/chat/ChatStatus.kt").readText()
    private val turns = File("src/main/kotlin/dev/phonecode/app/ui/chat/ChatTurn.kt").readText()
    private val markdown = File("src/main/kotlin/dev/phonecode/app/ui/chat/Markdown.kt").readText()

    @Test
    fun streamingGrowthDoesNotRestartAnIndexedScrollOrFenceScanForEveryToken() {
        assertFalse(
            chat.contains(
                "state.lines.size, state.streaming.length, state.streamingReasoning.length, followOutput",
            ),
        )
        assertTrue(turns.contains("AppendOnlyFenceParser"))
    }

    @Test
    fun entryEligibilityDoesNotObserveLazyLayoutDuringComposition() {
        assertTrue(chat.contains("appendTransitions.observe("))
        assertFalse(chat.contains("layoutInfo.visibleItemsInfo"))
    }

    @Test
    fun tokenOnlyUpdatesDoNotInvalidateTheWholeStatusSurface() {
        assertFalse(status.contains("state: ChatUiState"))
        assertFalse(chat.contains("ChatStatus(\n                state = state,"))
    }

    @Test
    fun streamingMarkdownKeepsSettledBlocksInsteadOfReparsingTheWholeReply() {
        assertTrue(markdown.contains("AppendOnlyMarkdownParser"))
        assertFalse(markdown.contains("val blocks = remember(text) { parseBlocks(text) }"))
    }

    @Test
    fun chatUsesOneHazeSourceOnlyWhenAnEdgeNeedsIt() {
        assertFalse(chat.contains("phoneHazeEffect"))
        assertFalse(chat.contains("val hazeStyle = phoneHaze()"))
        assertTrue(chat.contains("StretchSyncedScrollChrome("))
        assertFalse(chat.contains(".hazeSource("))
    }

    @Test
    fun runningStateUsesOneMotionClockWithoutAFullScreenBreathingWash() {
        assertFalse(chat.contains("val breath = rememberNeuralBreath(3000)"))
        assertFalse(chat.contains("Modifier.fillMaxWidth().height(190.dp)"))
        assertFalse(chat.contains("val phase by rememberNeuralPhase(3000)"))
    }

    @Test
    fun reasoningDisclosureDoesNotAnimateListLayoutDimensions() {
        assertFalse(chat.contains("expandHorizontally("))
        assertFalse(chat.contains("shrinkHorizontally("))
        assertFalse(chat.contains("expandVertically("))
        assertFalse(chat.contains("shrinkVertically("))
    }

    @Test
    fun incrementalFenceParsingMatchesTheFullParserAtCommittedLineBoundaries() {
        val parser = AppendOnlyFenceParser()
        val chunks = listOf(
            "Intro\n",
            "\n",
            "```kotlin\n",
            "val answer = 42\n",
            "```\n",
            "Tail",
        )
        var text = ""
        chunks.forEach { chunk ->
            text += chunk
            assertEquals(splitFenced(text), parser.update(text))
        }
    }

    @Test
    fun incrementalFenceParserDoesNotRescanCommittedLinesForTailTokens() {
        val parser = AppendOnlyFenceParser()
        parser.update("Settled line\nactive")
        val settled = parser.settledCharacterCount

        parser.update("Settled line\nactive tail token")

        assertEquals(settled, parser.settledCharacterCount)
    }

    @Test
    fun incrementalMarkdownMatchesTheFullParserAndKeepsSettledBlocks() {
        val parser = AppendOnlyMarkdownParser()
        var text = "First paragraph.\n\n"
        assertEquals(parseBlocks(text), parser.update(text))
        val settled = parser.settledCharacterCount

        listOf("## Head", "ing\n", "\n", "- item", " one").forEach { token ->
            text += token
            assertEquals(parseBlocks(text), parser.update(text))
        }
        assertTrue(parser.settledCharacterCount >= settled)
    }
}
