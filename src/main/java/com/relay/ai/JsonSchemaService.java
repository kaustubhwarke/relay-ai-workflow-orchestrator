package com.relay.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Thin wrapper over the JSON Schema validator used to enforce AI node output structure. */
@Component
public class JsonSchemaService {

    private final JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    /** The JSON Schema 2020-12 meta-schema (bundled with the library, no network access). */
    private final JsonSchema metaSchema = factory.getSchema(SchemaLocation.of("https://json-schema.org/draft/2020-12/schema"));

    /** Compiles a schema; throws {@link IllegalArgumentException} if it is not a valid JSON Schema. */
    public JsonSchema compile(JsonNode schema) {
        if (schema == null || !schema.isObject()) {
            throw new IllegalArgumentException("output_schema must be a JSON object");
        }
        rejectRemoteReferences(schema);
        Set<ValidationMessage> metaErrors = metaSchema.validate(schema);
        if (!metaErrors.isEmpty()) {
            throw new IllegalArgumentException("output_schema is not a valid JSON Schema: "
                    + metaErrors.stream().map(ValidationMessage::getMessage).sorted().findFirst().orElse("invalid"));
        }
        try {
            JsonSchema compiled = factory.getSchema(schema);
            compiled.initializeValidators();
            return compiled;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("output_schema is not a valid JSON Schema: " + e.getMessage(), e);
        }
    }

    /**
     * Schemas are author-supplied, so a reference must never make the validator fetch a URL: only in-document
     * references (starting with '#') are allowed. This closes a server-side request path through the schema.
     */
    private static void rejectRemoteReferences(JsonNode node) {
        if (node.isObject()) {
            node.fields().forEachRemaining(e -> {
                if (("$ref".equals(e.getKey()) || "$dynamicRef".equals(e.getKey()) || "$recursiveRef".equals(e.getKey()))
                        && e.getValue().isTextual() && !e.getValue().asText().startsWith("#")) {
                    throw new IllegalArgumentException("output_schema is not allowed to use remote references: "
                            + e.getKey() + " must start with '#'");
                }
                rejectRemoteReferences(e.getValue());
            });
        } else if (node.isArray()) {
            node.forEach(JsonSchemaService::rejectRemoteReferences);
        }
    }

    /** Returns human-readable violations; an empty list means the instance conforms. */
    public List<String> validate(JsonNode schema, JsonNode instance) {
        Set<ValidationMessage> messages = compile(schema).validate(instance);
        List<String> out = new ArrayList<>(messages.size());
        for (ValidationMessage m : messages) {
            out.add(m.getMessage());
        }
        out.sort(String::compareTo);
        return out;
    }
}
