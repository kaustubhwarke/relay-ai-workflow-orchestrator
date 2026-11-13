package com.relay.definition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A typed, read-only view over a (validated) workflow definition JSON. The JSON document remains the
 * source of truth; this class only gives the engine convenient accessors.
 */
public final class WorkflowDefinition {

    public record Trigger(String type, String cron) {}

    /** A value of 0 for timeoutSeconds / maxAiTokens means "no limit". */
    public record Limits(int maxSteps, int timeoutSeconds, int maxAiTokens) {}

    /** Optional per-node retry override ({@code "retry": {"max_attempts": 5, "base_delay_ms": 200}}). */
    public record RetrySpec(Integer maxAttempts, Long baseDelayMs, Long maxDelayMs) {}

    public record Node(String id, String type, ObjectNode params, String next, String onTrue, String onFalse, RetrySpec retry) {
        /** The parameters are copied in and out so a caller cannot change the parsed definition. */
        public Node {
            params = params == null ? JsonNodeFactory.instance.objectNode() : params.deepCopy();
        }

        @Override public ObjectNode params() { return params.deepCopy(); }

        public boolean isCondition() { return "condition".equals(type); }
    }

    private final String id;
    private final String name;
    private final Trigger trigger;
    private final String entry;
    private final Limits limits;
    private final Map<String, Node> nodes;

    private WorkflowDefinition(String id, String name, Trigger trigger, String entry, Limits limits, Map<String, Node> nodes) {
        this.id = id;
        this.name = name;
        this.trigger = trigger;
        this.entry = entry;
        this.limits = limits;
        this.nodes = java.util.Collections.unmodifiableMap(nodes);
    }

    /** Parses a definition. Missing limits fall back to {@code defaultMaxSteps}. */
    public static WorkflowDefinition parse(JsonNode json, int defaultMaxSteps) {
        JsonNode t = json.path("trigger");
        Trigger trigger = new Trigger(t.path("type").asText("manual"), t.has("cron") ? t.path("cron").asText() : null);
        JsonNode l = json.path("limits");
        Limits limits = new Limits(
                l.path("max_steps").asInt(defaultMaxSteps),
                l.path("timeout_seconds").asInt(0),
                l.path("max_ai_tokens").asInt(0));
        Map<String, Node> nodes = new LinkedHashMap<>();
        for (JsonNode n : json.path("nodes")) {
            JsonNode params = n.path("params");
            ObjectNode paramsObj = params.isObject() ? (ObjectNode) params : JsonNodeFactory.instance.objectNode();
            JsonNode r = n.path("retry");
            RetrySpec retry = r.isObject()
                    ? new RetrySpec(
                            r.has("max_attempts") ? r.get("max_attempts").asInt() : null,
                            r.has("base_delay_ms") ? r.get("base_delay_ms").asLong() : null,
                            r.has("max_delay_ms") ? r.get("max_delay_ms").asLong() : null)
                    : null;
            nodes.put(n.path("id").asText(), new Node(
                    n.path("id").asText(),
                    n.path("type").asText(),
                    paramsObj,
                    textOrNull(n.get("next")),
                    textOrNull(n.get("on_true")),
                    textOrNull(n.get("on_false")),
                    retry));
        }
        return new WorkflowDefinition(
                json.path("id").asText(), json.path("name").asText(), trigger, json.path("entry").asText(), limits, nodes);
    }

    private static String textOrNull(JsonNode n) {
        return n == null || n.isNull() ? null : n.asText();
    }

    public String id() { return id; }
    public String name() { return name; }
    public Trigger trigger() { return trigger; }
    public String entry() { return entry; }
    public Limits limits() { return limits; }
    public Map<String, Node> nodes() { return nodes; }
    public Node node(String nodeId) { return nodes.get(nodeId); }

    /** The node the run continues with after {@code node} produced {@code conditionResult} (ignored for non-conditions). */
    public String successor(Node node, boolean conditionResult) {
        if (node.isCondition()) {
            return conditionResult ? node.onTrue() : node.onFalse();
        }
        return node.next();
    }
}
