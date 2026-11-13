package com.relay.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relay.config.RelayProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

/**
 * Provider for any OpenAI-compatible {@code /chat/completions} endpoint (OpenRouter, Groq, Gemini's OpenAI
 * compatibility layer, Ollama, OpenAI). Every call has a timeout. Credentials are only ever sent in the
 * Authorization header and never appear in exceptions or logs.
 */
public class OpenAiCompatibleProvider implements AiProvider {

    private final ObjectMapper mapper;
    private final HttpClient client;
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final Duration timeout;
    private final boolean jsonMode;

    public OpenAiCompatibleProvider(RelayProperties.Ai cfg, ObjectMapper mapper) {
        if (cfg.getApiKey() == null || cfg.getApiKey().isBlank()) {
            throw new IllegalStateException(
                    "relay.ai.provider=openai requires an API key (set OPENAI_API_KEY) or use relay.ai.provider=heuristic");
        }
        this.mapper = mapper;
        this.apiKey = cfg.getApiKey();
        this.model = cfg.getModel();
        this.timeout = Duration.ofMillis(cfg.getTimeoutMs());
        this.jsonMode = cfg.isJsonMode();
        String base = cfg.getBaseUrl().endsWith("/") ? cfg.getBaseUrl().substring(0, cfg.getBaseUrl().length() - 1) : cfg.getBaseUrl();
        this.endpoint = URI.create(base + "/chat/completions");
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public String name() { return "openai-compatible:" + model; }

    @Override
    public AiCompletion complete(AiRequest request) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("temperature", 0);
        body.put("max_tokens", request.maxTokens());
        if (jsonMode) body.putObject("response_format").put("type", "json_object");
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", request.system());
        for (ChatMessage m : request.messages()) {
            messages.addObject().put("role", m.role()).put("content", m.content());
        }

        HttpRequest http;
        try {
            http = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
        } catch (IOException e) {
            throw new AiProviderException("ai_provider_error", "Could not build the model request", false, e);
        }

        HttpResponse<String> resp;
        try {
            resp = client.send(http, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new AiProviderException("ai_provider_timeout", "Model provider timed out after " + timeout.toSeconds() + "s", true, e);
        } catch (IOException e) {
            throw new AiProviderException("ai_provider_unavailable", "Model provider unreachable: " + e.getClass().getSimpleName(), true, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiProviderException("ai_provider_interrupted", "Interrupted while calling the model provider", true, e);
        }

        int status = resp.statusCode();
        if (status == 401 || status == 403) {
            throw new AiProviderException("ai_provider_auth", "Model provider rejected the configured credentials (HTTP " + status + ")", false);
        }
        if (status == 408 || status == 429 || status >= 500) {
            throw new AiProviderException("ai_provider_unavailable", "Model provider returned HTTP " + status, true);
        }
        if (status < 200 || status >= 300) {
            throw new AiProviderException("ai_provider_rejected", "Model provider rejected the request (HTTP " + status + ")", false);
        }
        try {
            JsonNode root = mapper.readTree(resp.body());
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            JsonNode usage = root.path("usage");
            return new AiCompletion(content, usage.path("prompt_tokens").asInt(0), usage.path("completion_tokens").asInt(0),
                    root.path("model").asText(model));
        } catch (IOException e) {
            throw new AiProviderException("ai_provider_error", "Model provider returned an unreadable response", false, e);
        }
    }
}
