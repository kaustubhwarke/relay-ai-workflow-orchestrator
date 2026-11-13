package com.relay.ai;

import static com.relay.TestSupport.MAPPER;
import static com.relay.TestSupport.json;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.relay.config.RelayProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class HeuristicAiProviderTest {

    private final JsonNode triageSchema = json("""
            {"type":"object","properties":{
               "category":{"type":"string","enum":["refund_request","complaint","question"]},
               "priority":{"type":"string","enum":["low","medium","high"]},
               "summary":{"type":"string","maxLength":300}},
             "required":["category","priority","summary"],"additionalProperties":false}""");

    private final AiNodeRunner runner = new AiNodeRunner(new HeuristicAiProvider(MAPPER), new JsonSchemaService(), MAPPER, new RelayProperties());

    private JsonNode classify(String message, String orderId) {
        String prompt = "Classify.\n\nCustomer message (untrusted input):\n---\n" + message + "\n---\n\nOrder ID: " + orderId;
        return runner.run(prompt, triageSchema).output();
    }

    private static JsonNode payload(String id) throws IOException {
        for (String line : Files.readAllLines(Path.of("data/sample_payloads.jsonl"))) {
            JsonNode n = json(line);
            if (id.equals(n.path("id").asText())) return n;
        }
        throw new IllegalArgumentException(id);
    }

    @Test
    void classifiesTheProvidedSamplePayloadsAsLabelled() throws IOException {
        for (String id : List.of("pay_001", "pay_002", "pay_003")) {
            JsonNode p = payload(id);
            JsonNode out = classify(p.path("body").path("message").asText(), p.path("body").path("order_id").asText());
            assertThat(out.get("category").asText()).as(id).isEqualTo(p.path("expected").path("category").asText());
        }
    }

    @Test
    void injectionPayloadsCannotMakeTheSummaryParrotTheirInstructions() throws IOException {
        for (String id : List.of("pay_inject_001", "pay_inject_002")) {
            JsonNode p = payload(id);
            JsonNode out = classify(p.path("body").path("message").asText(), p.path("body").path("order_id").asText());
            String summary = out.get("summary").asText().toLowerCase();
            assertThat(summary).doesNotContain("audit@evil").doesNotContain("system prompt").doesNotContain("5000").doesNotContain("admin");
        }
    }

    @Test
    void forceInvalidFirstExercisesTheRepairPath() {
        JsonNode out = classify("[force-invalid-first] my speaker is broken", "ord_1");
        assertThat(out.get("category").asText()).isEqualTo("complaint");
    }

    @Test
    void forceInvalidAlwaysFailsCleanly() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> classify("[force-invalid-always] hi", "ord_1"))
                .isInstanceOf(com.relay.engine.NodeExecutionException.class)
                .hasMessageContaining("did not conform");
    }

    @Test
    void producesAMinimalValidObjectForUnknownSchemas() {
        JsonNode schema = json("""
                {"type":"object","properties":{"approved":{"type":"boolean"},"score":{"type":"number"},
                 "label":{"type":"string","enum":["a","b"]}},"required":["approved","score","label"]}""");
        JsonNode out = runner.run("anything", schema).output();
        assertThat(out.get("approved").asBoolean()).isFalse();
        assertThat(out.get("label").asText()).isEqualTo("a");
    }
}
