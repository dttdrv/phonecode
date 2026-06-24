package dev.phonecode.provider.http

import dev.phonecode.provider.domain.ChatMessage
import dev.phonecode.provider.domain.ChatRequest
import dev.phonecode.provider.domain.MessagePart
import dev.phonecode.provider.domain.ReasoningEffort
import dev.phonecode.provider.domain.Role
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestBodyBuildersTest {

    private val json = Json
    private fun openAi(req: ChatRequest) = json.parseToJsonElement(RequestBodyBuilders.toOpenAiBody(req)).jsonObject
    private fun anthropic(req: ChatRequest) = json.parseToJsonElement(RequestBodyBuilders.toAnthropicBody(req)).jsonObject

    private fun user(text: String) = ChatMessage(Role.USER, listOf(MessagePart.Text(text)))

    @Test fun openAiSystemBecomesFirstMessageAndStreamOptionsSet() {
        val body = openAi(ChatRequest(model = "gpt", system = "You are helpful", messages = listOf(user("hi"))))
        val messages = body["messages"]!!.jsonArray
        assertEquals("system", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("You are helpful", messages[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("user", messages[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals(true, body["stream"]!!.jsonPrimitive.boolean)
        assertEquals(true, body["stream_options"]!!.jsonObject["include_usage"]!!.jsonPrimitive.boolean)
    }

    @Test fun openAiToolCallAndResultRoundTrip() {
        val req = ChatRequest(
            model = "gpt",
            messages = listOf(
                ChatMessage(Role.ASSISTANT, listOf(MessagePart.ToolCall("c1", "get", "{\"x\":1}"))),
                ChatMessage(Role.USER, listOf(MessagePart.ToolResult("c1", "ok"))),
            ),
        )
        val messages = openAi(req)["messages"]!!.jsonArray
        val assistant = messages[0].jsonObject
        assertEquals("assistant", assistant["role"]!!.jsonPrimitive.content)
        val tc = assistant["tool_calls"]!!.jsonArray[0].jsonObject
        assertEquals("c1", tc["id"]!!.jsonPrimitive.content)
        assertEquals("get", tc["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("{\"x\":1}", tc["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content)
        val tool = messages[1].jsonObject
        assertEquals("tool", tool["role"]!!.jsonPrimitive.content)
        assertEquals("c1", tool["tool_call_id"]!!.jsonPrimitive.content)
        assertEquals("ok", tool["content"]!!.jsonPrimitive.content)
    }

    @Test fun openAiReasoningEffortDefaultOmittedNonDefaultEmitted() {
        assertNull(openAi(ChatRequest(model = "gpt", messages = listOf(user("hi"))))["reasoning_effort"])
        val high = openAi(ChatRequest(model = "gpt", messages = listOf(user("hi")), reasoningEffort = ReasoningEffort.HIGH))
        assertEquals("high", high["reasoning_effort"]!!.jsonPrimitive.content)
    }

    @Test fun openAiUsesMaxCompletionTokens() {
        val body = openAi(ChatRequest(model = "gpt", messages = listOf(user("hi")), maxTokens = 100))
        assertEquals(100, body["max_completion_tokens"]!!.jsonPrimitive.int)
        assertNull(body["max_tokens"])
    }

    @Test fun anthropicSystemTopLevelNoSamplingAndContentBlocks() {
        val body = anthropic(ChatRequest(model = "claude", system = "sys", messages = listOf(user("hi"))))
        assertEquals("sys", body["system"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertNull(body["temperature"])
        assertNull(body["top_p"])
        assertEquals(4096, body["max_tokens"]!!.jsonPrimitive.int)
        val content = body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject
        assertEquals("text", content["type"]!!.jsonPrimitive.content)
        assertEquals("hi", content["text"]!!.jsonPrimitive.content)
    }

    @Test fun anthropicToolUseAndResultBlocks() {
        val req = ChatRequest(
            model = "claude",
            messages = listOf(
                ChatMessage(Role.ASSISTANT, listOf(MessagePart.ToolCall("t1", "get", "{\"x\":1}"))),
                ChatMessage(Role.USER, listOf(MessagePart.ToolResult("t1", "ok"))),
            ),
        )
        val body = anthropic(req)
        val toolUse = body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject
        assertEquals("tool_use", toolUse["type"]!!.jsonPrimitive.content)
        assertEquals("t1", toolUse["id"]!!.jsonPrimitive.content)
        assertEquals("get", toolUse["name"]!!.jsonPrimitive.content)
        assertEquals(1, toolUse["input"]!!.jsonObject["x"]!!.jsonPrimitive.int)
        val toolResult = body["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray[0].jsonObject
        assertEquals("tool_result", toolResult["type"]!!.jsonPrimitive.content)
        assertEquals("t1", toolResult["tool_use_id"]!!.jsonPrimitive.content)
    }

    @Test fun anthropicReasoningHighEnablesThinkingAndBumpsMaxTokens() {
        val body = anthropic(ChatRequest(model = "claude", messages = listOf(user("hi")), reasoningEffort = ReasoningEffort.HIGH))
        val thinking = body["thinking"]!!.jsonObject
        assertEquals("enabled", thinking["type"]!!.jsonPrimitive.content)
        assertEquals(16000, thinking["budget_tokens"]!!.jsonPrimitive.int)
        assertEquals(16000 + 8192, body["max_tokens"]!!.jsonPrimitive.int)
    }

    @Test fun anthropicBodyMarksCacheBreakpoints() {
        val req = ChatRequest(
            model = "claude",
            system = "sys",
            messages = listOf(
                ChatMessage(Role.USER, listOf(MessagePart.Text("a"))),
                ChatMessage(Role.ASSISTANT, listOf(MessagePart.Text("b"))),
                ChatMessage(Role.USER, listOf(MessagePart.Text("c"))),
            ),
        )
        val body = anthropic(req)
        // system is a cache-controlled text block
        val sysBlock = body["system"]!!.jsonArray[0].jsonObject
        assertEquals("ephemeral", sysBlock["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        // only the last two messages carry a cache breakpoint on their final content block
        val messages = body["messages"]!!.jsonArray
        fun lastBlockCached(i: Int): Boolean =
            messages[i].jsonObject["content"]!!.jsonArray.last().jsonObject["cache_control"] != null
        assertFalse(lastBlockCached(0))
        assertTrue(lastBlockCached(1))
        assertTrue(lastBlockCached(2))
    }

    @Test fun openAiBodySetsPromptCacheKeyFromSessionId() {
        val withSession = openAi(ChatRequest(model = "gpt", messages = listOf(user("hi")), sessionId = "sess-123"))
        assertEquals("sess-123", withSession["prompt_cache_key"]!!.jsonPrimitive.content)
        assertNull(openAi(ChatRequest(model = "gpt", messages = listOf(user("hi"))))["prompt_cache_key"])
    }

    @Test fun reasoningOnlyMessageIsDroppedFromWire() {
        val req = ChatRequest(
            model = "m",
            messages = listOf(
                ChatMessage(Role.USER, listOf(MessagePart.Text("hi"))),
                ChatMessage(Role.ASSISTANT, listOf(MessagePart.Reasoning("thinking..."))), // no wire content
                ChatMessage(Role.USER, listOf(MessagePart.Text("more"))),
            ),
        )
        assertEquals(2, openAi(req)["messages"]!!.jsonArray.size)
        val anthropicMsgs = anthropic(req)["messages"]!!.jsonArray
        assertEquals(2, anthropicMsgs.size)
        assertTrue(anthropicMsgs.all { it.jsonObject["content"]!!.jsonArray.isNotEmpty() })
    }
}
