package com.relay.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/** One node execution: the unit of the trace and the unit of crash recovery. */
public record StepRecord(
        long id,
        String runId,
        int sequence,
        String nodeId,
        String nodeType,
        StepStatus status,
        int attempt,
        JsonNode resolvedInput,
        JsonNode output,
        String error,
        String errorCode,
        Integer tokensPrompt,
        Integer tokensCompletion,
        JsonNode aiAttempts,
        String idempotencyKey,
        String approvalId,
        Instant resumeAt,
        Instant startedAt,
        Instant finishedAt,
        Long durationMs) {
}
