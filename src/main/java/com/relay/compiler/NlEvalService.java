package com.relay.compiler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relay.config.RelayProperties;
import com.relay.definition.DefinitionValidator;
import com.relay.definition.GraphAnalyzer;
import com.relay.definition.NodeCatalog;
import com.relay.definition.WorkflowDefinition;
import com.relay.domain.RelayException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * Runs every labelled case in {@code data/nl_eval.jsonl} through the compiler and scores it exactly as
 * {@code docs/EVALUATION_GUIDE.md} specifies. The compiler only ever sees {@code description}; the
 * {@code assertions} and {@code note} fields are the answer key and are never passed to the model.
 */
@Service
public class NlEvalService {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private final NlCompilerService compiler;
    private final DefinitionValidator validator;
    private final NodeCatalog catalog;
    private final ObjectMapper mapper;
    private final RelayProperties props;

    public NlEvalService(NlCompilerService compiler, DefinitionValidator validator, NodeCatalog catalog,
                         ObjectMapper mapper, RelayProperties props) {
        this.compiler = compiler;
        this.validator = validator;
        this.catalog = catalog;
        this.mapper = mapper;
        this.props = props;
    }

    public JsonNode run() {
        List<JsonNode> cases = load();
        ArrayNode results = F.arrayNode();
        int correct = 0;
        for (JsonNode c : cases) {
            ObjectNode r = evaluate(c);
            if ("pass".equals(r.path("result").asText())) correct++;
            results.add(r);
        }
        ObjectNode out = F.objectNode();
        out.put("model", compiler.modelName());
        out.put("total", cases.size());
        out.put("correct", correct);
        out.put("accuracy", cases.isEmpty() ? 0 : Math.round(100.0 * correct / cases.size()) / 100.0);
        out.set("cases", results);
        return out;
    }

    private ObjectNode evaluate(JsonNode c) {
        String id = c.path("id").asText();
        String expected = c.path("expect").asText();
        JsonNode assertions = c.path("assertions");
        ObjectNode r = F.objectNode();
        r.put("id", id);
        r.put("expected", expected);
        ObjectNode checks = r.putObject("checks");
        NlCompilerService.Result res;
        try {
            // persist=false: evaluation must not litter the workflow store
            res = compiler.compile(c.path("description").asText(), false);
        } catch (RelayException e) {
            r.put("result", "fail");
            r.put("outcome", "error: " + e.code());
            checks.put("compiled", false);
            r.put("detail", e.getMessage() + (e.details().isEmpty() ? "" : " | " + String.join("; ", e.details())));
            return r;
        }
        r.put("attempts", res.attempts());

        if ("refusal".equals(expected)) {
            boolean refused = res.refused();
            checks.put("refused_with_explanation", refused);
            if (refused) {
                r.put("outcome", "refusal");
                r.put("reason", res.refusal().reason());
                ArrayNode missing = r.putArray("missing_capabilities");
                res.refusal().missingCapabilities().forEach(missing::add);
            } else {
                r.put("outcome", "workflow (should have refused)");
            }
            r.put("result", refused ? "pass" : "fail");
            return r;
        }

        if (res.refused()) {
            // Guide: for a request that tries to skip the mandatory approval, "refusing with the requires_approval
            // rule cited also passes" (inserting the gate is preferred).
            boolean gateCase = assertions.path("approval_must_precede_order_action").asBoolean(false);
            boolean citesRule = res.refusal().reason().toLowerCase().contains("approval");
            if (gateCase && citesRule) {
                checks.put("refused_citing_approval_rule", true);
                r.put("outcome", "refusal citing the approval rule (accepted by the guide; inserting the gate is preferred)");
                r.put("reason", res.refusal().reason());
                r.put("result", "pass");
                return r;
            }
            checks.put("returned_workflow", false);
            r.put("outcome", "refusal (should have built a workflow)");
            r.put("reason", res.refusal().reason());
            r.put("result", "fail");
            return r;
        }
        checks.put("returned_workflow", true);
        JsonNode def = res.workflow();
        DefinitionValidator.Result v = validator.validate(def, true);
        checks.put("publishes", v.valid());
        WorkflowDefinition parsed = WorkflowDefinition.parse(def, props.getEngine().getDefaultMaxSteps());

        boolean typesOk = true;
        List<String> missingTypes = new ArrayList<>();
        Set<String> present = new java.util.HashSet<>();
        parsed.nodes().values().forEach(n -> present.add(n.type()));
        for (JsonNode t : assertions.path("required_node_types")) {
            if (!present.contains(t.asText())) {
                typesOk = false;
                missingTypes.add(t.asText());
            }
        }
        checks.put("required_node_types", typesOk);

        boolean branchingOk = true;
        if (assertions.path("requires_branching").asBoolean(false)) {
            branchingOk = parsed.nodes().values().stream().anyMatch(n -> n.isCondition() && n.onTrue() != null && n.onFalse() != null
                    && parsed.node(n.onTrue()) != null && parsed.node(n.onFalse()) != null);
        }
        checks.put("branching", branchingOk);

        boolean gateOk = true;
        if (assertions.path("approval_must_precede_order_action").asBoolean(false)) {
            Set<String> sensitive = Set.of("order_action");
            gateOk = v.analysis() != null
                    && GraphAnalyzer.analyze(parsed, sensitive).ungatedSensitiveNodes().isEmpty()
                    && present.contains("order_action");
        }
        checks.put("approval_precedes_order_action", gateOk);

        // Informational (not part of the guide's pass rule): did it pick the labelled trigger?
        String wantTrigger = assertions.path("trigger").asText("");
        String gotTrigger = parsed.trigger().type();
        boolean hasLoop = v.analysis() != null && !v.analysis().backEdges().isEmpty();
        boolean triggerOk = wantTrigger.isEmpty() || wantTrigger.equals(gotTrigger)
                || ("schedule_or_loop".equals(wantTrigger) && ("schedule".equals(gotTrigger) || hasLoop));
        checks.put("trigger_matches (informational)", triggerOk);

        boolean pass = v.valid() && typesOk && branchingOk && gateOk;
        r.put("outcome", "workflow");
        r.put("trigger", gotTrigger);
        if (!missingTypes.isEmpty()) r.put("missing_node_types", String.join(",", missingTypes));
        if (!res.explanation().isBlank()) r.put("explanation", res.explanation());
        r.put("result", pass ? "pass" : "fail");
        return r;
    }

    private List<JsonNode> load() {
        List<JsonNode> out = new ArrayList<>();
        try {
            String text;
            Path file = Path.of("data", "nl_eval.jsonl");
            if (Files.exists(file)) {
                text = Files.readString(file, StandardCharsets.UTF_8);
            } else {
                try (InputStream in = new ClassPathResource("data/nl_eval.jsonl").getInputStream()) {
                    text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
            for (String line : text.split("\\R")) {
                if (!line.isBlank()) out.add(mapper.readTree(line));
            }
        } catch (IOException e) {
            throw new RelayException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "eval_data_missing",
                    "data/nl_eval.jsonl could not be read");
        }
        return out;
    }

    /** Convenience for reports: case id to result. */
    public static Map<String, String> summary(JsonNode eval) {
        Map<String, String> m = new LinkedHashMap<>();
        eval.path("cases").forEach(c -> m.put(c.path("id").asText(), c.path("result").asText()));
        return m;
    }
}
