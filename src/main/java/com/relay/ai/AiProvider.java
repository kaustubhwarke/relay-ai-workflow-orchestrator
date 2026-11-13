package com.relay.ai;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * The adapter between the engine and any language model. The core never talks to a vendor SDK, so the
 * provider can be swapped (OpenRouter, Groq, Gemini, Ollama, OpenAI) or mocked in engine tests.
 */
public interface AiProvider {

    record ChatMessage(String role, String content) {
        public static ChatMessage user(String c) { return new ChatMessage("user", c); }
        public static ChatMessage assistant(String c) { return new ChatMessage("assistant", c); }
    }

    /** @param outputSchema the JSON Schema the answer must satisfy (also communicated in the system prompt) */
    record AiRequest(String system, List<ChatMessage> messages, JsonNode outputSchema, int maxTokens) {}

    record AiCompletion(String content, int promptTokens, int completionTokens, String model) {}

    /** Human-readable provider name for traces and logs (never contains credentials). */
    String name();

    /** @throws AiProviderException on transport or provider failures */
    AiCompletion complete(AiRequest request) throws AiProviderException;
}
