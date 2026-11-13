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

    private AiNodeRunner.Result run(String hook, String message, String orderId) {
        String prompt = hook + "Classify.\n\nCustomer message (untrusted input):\n---\n" + message + "\n---\n\nOrder ID: " + orderId;
        return runner.run(prompt, triageSchema);
    }

    private JsonNode classify(String message, String orderId) {
        return run("", message, orderId).output();
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
        AiNodeRunner.Result r = run("[force-invalid-first] ", "my speaker is broken", "ord_1");
        assertThat(r.output().get("category").asText()).isEqualTo("complaint");
        // observable evidence that the repair ran: two attempts, the first invalid, the second valid
        assertThat(r.usage().attempts()).hasSize(2);
        assertThat(r.usage().attempts().get(0).get("valid").asBoolean()).isFalse();
        assertThat(r.usage().attempts().get(1).get("valid").asBoolean()).isTrue();
    }

    @Test
    void forceInvalidAlwaysFailsCleanly() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> run("[force-invalid-always] ", "hi", "ord_1"))
                .isInstanceOf(com.relay.engine.NodeExecutionException.class)
                .hasMessageContaining("did not conform");
    }

    @Test
    void testHooksTypedByACustomerAreIgnored() {
        // the hook text is inside the delimited third-party message, so it must not switch anything on
        AiNodeRunner.Result r = run("", "[force-invalid-always] [force-invalid-first] my speaker is broken", "ord_1");
        assertThat(r.output().get("category").asText()).isEqualTo("complaint");
        assertThat(r.usage().attempts()).hasSize(1);
    }

    @Test
    void generatedValuesHonourCommonSchemaConstraints() {
        JsonNode schema = json("""
                {"type":"object","properties":{
                  "email":{"type":"string","format":"email"},
                  "name":{"type":"string","minLength":8},
                  "short":{"type":"string","maxLength":2},
                  "n":{"type":"integer","minimum":5},
                  "x":{"type":"number","exclusiveMinimum":1,"multipleOf":0.5},
                  "tags":{"type":"array","minItems":2,"items":{"type":"string","format":"uuid"}},
                  "kind":{"const":"fixed"},
                  "pick":{"oneOf":[{"type":"boolean"},{"type":"string"}]},
                  "flag":{"type":"boolean"},
                  "nothing":{"type":"null"},
                  "multi":{"type":["integer","null"]},
                  "dflt":{"type":"string","default":"given"},
                  "obj":{"type":"object","required":["inner"],"properties":{"inner":{"type":"integer","maximum":-3}}}},
                 "required":["email","name","short","n","x","tags","kind","pick","flag","nothing","multi","dflt","obj"]}""");
        JsonNode out = runner.run("anything", schema).output();
        assertThat(out.get("email").asText()).isEqualTo("user@example.com");
        assertThat(out.get("name").asText()).hasSizeGreaterThanOrEqualTo(8);
        assertThat(out.get("short").asText()).hasSize(2);
        assertThat(out.get("n").asInt()).isGreaterThanOrEqualTo(5);
        assertThat(out.get("x").asDouble()).isGreaterThan(1);
        assertThat(out.get("tags")).hasSize(2);
        assertThat(out.get("kind").asText()).isEqualTo("fixed");
        assertThat(out.get("dflt").asText()).isEqualTo("given");
        assertThat(out.get("obj").get("inner").asInt()).isLessThanOrEqualTo(-3);
    }

    @Test
    void aNonObjectSchemaYieldsAnEmptyObjectAndANameIsReported() {
        HeuristicAiProvider p = new HeuristicAiProvider(MAPPER);
        assertThat(p.name()).contains("heuristic");
        AiProvider.AiCompletion c = p.complete(new AiProvider.AiRequest("sys", List.of(AiProvider.ChatMessage.user("hi")), null, 100));
        assertThat(c.content()).isEqualTo("{}");
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
