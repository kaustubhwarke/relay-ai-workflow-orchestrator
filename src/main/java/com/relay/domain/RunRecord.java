package com.relay.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

public record RunRecord(
        String id,
        String workflowId,
        int workflowVersion,
        JsonNode definitionSnapshot,
        RunStatus status,
        String triggerType,
        JsonNode input,
        String currentNodeId,
        int stepsExecuted,
        int aiTokensUsed,
        JsonNode error,
        boolean cancelRequested,
        String cancelledReason,
        String replayOfRunId,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt) {
}
