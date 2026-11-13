package com.relay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.relay.ai.JsonSchemaService;
import com.relay.config.RelayProperties;
import com.relay.definition.DefinitionValidator;
import com.relay.definition.NodeCatalog;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.springframework.core.io.ClassPathResource;

/** Shared helpers for tests that need the real catalog and the seed data without starting Spring. */
public final class TestSupport {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    private TestSupport() {}

    public static NodeCatalog catalog() {
        try {
            return new NodeCatalog(MAPPER);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static DefinitionValidator validator() {
        return new DefinitionValidator(catalog(), new JsonSchemaService(), new RelayProperties());
    }

    public static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** One of the four provided seed workflows, as shipped in data/seed_workflows.json. */
    public static JsonNode seed(String id) {
        try (InputStream in = new ClassPathResource("data/seed_workflows.json").getInputStream()) {
            for (JsonNode w : MAPPER.readTree(in).path("workflows")) {
                if (id.equals(w.path("id").asText())) return w;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        throw new IllegalArgumentException("No seed workflow " + id);
    }

    /** A minimal valid manual workflow with a single notify node. */
    public static String minimalWorkflow(String id) {
        return """
                {"id":"%s","name":"Minimal","trigger":{"type":"manual"},"entry":"hello",
                 "limits":{"max_steps":5,"timeout_seconds":60,"max_ai_tokens":0},
                 "nodes":[{"id":"hello","type":"notify",
                           "params":{"channel":"chat","to":"#t","message":"hi"},"next":null}]}
                """.formatted(id);
    }
}
