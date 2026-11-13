package com.relay.definition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * The node/trigger type registry loaded from {@code data/node_catalog.json}. It is the boundary for
 * publish validation (and for the NL compiler): nothing outside the catalog can be published.
 */
@Component
public class NodeCatalog {

    public record ParamSpec(String name, String type, boolean required, List<String> enumValues, boolean templatable) {}

    public record NodeType(
            String type,
            String description,
            boolean sideEffect,
            boolean requiresApproval,
            Map<String, ParamSpec> params,
            List<String> branches) {}

    public record TriggerType(String type, String description, Map<String, ParamSpec> config) {}

    private final JsonNode raw;
    private final Map<String, NodeType> nodeTypes = new LinkedHashMap<>();
    private final Map<String, TriggerType> triggerTypes = new LinkedHashMap<>();

    public NodeCatalog(ObjectMapper mapper) throws IOException {
        try (InputStream in = new ClassPathResource("data/node_catalog.json").getInputStream()) {
            this.raw = mapper.readTree(in);
        }
        for (JsonNode n : raw.path("nodes")) {
            nodeTypes.put(n.path("type").asText(), parseNode(n));
        }
        for (JsonNode t : raw.path("triggers")) {
            triggerTypes.put(t.path("type").asText(),
                    new TriggerType(t.path("type").asText(), t.path("description").asText(""), parseParams(t.path("config"))));
        }
    }

    private static NodeType parseNode(JsonNode n) {
        List<String> branches = new ArrayList<>();
        n.path("branches").forEach(b -> branches.add(b.asText()));
        return new NodeType(
                n.path("type").asText(),
                n.path("description").asText(""),
                n.path("side_effect").asBoolean(false),
                n.path("requires_approval").asBoolean(false),
                parseParams(n.path("params")),
                branches);
    }

    private static Map<String, ParamSpec> parseParams(JsonNode params) {
        Map<String, ParamSpec> out = new LinkedHashMap<>();
        params.fields().forEachRemaining(e -> {
            JsonNode p = e.getValue();
            List<String> enums = new ArrayList<>();
            p.path("enum").forEach(v -> enums.add(v.asText()));
            out.put(e.getKey(), new ParamSpec(
                    e.getKey(),
                    p.path("type").asText("string"),
                    p.path("required").asBoolean(false),
                    enums,
                    p.path("templatable").asBoolean(false)));
        });
        return out;
    }

    public Optional<NodeType> nodeType(String type) { return Optional.ofNullable(nodeTypes.get(type)); }

    public Optional<TriggerType> triggerType(String type) { return Optional.ofNullable(triggerTypes.get(type)); }

    public Map<String, NodeType> nodeTypes() { return Collections.unmodifiableMap(nodeTypes); }

    public Map<String, TriggerType> triggerTypes() { return Collections.unmodifiableMap(triggerTypes); }

    /** The raw catalog document (served at GET /catalog and given to the NL compiler). */
    public JsonNode raw() { return raw; }
}
