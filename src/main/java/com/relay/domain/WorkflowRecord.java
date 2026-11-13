package com.relay.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/** A workflow's working copy. {@code currentVersion > 0} means a published version exists (see {@link #hasPublishedVersion()}). */
public record WorkflowRecord(
        String id,
        String name,
        String description,
        String status,
        JsonNode definition,
        String webhookSecretHash,
        int currentVersion,
        Instant createdAt,
        Instant updatedAt,
        Instant publishedAt) {

    public WorkflowRecord {
        definition = Json.copy(definition);
    }

    @Override public JsonNode definition() { return Json.copy(definition); }

    /** The working copy is currently in the published state (no edits since the last publish). */
    public boolean isPublished() { return "published".equals(status); }

    /** At least one version has ever been published, whatever the working copy's current state. */
    public boolean hasPublishedVersion() { return currentVersion > 0; }
}
