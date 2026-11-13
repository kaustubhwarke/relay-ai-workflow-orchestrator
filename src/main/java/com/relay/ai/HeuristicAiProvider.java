package com.relay.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A deterministic, offline stand-in for a language model. It lets the whole platform (including the demo
 * and the CI pipeline) run with no API key and no network, and it is what engine tests run against.
 *
 * <p>It is deliberately <em>not</em> an instruction-following model: it classifies by keyword and writes a
 * generic summary that never copies customer text, so prompt-injection payloads cannot steer it. For
 * unknown schemas it emits a minimal object that satisfies the schema.
 *
 * <p>Test hooks (only honoured by this provider): a prompt containing {@code [force-invalid-first]} makes the
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
        if (prompt.contains("[force-invalid-always]") || (prompt.contains("[force-invalid-first]") && !isRepair)) {
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

    private JsonNode sample(JsonNode schema) {
        if (schema == null || schema.isMissingNode()) return mapper.createObjectNode();
        JsonNode type = schema.path("type");
        if (schema.has("enum")) return schema.get("enum").get(0);
        String t = type.isTextual() ? type.asText() : "object";
        switch (t) {
            case "string": return mapper.getNodeFactory().textNode("n/a");
            case "integer": case "number": return mapper.getNodeFactory().numberNode(0);
            case "boolean": return mapper.getNodeFactory().booleanNode(false);
            case "array": return mapper.createArrayNode();
            default:
                ObjectNode obj = mapper.createObjectNode();
                JsonNode props = schema.path("properties");
                for (JsonNode req : schema.path("required")) {
                    obj.set(req.asText(), sample(props.path(req.asText())));
                }
                return obj;
        }
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
