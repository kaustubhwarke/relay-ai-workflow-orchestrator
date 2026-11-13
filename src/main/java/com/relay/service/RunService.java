package com.relay.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.relay.domain.ApprovalRecord;
import com.relay.domain.ApprovalStatus;
import com.relay.domain.RelayException;
import com.relay.domain.RunRecord;
import com.relay.domain.RunStatus;
import com.relay.domain.StepRecord;
import com.relay.domain.StepStatus;
import com.relay.domain.WorkflowRecord;
import com.relay.domain.WorkflowVersionRecord;
import com.relay.repository.ApprovalRepository;
import com.relay.repository.QueueRepository;
import com.relay.repository.RunRepository;
import com.relay.repository.StepRepository;
import com.relay.repository.WorkflowRepository;
import com.relay.security.SecretHasher;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Triggering, cancelling and replaying runs. Triggers <b>only enqueue</b>: the run row (with its definition
 * snapshot) and its queue job are written in one transaction and the call returns; execution happens on a
 * worker, never in the request handler.
 */
@Service
public class RunService {

    private static final Logger log = LoggerFactory.getLogger(RunService.class);

    private final WorkflowRepository workflows;
    private final RunRepository runs;
    private final StepRepository steps;
    private final ApprovalRepository approvals;
    private final QueueRepository queue;

    public RunService(WorkflowRepository workflows, RunRepository runs, StepRepository steps,
                      ApprovalRepository approvals, QueueRepository queue) {
        this.workflows = workflows;
        this.runs = runs;
        this.steps = steps;
        this.approvals = approvals;
        this.queue = queue;
    }

    @Transactional
    public RunRecord triggerManual(String workflowId, JsonNode input) {
        return start(workflowId, "manual", input == null || input.isNull() ? JsonNodeFactory.instance.objectNode() : input, null);
    }

    /** Webhook trigger: the secret is checked in constant time against the stored hash. */
    @Transactional
    public RunRecord triggerWebhook(String workflowId, String presentedSecret, JsonNode body) {
        WorkflowVersionRecord version = workflows.findLatestVersion(workflowId).orElse(null);
        WorkflowRecord wf = workflows.findById(workflowId).orElseThrow(() -> RelayException.notFound("Workflow", workflowId));
        if (version == null) {
            // Never published: refuse without revealing anything about the secret.
            if (!SecretHasher.matches(presentedSecret, wf.webhookSecretHash())) {
                throw new RelayException(HttpStatus.UNAUTHORIZED, "invalid_webhook_secret", "Missing or invalid X-Relay-Secret");
            }
            throw RelayException.conflict("workflow_not_published", "Workflow '" + workflowId + "' has not been published");
        }
        if (!"webhook".equals(version.definition().path("trigger").path("type").asText())) {
            throw RelayException.conflict("not_a_webhook_workflow", "Workflow '" + workflowId + "' does not use a webhook trigger");
        }
        if (!SecretHasher.matches(presentedSecret, version.webhookSecretHash())) {
            throw new RelayException(HttpStatus.UNAUTHORIZED, "invalid_webhook_secret", "Missing or invalid X-Relay-Secret");
        }
        return start(workflowId, "webhook", body == null || body.isNull() ? JsonNodeFactory.instance.objectNode() : body, null);
    }

    @Transactional
    public RunRecord triggerSchedule(String workflowId) {
        return start(workflowId, "schedule", JsonNodeFactory.instance.objectNode(), null);
    }

    private RunRecord start(String workflowId, String triggerType, JsonNode input, String replayOf) {
        WorkflowVersionRecord version = workflows.findLatestVersion(workflowId).orElse(null);
        if (version == null) {
            workflows.findById(workflowId).orElseThrow(() -> RelayException.notFound("Workflow", workflowId));
            throw RelayException.conflict("workflow_not_published", "Workflow '" + workflowId + "' has not been published; publish it first");
        }
        String runId = newRunId();
        runs.insert(runId, workflowId, version.version(), version.definition(), triggerType, input, replayOf);
        queue.enqueue(runId);
        log.info("Run {} queued for workflow {} v{} via {}", runId, workflowId, version.version(), triggerType);
        return runs.findById(runId).orElseThrow();
    }

    public RunRecord get(String runId) {
        return runs.findById(runId).orElseThrow(() -> RelayException.notFound("Run", runId));
    }

    public List<StepRecord> steps(String runId) {
        return steps.findByRunId(runId);
    }

    public List<RunRecord> list(String workflowId, String status, int limit, int offset) {
        if (status != null) {
            try {
                RunStatus.fromValue(status);
            } catch (IllegalArgumentException e) {
                throw RelayException.badRequest("invalid_status", "Unknown status '" + status + "'");
            }
        }
        return runs.list(workflowId, status, Math.max(1, Math.min(limit, 200)), Math.max(0, offset));
    }

    public long count(String workflowId, String status) {
        return runs.count(workflowId, status);
    }

    /**
     * Cancels a run. queued: cancelled immediately. waiting_approval: cancelled and its pending approval is
     * closed. running: cooperative; the engine checks between steps and the step in flight may finish.
     * Terminal runs return 409.
     */
    @Transactional
    public RunRecord cancel(String runId) {
        RunRecord run = get(runId);
        if (run.status().isTerminal()) {
            throw RelayException.conflict("run_already_terminal",
                    "Run '" + runId + "' is already " + run.status().value() + " and cannot be cancelled");
        }
        switch (run.status()) {
            case QUEUED, WAITING_APPROVAL -> {
                approvals.cancelPendingForRun(runId);
                steps.findLast(runId).ifPresent(last -> {
                    if (last.status() == StepStatus.WAITING) {
                        steps.failWaiting(runId, last.sequence(), JsonNodeFactory.instance.objectNode().put("decision", "cancelled"),
                                "Run cancelled while waiting", "cancelled");
                    }
                });
                if (runs.finish(runId, RunStatus.CANCELLED, null, "cancel_requested")) {
                    queue.delete(runId);
                } else {
                    runs.requestCancel(runId);
                }
            }
            case RUNNING -> {
                runs.requestCancel(runId);
                queue.expediteIfIdle(runId);
            }
            default -> { /* terminal handled above */ }
        }
        log.info("Cancel requested for run {} (was {})", runId, run.status().value());
        return get(runId);
    }

    /**
     * Replays a failed run from its failed step with the original inputs: a new run is created whose history
     * up to the failed step is copied, and the failed step is re-armed as an in-flight step that keeps its
     * original idempotency key (so a side effect that may have landed before the failure is not duplicated).
     */
    @Transactional
    public RunRecord replay(String runId) {
        RunRecord original = get(runId);
        if (original.status() != RunStatus.FAILED) {
            throw RelayException.conflict("run_not_failed", "Only failed runs can be replayed; run '" + runId + "' is " + original.status().value());
        }
        List<StepRecord> history = steps.findByRunId(runId);
        StepRecord failed = history.stream().filter(s -> s.status() == StepStatus.FAILED).reduce((a, b) -> b).orElse(null);

        String newId = newRunId();
        runs.insert(newId, original.workflowId(), original.workflowVersion(), original.definitionSnapshot(),
                original.triggerType(), original.input(), runId);
        if (failed != null) {
            for (StepRecord s : history) {
                if (s.sequence() >= failed.sequence()) break;
                steps.insert(newId, s.sequence(), s.nodeId(), s.nodeType(), StepStatus.RUNNING, s.idempotencyKey(), null, null, s.resolvedInput());
                steps.markSucceeded(newId, s.sequence(), s.output() == null ? JsonNodeFactory.instance.objectNode() : s.output(),
                        s.attempt(), new StepRepository.AiUsage(s.tokensPrompt(), s.tokensCompletion(), s.aiAttempts()));
                runs.bumpStepCounter(newId, s.nodeId());
                if (s.approvalId() != null) copyApproval(newId, s, runId);
            }
            // Re-arm the failed step as in-flight with its original idempotency key.
            steps.insert(newId, failed.sequence(), failed.nodeId(), failed.nodeType(), StepStatus.RUNNING,
                    failed.idempotencyKey(), null, null, null);
            runs.bumpStepCounter(newId, failed.nodeId());
        }
        queue.enqueue(newId);
        log.info("Run {} replayed from failed step as {}", runId, newId);
        return runs.findById(newId).orElseThrow();
    }

    private void copyApproval(String newRunId, StepRecord step, String originalRunId) {
        approvals.findByRunAndSequence(originalRunId, step.sequence()).ifPresent((ApprovalRecord a) -> {
            if (a.status() != ApprovalStatus.APPROVED) return;
            String id = "apr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            approvals.insert(id, newRunId, a.nodeId(), a.stepSequence(), a.message());
            approvals.decide(id, ApprovalStatus.APPROVED, a.decidedBy(), "Carried over by replay of " + originalRunId);
        });
    }

    static String newRunId() {
        return "run_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }
}
