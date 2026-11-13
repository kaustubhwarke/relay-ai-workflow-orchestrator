package com.relay.ai;

import static com.relay.TestSupport.MAPPER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.relay.config.RelayProperties;
import com.relay.support.StubServer;
import com.relay.support.StubServer.Reply;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The OpenAI-compatible adapter against a local stub: success shapes, every failure class, and credential hygiene. */
class OpenAiCompatibleProviderTest {

    private StubServer server;

    @BeforeEach
    void start() throws Exception {
        server = new StubServer();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private OpenAiCompatibleProvider provider(String baseUrl, int timeoutMs, boolean jsonMode) {
        RelayProperties.Ai cfg = new RelayProperties.Ai();
        cfg.setApiKey("sk-test-key-123456");
        cfg.setBaseUrl(baseUrl);
        cfg.setModel("m1");
        cfg.setTimeoutMs(timeoutMs);
        cfg.setJsonMode(jsonMode);
        return new OpenAiCompatibleProvider(cfg, MAPPER);
    }

    private AiProvider.AiRequest request() {
        return new AiProvider.AiRequest("system", List.of(AiProvider.ChatMessage.user("hello")), null, 100);
    }

    private static String completion(String contentJson) {
        return "{\"model\":\"m1-served\",\"choices\":[{\"message\":{\"content\":" + contentJson + "}}],"
                + "\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":7}}";
    }

    @Test
    void returnsTheContentAndUsageAndSendsTheCredentialOnlyInTheAuthorizationHeader() {
        server.respond(s -> Reply.ok(completion("\"{\\\"a\\\":1}\"")));
        AiProvider.AiCompletion c = provider(server.url() + "/", 2000, true).complete(request());
        assertThat(c.content()).isEqualTo("{\"a\":1}");
        assertThat(c.promptTokens()).isEqualTo(11);
        assertThat(c.completionTokens()).isEqualTo(7);
        assertThat(c.model()).isEqualTo("m1-served");

        StubServer.Seen seen = server.requests().get(0);
        assertThat(seen.path()).isEqualTo("/chat/completions");
        assertThat(seen.headers().getFirst("Authorization")).isEqualTo("Bearer sk-test-key-123456");
        assertThat(seen.body()).doesNotContain("sk-test-key").contains("\"response_format\"").contains("\"temperature\":0");
    }

    @Test
    void jsonModeCanBeSwitchedOff() {
        server.respond(s -> Reply.ok(completion("\"{}\"")));
        provider(server.url(), 2000, false).complete(request());
        assertThat(server.requests().get(0).body()).doesNotContain("response_format");
        assertThat(provider(server.url(), 2000, false).name()).isEqualTo("openai-compatible:m1");
    }

    @Test
    void contentGivenAsAnArrayOfTextPartsIsJoined() {
        server.respond(s -> Reply.ok(completion("[{\"type\":\"text\",\"text\":\"{\\\"a\\\":\"},\"1}\"]")));
        assertThat(provider(server.url(), 2000, true).complete(request()).content()).isEqualTo("{\"a\":1}");
    }

    @Test
    void anUnusableResponseIsAClearRetryableProviderError() {
        for (String body : List.of("{\"choices\":[]}", "{}", completion("null"), completion("\"   \""), completion("{\"x\":1}"))) {
            server.respond(s -> Reply.ok(body));
            assertThatThrownBy(() -> provider(server.url(), 2000, true).complete(request()))
                    .isInstanceOfSatisfying(AiProviderException.class, e -> {
                        assertThat(e.code()).isEqualTo("ai_provider_empty_response");
                        assertThat(e.isRetryable()).isTrue();
                    });
        }
    }

    @Test
    void anUnreadableBodyIsAPermanentProviderError() {
        server.respond(s -> Reply.ok("<html>not json</html>"));
        assertThatThrownBy(() -> provider(server.url(), 2000, true).complete(request()))
                .isInstanceOfSatisfying(AiProviderException.class, e -> {
                    assertThat(e.code()).isEqualTo("ai_provider_error");
                    assertThat(e.isRetryable()).isFalse();
                });
    }

    @Test
    void httpStatusesAreClassifiedIntoRetryableAndPermanentFailures() {
        record Case(int status, String code, boolean retryable) {}
        for (Case c : List.of(new Case(401, "ai_provider_auth", false), new Case(403, "ai_provider_auth", false),
                new Case(408, "ai_provider_unavailable", true), new Case(429, "ai_provider_unavailable", true),
                new Case(503, "ai_provider_unavailable", true), new Case(400, "ai_provider_rejected", false),
                new Case(302, "ai_provider_rejected", false))) {
            server.respond(s -> Reply.status(c.status(), "{\"error\":\"details with sk-secret-value\"}"));
            assertThatThrownBy(() -> provider(server.url(), 2000, true).complete(request()))
                    .isInstanceOfSatisfying(AiProviderException.class, e -> {
                        assertThat(e.code()).as("HTTP %d", c.status()).isEqualTo(c.code());
                        assertThat(e.isRetryable()).isEqualTo(c.retryable());
                        assertThat(e.getMessage()).doesNotContain("sk-secret-value").doesNotContain("sk-test-key");
                    });
        }
    }

    @Test
    void aSlowProviderTimesOutAsARetryableError() {
        server.respond(s -> Reply.slow(1500));
        assertThatThrownBy(() -> provider(server.url(), 300, true).complete(request()))
                .isInstanceOfSatisfying(AiProviderException.class, e -> {
                    assertThat(e.code()).isEqualTo("ai_provider_timeout");
                    assertThat(e.isRetryable()).isTrue();
                });
    }

    @Test
    void anUnreachableProviderIsARetryableError() {
        String dead = server.url();
        server.close();
        assertThatThrownBy(() -> provider(dead, 500, true).complete(request()))
                .isInstanceOfSatisfying(AiProviderException.class, e -> {
                    assertThat(e.code()).isEqualTo("ai_provider_unavailable");
                    assertThat(e.isRetryable()).isTrue();
                });
    }

    @Test
    void aMissingApiKeyFailsFastAtConstruction() {
        RelayProperties.Ai cfg = new RelayProperties.Ai();
        cfg.setApiKey(" ");
        assertThatThrownBy(() -> new OpenAiCompatibleProvider(cfg, MAPPER)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("API key");
    }

    @Test
    void anInterruptedCallRestoresTheInterruptFlagAndIsRetryable() {
        server.respond(s -> Reply.slow(1500));
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> provider(server.url(), 5000, true).complete(request()))
                    .isInstanceOfSatisfying(AiProviderException.class, e -> assertThat(e.code()).isEqualTo("ai_provider_interrupted"));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted(); // clear the flag for other tests
        }
    }

    @Test
    void theConfigurationSelectsTheProviderAndRejectsUnknownOnes() {
        com.relay.ai.AiProviderConfig config = new com.relay.ai.AiProviderConfig();
        RelayProperties p = new RelayProperties();
        p.getAi().setProvider(" HEURISTIC ");
        assertThat(config.aiProvider(p, MAPPER)).isInstanceOf(HeuristicAiProvider.class);
        p.getAi().setProvider("OpenAI");
        p.getAi().setApiKey("k");
        assertThat(config.aiProvider(p, MAPPER)).isInstanceOf(OpenAiCompatibleProvider.class);
        p.getAi().setProvider("nope");
        assertThatThrownBy(() -> config.aiProvider(p, MAPPER)).isInstanceOf(IllegalStateException.class).hasMessageContaining("nope");
        p.getAi().setProvider(null);
        assertThatThrownBy(() -> config.aiProvider(p, MAPPER)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void remoteSchemaReferencesAreRefusedBeforeAnyValidatorIsBuilt() {
        JsonSchemaService schemas = new JsonSchemaService();
        for (String key : List.of("$ref", "$dynamicRef", "$recursiveRef")) {
            com.fasterxml.jackson.databind.JsonNode schema = com.relay.TestSupport.json(
                    "{\"type\":\"object\",\"properties\":{\"x\":{\"" + key + "\":\"http://169.254.169.254/schema.json\"}},\"allOf\":[{\"" + key + "\":\"other.json\"}]}");
            assertThatThrownBy(() -> schemas.compile(schema)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("remote references");
        }
        com.fasterxml.jackson.databind.JsonNode local = com.relay.TestSupport.json(
                "{\"$defs\":{\"n\":{\"type\":\"integer\"}},\"type\":\"object\",\"properties\":{\"x\":{\"$ref\":\"#/$defs/n\"}},\"required\":[\"x\"]}");
        assertThat(schemas.validate(local, com.relay.TestSupport.json("{\"x\":1}"))).isEmpty();
        assertThat(schemas.validate(local, com.relay.TestSupport.json("{\"x\":\"no\"}"))).isNotEmpty();
        assertThatThrownBy(() -> schemas.compile(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> schemas.compile(com.relay.TestSupport.json("[1]"))).isInstanceOf(IllegalArgumentException.class);
    }
}
