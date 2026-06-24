package dev.phonecode.agent

import dev.phonecode.provider.domain.ChatRequest
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
import dev.phonecode.tools.UserAnswer
import dev.phonecode.tools.UserQuestion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
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
        override suspend fun requestPermission(tool: String, summary: String): Boolean {
            permissionRequests += tool
            return allow
        }
    }

    private fun config(
        mode: AgentMode = AgentMode.BUILD,
        maxSteps: Int = 200,
        skills: List<SkillInfo> = emptyList(),
        instructions: List<String> = emptyList(),
    ) = AgentConfig(
        model = "m",
        mode = mode,
        environment = AgentEnvironment(workspacePath = "/ws"),
        maxSteps = maxSteps,
        projectInstructions = instructions,
        skills = skills,
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

    @Test fun unknownToolReportsError() = runTest {
        val provider = ScriptedProvider(listOf(toolTurn(Triple(0, "c", "nope")), finalText))
        val events = loop(provider).run(emptyList(), "x").toList()
        assertTrue(events.any { it is AgentEvent.ToolFinished && it.isError && it.output.contains("unknown tool") })
    }

    @Test fun planModeWithholdsMutatingTools() = runTest {
        val provider = ScriptedProvider(listOf(listOf(StreamEvent.TextDelta("plan"), StreamEvent.Done(StopReason.END_TURN))))
        val tools = listOf(RecordingTool("write", mutating = true), RecordingTool("read", mutating = false))
        loop(provider, tools, cfg = config(mode = AgentMode.PLAN)).run(emptyList(), "explore").toList()
        val toolNames = provider.requests[0].tools.map { it.name }
        assertTrue(toolNames.contains("read"))
        assertFalse(toolNames.contains("write"))
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

    @Test fun planExitApprovalUnlocksBuildModeMidRun() = runTest {
        // Turn 1: model calls plan_exit (approved). Turn 2: model calls the mutating write tool. Turn 3: wrap up.
        val provider = ScriptedProvider(
            listOf(
                toolTurn(Triple(0, "c1", "plan_exit")),
                toolTurn(Triple(0, "c2", "write")),
                finalText,
            ),
        )
        var mode = AgentMode.PLAN
        val write = RecordingTool("write", mutating = true)
        val planExit = PlanExitTool { mode = AgentMode.BUILD }
        val approving = object : ToolContext {
            override val workspacePath = "/ws"
            override suspend fun requestPermission(tool: String, summary: String) = true
            override suspend fun askUser(questions: List<UserQuestion>) =
                questions.map { UserAnswer(it.question, listOf("Yes")) }
        }
        val events = AgentLoop(
            provider, ToolRegistry(listOf(write, planExit)), approving, config(mode = AgentMode.PLAN),
            modeProvider = { mode },
        ).run(emptyList(), "plan then build").toList()

        assertEquals(1, write.executions) // write ran - it would have been hard-blocked if still in PLAN
        assertTrue(events.any { it is AgentEvent.ToolFinished && it.id == "c2" && !it.isError })
        // PLAN turn hid the mutating tool and showed plan_exit; BUILD turn flipped both.
        val planTurnTools = provider.requests[0].tools.map { it.name }
        val buildTurnTools = provider.requests[1].tools.map { it.name }
        assertTrue(planTurnTools.contains("plan_exit"))
        assertFalse(planTurnTools.contains("write"))
        assertTrue(buildTurnTools.contains("write"))
        assertFalse(buildTurnTools.contains("plan_exit"))
    }

    @Test fun planModeHardBlocksMutatingExecution() = runTest {
        val provider = ScriptedProvider(listOf(toolTurn(Triple(0, "c", "write")), finalText))
        val tool = RecordingTool("write", mutating = true)
        val events = loop(provider, listOf(tool), cfg = config(mode = AgentMode.PLAN)).run(emptyList(), "x").toList()
        assertEquals(0, tool.executions)
        assertTrue(events.any { it is AgentEvent.ToolFinished && it.isError && it.output.contains("PLAN") })
    }

    @Test fun providerFailureEmitsErrorAndStops() = runTest {
        val provider = ScriptedProvider(listOf(listOf(StreamEvent.Failed("boom"))))
        val events = loop(provider).run(emptyList(), "x").toList()
        assertEquals(AgentEvent.Error("boom"), events.last())
        assertFalse(events.any { it is AgentEvent.TurnComplete })
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
        loop(provider, listOf(RecordingTool()), steering = steering).run(emptyList(), "do it").toList()
        assertEquals(2, provider.requests.size)
        val secondTexts = provider.requests[1].messages.flatMap { it.parts }.filterIsInstance<MessagePart.Text>().map { it.text }
        assertTrue(secondTexts.any { it.contains("also check the config") && it.contains("<system-reminder>") })
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

    @Test fun systemPromptContainsBaseToolsInstructionsSkillsAndPlanReminder() = runTest {
        val provider = ScriptedProvider(listOf(listOf(StreamEvent.TextDelta("ok"), StreamEvent.Done(StopReason.END_TURN))))
        val cfg = config(
            mode = AgentMode.PLAN,
            skills = listOf(SkillInfo("pdf", "work with PDF files")),
            instructions = listOf("This repo uses 4-space indentation."),
        )
        loop(provider, listOf(RecordingTool("read", snippet = "read a file by path")), cfg = cfg).run(emptyList(), "x").toList()
        val system = provider.requests[0].system!!
        assertTrue(system.contains("AI coding agent operating inside PhoneCode"))
        assertTrue(system.contains("- read: read a file by path"))
        assertTrue(system.contains("Workspace: /ws"))
        assertTrue(system.contains("This repo uses 4-space indentation."))
        assertTrue(system.contains("pdf: work with PDF files"))
        assertTrue(system.contains("PLAN MODE ACTIVE"))
    }

    @Test fun doomLoopGuardStopsRepeatedIdenticalCalls() = runTest {
        val provider = LoopingProvider(toolTurn(Triple(0, "c", "echo")))
        val ctx = FakeContext(allow = false) // deny the doom-loop confirmation
        val tool = RecordingTool()
        val events = loop(provider, listOf(tool), context = ctx, cfg = config(maxSteps = 50)).run(emptyList(), "x").toList()
        assertEquals(3, provider.calls) // two batches run, doom detected on the third
        assertEquals(2, tool.executions)
        assertTrue(ctx.permissionRequests.contains("doom_loop"))
        assertTrue((events.last() as AgentEvent.Error).message.contains("repeated"))
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
