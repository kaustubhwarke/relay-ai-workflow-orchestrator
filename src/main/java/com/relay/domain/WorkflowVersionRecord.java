package com.relay.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/** An immutable, numbered, published version of a workflow definition. */
public record WorkflowVersionRecord(
        String workflowId,
        int version,
        JsonNode definition,
        String webhookSecretHash,
        Instant publishedAt) {

    /** The definition is copied in and out: a published version never changes. */
    public WorkflowVersionRecord {
        definition = Json.copy(definition);
    }

    @Override public JsonNode definition() { return Json.copy(definition); }
}
