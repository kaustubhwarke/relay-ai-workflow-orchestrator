package com.relay.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/** A workflow's working copy. {@code currentVersion > 0} means a published version exists. */
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

    public boolean isPublished() { return "published".equals(status); }
}
