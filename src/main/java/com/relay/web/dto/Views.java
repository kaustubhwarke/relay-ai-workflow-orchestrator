package com.relay.web.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.relay.domain.ApprovalRecord;
import com.relay.domain.RunRecord;
import com.relay.domain.StepRecord;
import com.relay.domain.WorkflowRecord;
import com.relay.domain.WorkflowVersionRecord;
import java.time.Instant;
import java.util.List;

/**
 * API response shapes. JSON property names are snake_case (global Jackson setting). Nothing here exposes a
 * webhook secret: stored definitions carry only a redaction marker, and run snapshots are never returned.
 */
public final class Views {

    private Views() {}

    public record WorkflowSummary(
            String id, String name, String status, int currentVersion, boolean hasUnpublishedChanges,
            String triggerType, int nodeCount, Instant createdAt, Instant updatedAt, Instant publishedAt) {

        public static WorkflowSummary of(WorkflowRecord w) {
            return new WorkflowSummary(w.id(), w.name(), w.status(), w.currentVersion(),
                    w.hasPublishedVersion() && !w.isPublished(),
                    w.definition().path("trigger").path("type").asText(null),
                    w.definition().path("nodes").size(), w.createdAt(), w.updatedAt(), w.publishedAt());
        }
    }

    public record WorkflowDetail(
            String id, String name, String description, String status, int currentVersion, boolean hasUnpublishedChanges,
            JsonNode definition, Instant createdAt, Instant updatedAt, Instant publishedAt, List<String> warnings) {

        public static WorkflowDetail of(WorkflowRecord w, List<String> warnings) {
            return new WorkflowDetail(w.id(), w.name(), w.description(), w.status(), w.currentVersion(),
                    w.hasPublishedVersion() && !w.isPublished(), w.definition(), w.createdAt(), w.updatedAt(), w.publishedAt(),
                    warnings == null || warnings.isEmpty() ? null : warnings);
        }
    }

    public record VersionView(String workflowId, int version, JsonNode definition, Instant publishedAt) {
        public static VersionView of(WorkflowVersionRecord v) {
            return new VersionView(v.workflowId(), v.version(), v.definition(), v.publishedAt());
        }
    }

    public record TokenUsage(Integer prompt, Integer completion) {}

    public record StepView(
            int sequence, String nodeId, String type, String status, int attempt, JsonNode input, JsonNode output,
            String error, String errorCode, TokenUsage tokens, JsonNode aiAttempts, String idempotencyKey,
            String approvalId, Instant resumeAt, Instant startedAt, Instant finishedAt, Long durationMs) {

        public static StepView of(StepRecord s) {
            TokenUsage tokens = s.tokensPrompt() == null && s.tokensCompletion() == null
                    ? null : new TokenUsage(s.tokensPrompt(), s.tokensCompletion());
            return new StepView(s.sequence(), s.nodeId(), s.nodeType(), s.status().value(), s.attempt(), s.resolvedInput(),
                    s.output(), s.error(), s.errorCode(), tokens, s.aiAttempts(), s.idempotencyKey(), s.approvalId(),
                    s.resumeAt(), s.startedAt(), s.finishedAt(), s.durationMs());
        }
    }

    public record RunSummary(
            String runId, String id, String workflowId, int workflowVersion, String status, String triggerType,
            int stepsExecuted, int aiTokensUsed, JsonNode error, String cancelledReason, String replayOfRunId,
            Instant createdAt, Instant startedAt, Instant finishedAt) {

        public static RunSummary of(RunRecord r) {
            return new RunSummary(r.id(), r.id(), r.workflowId(), r.workflowVersion(), r.status().value(), r.triggerType(),
                    r.stepsExecuted(), r.aiTokensUsed(), r.error(), r.cancelledReason(), r.replayOfRunId(),
                    r.createdAt(), r.startedAt(), r.finishedAt());
        }
    }

    public record RunDetail(
            String runId, String id, String workflowId, int workflowVersion, String status, String triggerType,
            JsonNode input, String currentNodeId, int stepsExecuted, int aiTokensUsed, JsonNode error,
            String cancelledReason, String replayOfRunId, Instant createdAt, Instant startedAt, Instant finishedAt,
            List<StepView> steps) {

        public static RunDetail of(RunRecord r, List<StepRecord> steps) {
            return new RunDetail(r.id(), r.id(), r.workflowId(), r.workflowVersion(), r.status().value(), r.triggerType(),
                    r.input(), r.currentNodeId(), r.stepsExecuted(), r.aiTokensUsed(), r.error(), r.cancelledReason(),
                    r.replayOfRunId(), r.createdAt(), r.startedAt(), r.finishedAt(),
                    steps.stream().map(StepView::of).toList());
        }
    }

    public record ApprovalView(
            String id, String runId, String nodeId, String message, String status, String decidedBy,
            Instant decidedAt, String decisionComment, Instant createdAt) {

        public static ApprovalView of(ApprovalRecord a) {
            return new ApprovalView(a.id(), a.runId(), a.nodeId(), a.message(), a.status().value(), a.decidedBy(),
                    a.decidedAt(), a.decisionComment(), a.createdAt());
        }
    }

    public record TriggerResponse(String runId, String id, String status, String workflowId, int workflowVersion) {
        public static TriggerResponse of(RunRecord r) {
            return new TriggerResponse(r.id(), r.id(), r.status().value(), r.workflowId(), r.workflowVersion());
        }
    }

    public record Page<T>(List<T> items, long total, int limit, int offset) {}
}
