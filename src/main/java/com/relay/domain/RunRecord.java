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

    /** JSON trees are copied in and out, so nobody can change a run's snapshot (used by replay) after it is read. */
    public RunRecord {
        definitionSnapshot = Json.copy(definitionSnapshot);
        input = Json.copy(input);
        error = Json.copy(error);
    }

    @Override public JsonNode definitionSnapshot() { return Json.copy(definitionSnapshot); }
    @Override public JsonNode input() { return Json.copy(input); }
    @Override public JsonNode error() { return Json.copy(error); }
}
