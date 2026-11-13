package com.relay.domain;

import java.time.Instant;

public record ApprovalRecord(
        String id,
        String runId,
        String nodeId,
        int stepSequence,
        String message,
        ApprovalStatus status,
        String decidedBy,
        Instant decidedAt,
        String decisionComment,
        Instant createdAt) {
}
