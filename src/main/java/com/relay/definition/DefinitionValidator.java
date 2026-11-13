package com.relay.definition;

import com.fasterxml.jackson.databind.JsonNode;
import com.relay.ai.JsonSchemaService;
import com.relay.config.RelayProperties;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

/**
 * Publish-time validation of a workflow definition against the node catalog.
 *
 * <p>Every rejection carries a stable machine-readable {@code code} and a distinct, human-readable
 * message so that the failure can be diagnosed from the response alone. Loops (backward jumps) are
 * legal and never rejected.
 */
@Component
public class DefinitionValidator {

    public record Issue(String code, String message, String nodeId) {
        @Override
        public String toString() { return message; }
    }

    public record Result(List<Issue> errors, List<String> warnings, GraphAnalyzer.Analysis analysis) {
        public boolean valid() { return errors.isEmpty(); }
    }

    static final Pattern ID_PATTERN = Pattern.compile("^[A-Za-z0-9_\\-]{1,100}$");
    static final Pattern TEMPLATE = Pattern.compile("\\{\\{\\s*([^{}]+?)\\s*}}");
    private static final Set<String> TEMPLATE_ROOTS = Set.of("trigger", "nodes", "run");

    private final NodeCatalog catalog;
    private final JsonSchemaService schemas;
    private final RelayProperties props;

    public DefinitionValidator(NodeCatalog catalog, JsonSchemaService schemas, RelayProperties props) {
        this.catalog = catalog;
        this.schemas = schemas;
        this.props = props;
    }

    /**
     * @param definition the workflow definition JSON
     * @param hasWebhookSecret whether a webhook secret is known (in the payload or already stored)
     */
    public Result validate(JsonNode definition, boolean hasWebhookSecret) {
        List<Issue> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (definition == null || !definition.isObject()) {
            errors.add(new Issue("invalid_definition", "Workflow definition must be a JSON object", null));
            return new Result(errors, warnings, null);
        }

        String id = text(definition, "id");
        if (id == null || !ID_PATTERN.matcher(id).matches()) {
            errors.add(new Issue("invalid_id", "Workflow 'id' is required and may contain only letters, digits, '_' and '-' (max 100)", null));
        }
        if (isBlank(text(definition, "name"))) {
            errors.add(new Issue("missing_name", "Workflow 'name' is required", null));
        }

        validateTrigger(definition.get("trigger"), hasWebhookSecret, errors);
        validateLimits(definition.get("limits"), errors);

        JsonNode nodesNode = definition.get("nodes");
        if (nodesNode == null || !nodesNode.isArray() || nodesNode.isEmpty()) {
            errors.add(new Issue("missing_nodes", "Workflow must define a non-empty 'nodes' array", null));
            return new Result(errors, warnings, null);
        }

        Map<String, JsonNode> byId = new LinkedHashMap<>();
        for (JsonNode n : nodesNode) {
            String nid = text(n, "id");
            if (nid == null || !ID_PATTERN.matcher(nid).matches()) {
                errors.add(new Issue("invalid_node_id", "Every node needs an 'id' made of letters, digits, '_' or '-'", null));
                continue;
            }
            if (byId.putIfAbsent(nid, n) != null) {
                errors.add(new Issue("duplicate_node_id", "Duplicate node id '" + nid + "'", nid));
            }
        }

        String entry = text(definition, "entry");
        if (isBlank(entry)) {
            errors.add(new Issue("missing_entry", "Workflow 'entry' is required", null));
        } else if (!byId.containsKey(entry)) {
            errors.add(new Issue("entry_not_found", "Entry node '" + entry + "' does not exist", entry));
        }

        for (Map.Entry<String, JsonNode> e : byId.entrySet()) {
            validateNode(e.getKey(), e.getValue(), byId.keySet(), errors);
        }

        GraphAnalyzer.Analysis analysis = null;
        if (errors.isEmpty()) {
            WorkflowDefinition parsed = WorkflowDefinition.parse(definition, props.getEngine().getDefaultMaxSteps());
            Set<String> sensitive = catalog.nodeTypes().values().stream()
                    .filter(NodeCatalog.NodeType::requiresApproval)
                    .map(NodeCatalog.NodeType::type)
                    .collect(Collectors.toSet());
            analysis = GraphAnalyzer.analyze(parsed, sensitive);
            for (String n : analysis.unreachable()) {
                warnings.add("Node '" + n + "' is unreachable from entry '" + parsed.entry() + "'");
            }
            for (GraphAnalyzer.Edge b : analysis.backEdges()) {
                warnings.add("Loop detected: '" + b.from() + "' jumps back to '" + b.to()
                        + "' (allowed; bounded at runtime by limits.max_steps=" + parsed.limits().maxSteps() + ")");
            }
            for (String n : analysis.ungatedSensitiveNodes()) {
                warnings.add("Sensitive node '" + n + "' is reachable without passing an approval node; "
                        + "the engine will refuse to execute it at runtime unless an approval was granted");
            }
        }
        return new Result(errors, warnings, analysis);
    }

    private void validateTrigger(JsonNode trigger, boolean hasWebhookSecret, List<Issue> errors) {
        if (trigger == null || !trigger.isObject()) {
            errors.add(new Issue("missing_trigger", "Workflow 'trigger' is required", null));
            return;
        }
        String type = text(trigger, "type");
        var spec = type == null ? null : catalog.triggerType(type).orElse(null);
        if (spec == null) {
            errors.add(new Issue("invalid_trigger_type", "Unknown trigger type '" + type + "'. Allowed: "
                    + String.join(", ", catalog.triggerTypes().keySet()), null));
            return;
        }
        if ("webhook".equals(type) && !hasWebhookSecret) {
            errors.add(new Issue("missing_trigger_secret", "Webhook trigger requires a non-empty 'secret'", null));
        }
        if ("schedule".equals(type)) {
            String cron = text(trigger, "cron");
            if (isBlank(cron)) {
                errors.add(new Issue("missing_trigger_cron", "Schedule trigger requires a 'cron' expression", null));
            } else if (!isValidCron(cron)) {
                errors.add(new Issue("invalid_cron", "Invalid 5-field cron expression '" + cron + "'", null));
            }
        }
    }

    public static boolean isValidId(String id) {
        return id != null && ID_PATTERN.matcher(id).matches();
    }

    public static boolean isValidCron(String cron) {
        if (cron == null) return false;
        String[] parts = cron.trim().split("\\s+");
        if (parts.length != 5) return false;
        try {
            CronExpression.parse("0 " + cron.trim());
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private void validateLimits(JsonNode limits, List<Issue> errors) {
        if (limits == null || limits.isNull()) return;
        if (!limits.isObject()) {
            errors.add(new Issue("invalid_limits", "'limits' must be an object", null));
            return;
        }
        JsonNode ms = limits.get("max_steps");
        if (ms != null) {
            if (!ms.isInt() || ms.asInt() < 1) {
                errors.add(new Issue("invalid_limits", "limits.max_steps must be a positive integer", null));
            } else if (ms.asInt() > props.getEngine().getHardMaxSteps()) {
                errors.add(new Issue("invalid_limits",
                        "limits.max_steps may not exceed " + props.getEngine().getHardMaxSteps(), null));
            }
        }
        for (String f : new String[] {"timeout_seconds", "max_ai_tokens"}) {
            JsonNode v = limits.get(f);
            if (v != null && (!v.isInt() || v.asInt() < 0)) {
                errors.add(new Issue("invalid_limits", "limits." + f + " must be a non-negative integer", null));
            }
        }
    }

    private void validateNode(String id, JsonNode node, Set<String> allIds, List<Issue> errors) {
        String type = text(node, "type");
        NodeCatalog.NodeType spec = type == null ? null : catalog.nodeType(type).orElse(null);
        if (spec == null) {
            errors.add(new Issue("invalid_node_type", "Node '" + id + "' has unknown type '" + type + "'", id));
            return;
        }

        JsonNode params = node.get("params");
        if (params == null || params.isNull()) params = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        if (!params.isObject()) {
            errors.add(new Issue("invalid_params", "Node '" + id + "': 'params' must be an object", id));
            return;
        }
        for (NodeCatalog.ParamSpec p : spec.params().values()) {
            JsonNode v = params.get(p.name());
            boolean missing = v == null || v.isNull() || (v.isTextual() && v.asText().isBlank());
            if (p.required() && missing) {
                errors.add(new Issue("missing_required_param",
                        "Node '" + id + "' (" + type + ") is missing required param '" + p.name() + "'", id));
                continue;
            }
            if (v == null || v.isNull()) continue;
            checkParamType(id, type, p, v, errors);
        }
        Iterator<String> names = params.fieldNames();
        while (names.hasNext()) {
            String n = names.next();
            if (!spec.params().containsKey(n)) {
                errors.add(new Issue("unknown_param", "Node '" + id + "' (" + type + ") has unknown param '" + n + "'", id));
            }
        }

        if ("ai".equals(type) && params.has("output_schema")) {
            try {
                schemas.compile(params.get("output_schema"));
            } catch (IllegalArgumentException e) {
                errors.add(new Issue("invalid_output_schema", "Node '" + id + "': " + e.getMessage(), id));
            }
        }

        validateEdges(id, type, node, allIds, errors);
        validateRetry(id, node.get("retry"), errors);
        validateTemplates(id, params, allIds, errors);
    }

    private void checkParamType(String nodeId, String nodeType, NodeCatalog.ParamSpec p, JsonNode v, List<Issue> errors) {
        boolean templated = v.isTextual() && TEMPLATE.matcher(v.asText()).find();
        boolean ok = switch (p.type()) {
            case "string" -> v.isTextual();
            case "number" -> v.isNumber() || templated;
            case "object" -> v.isObject() || templated;
            case "boolean" -> v.isBoolean() || templated;
            default -> true;
        };
        if (!ok) {
            errors.add(new Issue("invalid_param_type",
                    "Node '" + nodeId + "' (" + nodeType + "): param '" + p.name() + "' must be of type " + p.type(), nodeId));
            return;
        }
        if (!p.enumValues().isEmpty() && !templated && !p.enumValues().contains(v.asText())) {
            errors.add(new Issue("invalid_param_value",
                    "Node '" + nodeId + "' (" + nodeType + "): param '" + p.name() + "' must be one of " + p.enumValues()
                            + " but was '" + v.asText() + "'", nodeId));
        }
    }

    private void validateEdges(String id, String type, JsonNode node, Set<String> allIds, List<Issue> errors) {
        boolean isCondition = "condition".equals(type);
        for (String edge : new String[] {"next", "on_true", "on_false"}) {
            JsonNode target = node.get(edge);
            boolean branchEdge = !"next".equals(edge);
            if (target == null || target.isNull()) {
                if (isCondition && branchEdge) {
                    errors.add(new Issue("missing_branch", "Condition node '" + id + "' must define '" + edge
                            + "' pointing to an existing node (if that outcome should do nothing, point it to a terminal no-op node such as a delay with seconds 0)", id));
                }
                continue;
            }
            if (isCondition && !branchEdge) {
                errors.add(new Issue("unexpected_next", "Condition node '" + id + "' must use on_true/on_false, not 'next'", id));
                continue;
            }
            if (!isCondition && branchEdge) {
                errors.add(new Issue("unexpected_branch", "Only condition nodes may define '" + edge + "' (node '" + id + "')", id));
                continue;
            }
            if (!target.isTextual() || !allIds.contains(target.asText())) {
                errors.add(new Issue("dangling_reference",
                        "Node '" + id + "' '" + edge + "' points to nonexistent node '" + target.asText() + "'", id));
            }
        }
    }

    private void validateRetry(String id, JsonNode retry, List<Issue> errors) {
        if (retry == null || retry.isNull()) return;
        if (!retry.isObject()) {
            errors.add(new Issue("invalid_retry", "Node '" + id + "': 'retry' must be an object", id));
            return;
        }
        JsonNode ma = retry.get("max_attempts");
        if (ma != null && (!ma.isInt() || ma.asInt() < 1 || ma.asInt() > 20)) {
            errors.add(new Issue("invalid_retry", "Node '" + id + "': retry.max_attempts must be between 1 and 20", id));
        }
    }

    /** Statically checks that template references point at a known root and a node that exists. */
    private void validateTemplates(String nodeId, JsonNode value, Set<String> allIds, List<Issue> errors) {
        Set<String> seen = new HashSet<>();
        collectTemplates(value, seen);
        for (String path : seen) {
            String[] seg = path.split("\\.");
            if (!TEMPLATE_ROOTS.contains(seg[0])) {
                errors.add(new Issue("invalid_template",
                        "Node '" + nodeId + "': template '{{" + path + "}}' must start with trigger., nodes. or run.", nodeId));
            } else if ("nodes".equals(seg[0]) && (seg.length < 3 || !"output".equals(seg[2]))) {
                errors.add(new Issue("invalid_template",
                        "Node '" + nodeId + "': template '{{" + path + "}}' must look like nodes.<id>.output.<field>", nodeId));
            } else if ("nodes".equals(seg[0]) && !allIds.contains(seg[1])) {
                errors.add(new Issue("unknown_node_reference",
                        "Node '" + nodeId + "': template '{{" + path + "}}' references nonexistent node '" + seg[1] + "'", nodeId));
            }
        }
    }

    private static void collectTemplates(JsonNode n, Set<String> out) {
        if (n == null) return;
        if (n.isTextual()) {
            Matcher m = TEMPLATE.matcher(n.asText());
            while (m.find()) out.add(m.group(1).trim());
        } else if (n.isContainerNode()) {
            n.forEach(c -> collectTemplates(c, out));
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        return v == null || !v.isTextual() ? null : v.asText();
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
}
