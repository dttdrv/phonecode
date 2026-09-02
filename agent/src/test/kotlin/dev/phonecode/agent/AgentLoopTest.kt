package dev.phonecode.agent

import dev.phonecode.provider.domain.ChatMessage
import dev.phonecode.provider.domain.ChatRequest
import dev.phonecode.provider.domain.FailureKind
import dev.phonecode.provider.domain.LlmProvider
import dev.phonecode.provider.domain.MessagePart
import dev.phonecode.provider.domain.ReasoningEffort
import dev.phonecode.provider.domain.Role
import dev.phonecode.provider.domain.StopReason
import dev.phonecode.provider.domain.StreamEvent
import dev.phonecode.tools.Tool
import dev.phonecode.tools.ToolContext
import dev.phonecode.tools.ToolRegistry
import dev.phonecode.tools.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLoopTest {

    private class ScriptedProvider(turns: List<List<StreamEvent>>) : LlmProvider {
        private val queue = ArrayDeque(turns)
        val requests = mutableListOf<ChatRequest>()
        override fun stream(request: ChatRequest): Flow<StreamEvent> {
            requests += request
            return (if (queue.isNotEmpty()) queue.removeFirst() else emptyList()).asFlow()
        }
    }

    private class LoopingProvider(private val turn: List<StreamEvent>) : LlmProvider {
        var calls = 0
        override fun stream(request: ChatRequest): Flow<StreamEvent> {
            calls++
            return turn.asFlow()
        }
    }

    private class RecordingTool(
        override val name: String = "echo",
        override val mutating: Boolean = false,
        private val result: ToolResult = ToolResult("ok"),
        private val snippet: String? = null,
    ) : Tool {
        override val description = "test tool"
        override val parameters = JsonObject(emptyMap())
        override val promptSnippet: String? get() = snippet
        var executions = 0
        override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult {
            executions++
            return result
        }
    }

    private class FakeContext(private val allow: Boolean = true) : ToolContext {
        override val workspacePath = "/ws"
        val permissionRequests = mutableListOf<String>()
        val permissionSummaries = mutableListOf<String>()
        override suspend fun requestPermission(tool: String, summary: String): Boolean {
            permissionRequests += tool
            permissionSummaries += summary
            return allow
        }
    }

    private fun config(
        maxSteps: Int = 200,
        instructions: List<String> = emptyList(),
    ) = AgentConfig(
        model = "m",
        environment = AgentEnvironment(workspacePath = "/ws"),
        maxSteps = maxSteps,
        projectInstructions = instructions,
    )

    private fun loop(
        provider: LlmProvider,
        tools: List<Tool> = emptyList(),
        context: ToolContext = FakeContext(),
        cfg: AgentConfig = config(),
        steering: MessageSource = MessageSource.EMPTY,
        followUp: MessageSource = MessageSource.EMPTY,
        turnSettings: (suspend () -> TurnSettings)? = null,
    ) = if (turnSettings == null) {
        AgentLoop(provider, ToolRegistry(tools), context, cfg, steering, followUp)
    } else {
        AgentLoop(provider, ToolRegistry(tools), context, cfg, steering, followUp, turnSettings)
    }

    private fun toolTurn(vararg calls: Triple<Int, String, String>): List<StreamEvent> = buildList {
        calls.forEach { (idx, id, name) -> add(StreamEvent.ToolCallStart(idx, id, name)) }
        add(StreamEvent.Done(StopReason.TOOL_USE))
    }

    private val finalText = listOf(StreamEvent.TextDelta("done"), StreamEvent.Done(StopReason.END_TURN))

    @Test fun plainTextTurnCompletes() = runTest {
        val provider = ScriptedProvider(listOf(listOf(StreamEvent.TextDelta("Hello"), StreamEvent.Done(StopReason.END_TURN))))
        val events = loop(provider).run(emptyList(), "hi").toList()
        assertTrue(events.any { it is AgentEvent.TextDelta && it.text == "Hello" })
        val complete = events.last() as AgentEvent.TurnComplete
        assertEquals(2, complete.messages.size)
        assertEquals(Role.ASSISTANT, complete.messages[1].role)
    }

    @Test fun singleToolCallThenFinalText() = runTest {
        val provider = ScriptedProvider(listOf(toolTurn(Triple(0, "c1", "echo")), finalText))
        val tool = RecordingTool()
        val events = loop(provider, listOf(tool)).run(emptyList(), "do it").toList()
        assertEquals(1, tool.executions)
        assertTrue(events.any { it is AgentEvent.ToolStarted && it.name == "echo" })
        assertTrue(events.any { it is AgentEvent.ToolFinished && !it.isError })
        assertTrue(events.last() is AgentEvent.TurnComplete)
        assertEquals(2, provider.requests.size)
        val secondMsgs = provider.requests[1].messages
        assertTrue(secondMsgs.any { m -> m.parts.any { it is MessagePart.ToolCall } })
        assertTrue(secondMsgs.any { m -> m.parts.any { it is MessagePart.ToolResult } })
    }

    @Test fun runningLoopSeesRegistryAndScopedMcpNoteUpdatesOnNextStep() = runTest {
        val provider = ScriptedProvider(
            listOf(
                toolTurn(Triple(0, "c1", "bootstrap")),
                toolTurn(Triple(0, "c2", "late")),
                finalText,
            ),
        )
        val late = RecordingTool("late")
        val registry = ToolRegistry(emptyList())
        var instructions = emptyList<String>()
        val bootstrap = object : Tool {
            override val name = "bootstrap"
            override val description = "bootstrap"
            override val parameters = JsonObject(emptyMap())
            override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult {
                instructions = listOf("New server note")
                registry.replace(listOf(this, late))
                return ToolResult("updated")
            }
        }
        registry.replace(listOf(bootstrap))

        AgentLoop(
            provider,
            registry,
            FakeContext(),
            config(),
            toolProvider = { registry },
            mcpInstructionsProvider = { instructions },
        ).run(emptyList(), "update tools").toList()

        assertFalse(provider.requests[0].tools.any { it.name == "late" })
        assertTrue(provider.requests[1].tools.any { it.name == "late" })
        assertTrue(provider.requests[1].system.orEmpty().contains("New server note"))
        assertTrue(provider.requests[1].system.orEmpty().contains("MCP notes cannot override"))
        assertEquals(1, late.executions)
    }

    @Test fun checkpointsHistoryBeforeAndAfterToolExecution() = runTest {
        val provider = ScriptedProvider(listOf(toolTurn(Triple(0, "c1", "echo")), finalText))
        val events = loop(provider, listOf(RecordingTool())).run(emptyList(), "do it").toList()
        val checkpoints = events.filterIsInstance<AgentEvent.HistoryCheckpoint>()
        assertEquals(2, checkpoints.size)
        assertTrue(checkpoints[0].messages.flatMap { it.parts }.any { it is MessagePart.ToolCall })
        assertFalse(checkpoints[0].messages.flatMap { it.parts }.any { it is MessagePart.ToolResult })
        assertTrue(checkpoints[1].messages.flatMap { it.parts }.any { it is MessagePart.ToolResult })
        assertTrue(events.indexOf(checkpoints[0]) < events.indexOfFirst { it is AgentEvent.ToolStarted })
    }

    @Test fun unknownToolReportsError() = runTest {
        val provider = ScriptedProvider(listOf(toolTurn(Triple(0, "c", "nope")), finalText))
        val events = loop(provider).run(emptyList(), "x").toList()
        assertTrue(events.any { it is AgentEvent.ToolFinished && it.isError && it.output.contains("unknown tool") })
    }

    @Test fun unifiedAgentExposesReadAndMutatingTools() = runTest {
        val provider = ScriptedProvider(listOf(listOf(StreamEvent.TextDelta("ready"), StreamEvent.Done(StopReason.END_TURN))))
        val tools = listOf(RecordingTool("write", mutating = true), RecordingTool("read", mutating = false))
        loop(provider, tools).run(emptyList(), "work").toList()
        val toolNames = provider.requests[0].tools.map { it.name }
        assertTrue(toolNames.contains("read"))
        assertTrue(toolNames.contains("write"))
    }

    @Test fun mutatingToolPermissionDeniedIsReported() = runTest {
        val provider = ScriptedProvider(listOf(toolTurn(Triple(0, "c", "write")), finalText))
        val tool = RecordingTool("write", mutating = true)
        val ctx = FakeContext(allow = false)
        val events = loop(provider, listOf(tool), context = ctx).run(emptyList(), "write file").toList()
        assertEquals(listOf("write"), ctx.permissionRequests)
        assertEquals(0, tool.executions)
        assertTrue(events.any { it is AgentEvent.ToolFinished && it.isError && it.output.contains("permission denied") })
    }

    @Test fun deniedMutatingToolNeverAppearsToStartBeforePermission() = runTest {
        val provider = ScriptedProvider(listOf(toolTurn(Triple(0, "c", "write")), finalText))
        val tool = RecordingTool("write", mutating = true)
        val events = mutableListOf<AgentEvent>()
        val context = object : ToolContext {
            override val workspacePath = "/ws"
            override suspend fun requestPermission(tool: String, summary: String): Boolean {
                assertFalse(events.any { it is AgentEvent.ToolStarted })
                return false
            }
        }

        loop(provider, listOf(tool), context = context).run(emptyList(), "write file").collect(events::add)

        assertFalse(events.any { it is AgentEvent.ToolStarted })
        assertEquals(0, tool.executions)
        assertTrue(events.any { it is AgentEvent.ToolFinished && it.isError })
    }

    @Test fun approvalRetainsTheCompleteToolArguments() = runTest {
        val middle = "middle-path-that-must-remain-visible"
        val args = """{"patch":"${"a".repeat(400)}$middle${"z".repeat(400)}"}"""
        val provider = ScriptedProvider(
            listOf(
                listOf(
                    StreamEvent.ToolCallStart(0, "c", "write"),
                    StreamEvent.ToolCallArgsDelta(0, args),
                    StreamEvent.Done(StopReason.TOOL_USE),
                ),
                finalText,
            ),
        )
        val context = FakeContext()

        loop(provider, listOf(RecordingTool("write", mutating = true)), context = context)
            .run(emptyList(), "apply it")
            .toList()

        assertTrue(context.permissionSummaries.single().contains(middle))
        assertTrue(context.permissionSummaries.single().contains(args))
    }

    @Test fun providerFailureEmitsErrorAndStops() = runTest {
        val provider = ScriptedProvider(listOf(listOf(StreamEvent.Failed("boom"))))
        val events = loop(provider).run(emptyList(), "x").toList()
        val error = events.last() as AgentEvent.Error
        assertEquals("boom", error.message)
        // A failed turn preserves the conversation (here just the user's message) so context survives a drop.
        assertEquals(listOf(ChatMessage(Role.USER, listOf(MessagePart.Text("x")))), error.messages)
        assertFalse(events.any { it is AgentEvent.TurnComplete })
    }

    @Test fun retryableFailureBeforeOutputRetries() = runTest {
        val provider = ScriptedProvider(
            listOf(
                listOf(StreamEvent.Failed("temporarily offline", retryable = true)),
                finalText,
            ),
        )

        val events = loop(provider).run(emptyList(), "x").toList()

        assertEquals(2, provider.requests.size)
        assertTrue(events.any { it is AgentEvent.Retrying && it.attempt == 1 })
        assertTrue(events.last() is AgentEvent.TurnComplete)
    }

    @Test fun retryableFailureAfterOutputDoesNotReplay() = runTest {
        val provider = ScriptedProvider(
            listOf(listOf(StreamEvent.TextDelta("partial"), StreamEvent.Failed("disconnected", retryable = true))),
        )

        val events = loop(provider).run(emptyList(), "x").toList()

        assertEquals(1, provider.requests.size)
        assertFalse(events.any { it is AgentEvent.Retrying })
        val error = events.last() as AgentEvent.Error
        assertEquals("partial", error.messages.last().parts.filterIsInstance<MessagePart.Text>().single().text)
    }

    @Test fun toolCancellationPropagatesAndIsNotSwallowed() = runTest {
        val provider = ScriptedProvider(listOf(toolTurn(Triple(0, "c", "boom"))))
        val cancelling = object : Tool {
            override val name = "boom"
            override val description = "cancels"
            override val parameters = JsonObject(emptyMap())
            override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult =
                throw CancellationException("turn aborted")
        }
        val collected = mutableListOf<AgentEvent>()
        var cancelled = false
        try {
            loop(provider, listOf(cancelling)).run(emptyList(), "x").collect { collected += it }
        } catch (e: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertFalse(collected.any { it is AgentEvent.ToolFinished })
    }

    @Test fun blankToolIdIsBackfilledConsistently() = runTest {
        val provider = ScriptedProvider(listOf(toolTurn(Triple(0, "", "echo")), finalText))
        loop(provider, listOf(RecordingTool())).run(emptyList(), "x").toList()
        val parts = provider.requests[1].messages.flatMap { it.parts }
        val toolCall = parts.filterIsInstance<MessagePart.ToolCall>().single()
        val toolResult = parts.filterIsInstance<MessagePart.ToolResult>().single()
        assertTrue(toolCall.id.isNotBlank())
        assertEquals(toolCall.id, toolResult.callId)
    }

    @Test fun maxStepsCapStopsGracefully() = runTest {
        val provider = LoopingProvider(toolTurn(Triple(0, "c", "echo")))
        val events = loop(provider, listOf(RecordingTool()), cfg = config(maxSteps = 2)).run(emptyList(), "loop").toList()
        assertEquals(2, provider.calls) // exactly maxSteps turns, no runaway
        assertTrue(events.last() is AgentEvent.TurnComplete)
        assertFalse(events.any { it is AgentEvent.Error })
    }

    @Test fun oversizedToolDefinitionsAreRejectedBeforeTheProviderRequest() = runTest {
        val provider = ScriptedProvider(listOf(finalText))
        val hugeTool = object : Tool {
            override val name = "huge"
            override val description = "huge schema"
            override val parameters = JsonObject(mapOf("payload" to JsonPrimitive("x".repeat(200_000))))
            override suspend fun execute(args: JsonObject, context: ToolContext) = ToolResult("unused")
        }

        val events = loop(
            provider,
            tools = listOf(hugeTool),
            turnSettings = { TurnSettings("small", ReasoningEffort.DEFAULT, contextLimit = 32_000) },
        ).run(emptyList(), "hello").toList()

        assertTrue(provider.requests.isEmpty())
        assertTrue((events.last() as AgentEvent.Error).message.contains("tool definitions"))
    }

    @Test fun streamedTextReasoningAndToolArgumentsAreBoundedAndCancelTheProvider() = runTest {
        val chunk = "x".repeat(500_000)
        val events = listOf<(String) -> StreamEvent>(
            { StreamEvent.TextDelta(it) },
            { StreamEvent.ReasoningDelta(it) },
            { StreamEvent.ToolCallArgsDelta(0, it) },
        )

        events.forEach { event ->
            var cancelled = false
            val provider = object : LlmProvider {
                override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
                    try {
                        while (true) emit(event(chunk))
                    } finally {
                        cancelled = true
                    }
                }
            }

            val result = loop(provider).run(emptyList(), "x").toList()
            val error = result.last() as AgentEvent.Error

            assertTrue(cancelled)
            assertEquals(FailureKind.INVALID_REQUEST, error.kind)
            assertTrue(error.message.contains("stream safety limit"))
            assertFalse(result.any { it is AgentEvent.TurnComplete })
        }
    }

    @Test fun parallelNonMutatingToolsBothExecute() = runTest {
        val provider = ScriptedProvider(listOf(toolTurn(Triple(0, "c0", "a"), Triple(1, "c1", "b")), finalText))
        val a = RecordingTool("a")
        val b = RecordingTool("b")
        val events = loop(provider, listOf(a, b)).run(emptyList(), "x").toList()
        assertEquals(1, a.executions)
        assertEquals(1, b.executions)
        val finished = events.filterIsInstance<AgentEvent.ToolFinished>().map { it.id }
        assertEquals(listOf("c0", "c1"), finished)
    }

    @Test fun steeringMessageDeliveredAfterToolBatch() = runTest {
        val provider = ScriptedProvider(listOf(toolTurn(Triple(0, "c", "echo")), finalText))
        var delivered = false
        val steering = MessageSource { if (!delivered) { delivered = true; listOf("also check the config") } else emptyList() }
        val events = loop(provider, listOf(RecordingTool()), steering = steering).run(emptyList(), "do it").toList()
        assertEquals(2, provider.requests.size)
        // The steering message is folded in as a plain user turn (no system-reminder wrapper), so the
        // persisted history and the on-screen timeline match exactly.
        val secondTexts = provider.requests[1].messages.flatMap { it.parts }.filterIsInstance<MessagePart.Text>().map { it.text }
        assertTrue(secondTexts.any { it == "also check the config" })
        // ...and it is surfaced as a UserMessage event so the UI can place it in the timeline.
        assertTrue(events.filterIsInstance<AgentEvent.UserMessage>().any { it.text == "also check the config" })
    }

    @Test fun followUpMessageContinuesAfterIdle() = runTest {
        val provider = ScriptedProvider(
            listOf(
                listOf(StreamEvent.TextDelta("first"), StreamEvent.Done(StopReason.END_TURN)),
                listOf(StreamEvent.TextDelta("second"), StreamEvent.Done(StopReason.END_TURN)),
            ),
        )
        var delivered = false
        val followUp = MessageSource { if (!delivered) { delivered = true; listOf("now do Y") } else emptyList() }
        loop(provider, followUp = followUp).run(emptyList(), "first task").toList()
        assertEquals(2, provider.requests.size)
        val secondTexts = provider.requests[1].messages.flatMap { it.parts }.filterIsInstance<MessagePart.Text>().map { it.text }
        assertTrue(secondTexts.any { it == "now do Y" }) // follow-up delivered as a plain user message, not wrapped
    }

    @Test fun modelSwitchesPerTurn() = runTest {
        val provider = ScriptedProvider(listOf(toolTurn(Triple(0, "c", "echo")), finalText))
        var turn = 0
        val settings: suspend () -> TurnSettings = {
            TurnSettings(if (turn++ == 0) "model-A" else "model-B", ReasoningEffort.DEFAULT)
        }
        loop(provider, listOf(RecordingTool()), turnSettings = settings).run(emptyList(), "x").toList()
        assertEquals("model-A", provider.requests[0].model)
        assertEquals("model-B", provider.requests[1].model)
    }

    @Test fun systemPromptContainsBaseToolsInstructionsWithoutModeReminder() = runTest {
        val provider = ScriptedProvider(listOf(listOf(StreamEvent.TextDelta("ok"), StreamEvent.Done(StopReason.END_TURN))))
        val cfg = config(instructions = listOf("This repo uses 4-space indentation."))
        loop(provider, listOf(RecordingTool("read", snippet = "read a file by path")), cfg = cfg).run(emptyList(), "x").toList()
        val system = provider.requests[0].system!!
        assertTrue(system.contains("AI coding agent operating inside PhoneCode"))
        assertTrue(system.contains("- read: read a file by path"))
        assertTrue(system.contains("Workspace: /ws"))
        assertTrue(system.contains("This repo uses 4-space indentation."))
        assertFalse(system.contains("PLAN MODE ACTIVE"))
    }

    @Test fun doomLoopGuardStopsRepeatedIdenticalCalls() = runTest {
        val provider = LoopingProvider(toolTurn(Triple(0, "c", "echo")))
        val ctx = FakeContext(allow = false) // deny the doom-loop confirmation
        val tool = RecordingTool()
        val events = loop(provider, listOf(tool), context = ctx, cfg = config(maxSteps = 50)).run(emptyList(), "x").toList()
        assertEquals(3, provider.calls) // two batches run, doom detected on the third
        assertEquals(2, tool.executions)
        assertTrue(ctx.permissionRequests.contains("doom_loop"))
        val error = events.last() as AgentEvent.Error
        assertTrue(error.message.contains("repeated"))
        val calls = error.messages.flatMap { it.parts }.filterIsInstance<MessagePart.ToolCall>()
        val results = error.messages.flatMap { it.parts }.filterIsInstance<MessagePart.ToolResult>()
        assertEquals(calls.map { it.id }, results.map { it.callId })
    }

    @Test fun steeringResetsTheDoomLoopGuard() = runTest {
        // Identical tool calls, but the user steers every turn - that is not a doom loop.
        val provider = LoopingProvider(toolTurn(Triple(0, "c", "echo")))
        val steering = MessageSource { listOf("keep going") }
        val ctx = FakeContext(allow = false) // would deny a doom-loop prompt if one fired
        val events = loop(provider, listOf(RecordingTool()), context = ctx, cfg = config(maxSteps = 5), steering = steering)
            .run(emptyList(), "x").toList()
        assertFalse(events.any { it is AgentEvent.Error }) // never trips the guard
        assertFalse(ctx.permissionRequests.contains("doom_loop"))
        assertTrue(events.last() is AgentEvent.TurnComplete) // bounded only by maxSteps
        assertEquals(5, provider.calls)
    }
}
