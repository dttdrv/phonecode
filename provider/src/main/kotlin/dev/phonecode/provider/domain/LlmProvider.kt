package dev.phonecode.provider.domain

import kotlinx.coroutines.flow.Flow

/**
 * A model provider that streams a normalized [StreamEvent] flow for a request.
 * Implementations own their wire format (OpenAI-compatible or Anthropic).
 * Errors are emitted as [StreamEvent.Failed]; the flow always completes.
 */
interface LlmProvider {
    fun stream(request: ChatRequest): Flow<StreamEvent>
}
