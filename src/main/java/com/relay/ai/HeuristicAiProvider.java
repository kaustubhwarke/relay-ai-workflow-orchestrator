package com.relay.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A deterministic, offline stand-in for a language model. It lets the whole platform (including the demo
 * and the CI pipeline) run with no API key and no network, and it is what engine tests run against.
 *
 * <p>It is deliberately <em>not</em> an instruction-following model: it classifies by keyword and writes a
 * generic summary that never copies customer text, so prompt-injection payloads cannot steer it. For
 * unknown schemas it emits a minimal value that satisfies the common schema keywords.
 *
 * <p>Test hooks (only honoured by this provider, and only outside the delimited third-party text): a prompt containing {@code [force-invalid-first]} makes the
 * first response schema-invalid (exercising the repair retry); {@code [force-invalid-always]} makes every
 * response invalid (exercising the clean failure path).
 */
public class HeuristicAiProvider implements AiProvider {

    private static final List<String> REFUND_WORDS = List.of("refund", "money back", "reimburse", "chargeback", "charge back");
    private static final List<String> COMPLAINT_WORDS = List.of("cracked", "broken", "damaged", "disappointed", "not working",
            "stopped working", "defective", "terrible", "poor", "late", "missing", "wrong item");
    private static final List<String> URGENT_WORDS = List.of("urgent", "immediately", "asap", "furious", "fraud", "emergency");
    private static final Pattern ORDER_ID = Pattern.compile("Order ID:\\s*([A-Za-z0-9_\\-]+)");

    private final ObjectMapper mapper;

    public HeuristicAiProvider(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String name() { return "heuristic-offline-model"; }

    @Override
    public AiCompletion complete(AiRequest request) {
        String prompt = request.messages().get(0).content();
        boolean isRepair = request.messages().size() > 1;
        String content;
        // Hooks are read only from the trusted part of the prompt: text between the '---' delimiters is
        // third-party data and can never switch a test hook on.
        String trusted = withoutCustomerText(prompt);
        if (trusted.contains("[force-invalid-always]") || (trusted.contains("[force-invalid-first]") && !isRepair)) {
            content = "Sure! Here is my answer: category=refund_request";
        } else {
            content = writeJson(answer(prompt, request.outputSchema()));
        }
        int promptTokens = words(request.system()) + words(prompt);
        return new AiCompletion(content, promptTokens, words(content), name());
    }

    private JsonNode answer(String prompt, JsonNode schema) {
        JsonNode props = schema == null ? null : schema.path("properties");
        if (props != null && props.has("category") && props.has("priority") && props.has("summary")) {
            return triage(prompt, props);
        }
        return sample(schema);
    }

    private JsonNode triage(String prompt, JsonNode props) {
        String text = customerText(prompt).toLowerCase(Locale.ROOT);
        String category = containsAny(text, REFUND_WORDS) ? "refund_request"
                : containsAny(text, COMPLAINT_WORDS) ? "complaint" : "question";
        String priority = containsAny(text, URGENT_WORDS) ? "high" : category.equals("question") ? "low" : "medium";
        Matcher m = ORDER_ID.matcher(prompt);
        String order = m.find() ? m.group(1) : "unknown";
        ObjectNode out = mapper.createObjectNode();
        out.put("category", category);
        out.put("priority", priority);
        // Generic on purpose: never echo untrusted customer text into downstream fields.
        out.put("summary", "Customer message classified as " + category.replace('_', ' ') + " for order " + order);
        return out;
    }

    /** The text between the first pair of '---' delimiter lines, else the whole prompt. */
    private static String customerText(String prompt) {
        int a = prompt.indexOf("---");
        if (a >= 0) {
            int b = prompt.indexOf("---", a + 3);
            if (b > a) return prompt.substring(a + 3, b);
        }
        return prompt;
    }

    /** The prompt with the delimited third-party text removed. */
    private static String withoutCustomerText(String prompt) {
        int a = prompt.indexOf("---");
        if (a >= 0) {
            int b = prompt.indexOf("---", a + 3);
            if (b > a) return prompt.substring(0, a) + prompt.substring(b + 3);
        }
        return prompt;
    }

    private static final Map<String, String> FORMAT_SAMPLES = Map.of(
            "email", "user@example.com",
            "uri", "https://example.com/",
            "uuid", "123e4567-e89b-42d3-a456-426614174000",
            "date", "2026-01-01",
            "date-time", "2026-01-01T00:00:00Z",
            "time", "00:00:00Z",
            "hostname", "example.com",
            "ipv4", "192.0.2.1");

    /**
     * Builds a minimal value for a schema. It honours the common constraint keywords (const, enum, default,
     * string formats and length bounds, numeric bounds, minimum item and property counts, first branch of
     * oneOf/anyOf/allOf). It is best effort: the engine validates every answer regardless, so a schema using
     * constraints this method cannot satisfy (for example a regular expression) fails cleanly instead of
     * passing unvalidated.
     */
    private JsonNode sample(JsonNode schema) {
        if (schema == null || schema.isMissingNode() || !schema.isObject()) return mapper.createObjectNode();
        if (schema.has("const")) return schema.get("const");
        if (schema.has("enum") && schema.get("enum").size() > 0) return schema.get("enum").get(0);
        if (schema.has("default")) return schema.get("default");
        for (String combinator : List.of("oneOf", "anyOf", "allOf")) {
            if (schema.path(combinator).isArray() && schema.get(combinator).size() > 0) return sample(schema.get(combinator).get(0));
        }
        JsonNode type = schema.path("type");
        String t = type.isTextual() ? type.asText()
                : type.isArray() && type.size() > 0 ? type.get(0).asText() : "object";
        switch (t) {
            case "string": return mapper.getNodeFactory().textNode(sampleString(schema));
            case "integer": return mapper.getNodeFactory().numberNode((long) Math.ceil(sampleNumber(schema)));
            case "number": return mapper.getNodeFactory().numberNode(sampleNumber(schema));
            case "boolean": return mapper.getNodeFactory().booleanNode(false);
            case "null": return mapper.getNodeFactory().nullNode();
            case "array": {
                ArrayNode arr = mapper.createArrayNode();
                for (int i = 0; i < schema.path("minItems").asInt(0); i++) arr.add(sample(schema.path("items")));
                return arr;
            }
            default: {
                ObjectNode obj = mapper.createObjectNode();
                JsonNode props = schema.path("properties");
                for (JsonNode req : schema.path("required")) {
                    obj.set(req.asText(), sample(props.path(req.asText())));
                }
                return obj;
            }
        }
    }

    private static String sampleString(JsonNode schema) {
        String base = FORMAT_SAMPLES.getOrDefault(schema.path("format").asText(""), "n/a");
        int min = schema.path("minLength").asInt(0);
        StringBuilder sb = new StringBuilder(base);
        while (sb.length() < min) sb.append('a');
        int max = schema.path("maxLength").asInt(Integer.MAX_VALUE);
        return sb.length() > max ? sb.substring(0, max) : sb.toString();
    }

    private static double sampleNumber(JsonNode schema) {
        double v = 0;
        if (schema.has("minimum")) v = Math.max(v, schema.get("minimum").asDouble());
        if (schema.has("exclusiveMinimum")) v = Math.max(v, schema.get("exclusiveMinimum").asDouble() + 1);
        if (schema.has("maximum")) v = Math.min(v, schema.get("maximum").asDouble());
        if (schema.has("exclusiveMaximum")) v = Math.min(v, schema.get("exclusiveMaximum").asDouble() - 1);
        double step = schema.path("multipleOf").asDouble(0);
        return step > 0 ? Math.ceil(v / step) * step : v;
    }

    private String writeJson(JsonNode n) {
        try {
            return mapper.writeValueAsString(n);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AiProviderException("ai_provider_error", "Could not serialize model output", false, e);
        }
    }

    private static boolean containsAny(String text, List<String> words) {
        for (String w : words) if (text.contains(w)) return true;
        return false;
    }

    private static int words(String s) { return s == null || s.isBlank() ? 0 : s.trim().split("\\s+").length; }
}
