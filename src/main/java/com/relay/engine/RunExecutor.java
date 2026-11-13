package com.relay.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relay.config.RelayProperties;
import com.relay.definition.NodeCatalog;
import com.relay.definition.WorkflowDefinition;
import com.relay.domain.RunRecord;
import com.relay.domain.RunStatus;
import com.relay.domain.StepRecord;
import com.relay.domain.StepStatus;
import com.relay.engine.node.NodeExecutor;
import com.relay.observability.RelayMetrics;
import com.relay.repository.ApprovalRepository;
import com.relay.repository.QueueRepository;
import com.relay.repository.RunRepository;
import com.relay.repository.StepRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The durable execution engine. Given a run id it drives the run forward node by node until the run
 * finishes, parks (approval / delay), or loses its lease.
 *
 * <h2>Crash-recovery design</h2>
 * Resume logic is derived <b>entirely from persisted steps</b>; nothing lives in memory between steps:
 * <ul>
 *   <li>The cursor is the last step row: {@code succeeded} means follow its edge, {@code running} means a
 *       crash happened mid-node so re-execute <em>that same step</em>, {@code waiting} means a delay or
 *       approval is pending.</li>
 *   <li>A step row is inserted ({@code running}) <em>before</em> the node executes and completed in one
 *       transaction after. Completed steps are never executed again.</li>
 *   <li>Side-effect calls carry the key stored on the step row ({@code run:node[:visit]}). The key is the
 *       same on retries and on resume (no attempt number in it), so if the process died after the side
 *       effect landed but before the step was persisted, the replay is absorbed by the downstream
 *       idempotency check. That is the "crash window", and why the key is stable.</li>
 *   <li>The engine, not any prompt, enforces the approval gate, the step cap, the wall-clock cap and the
 *       AI token cap.</li>
 * </ul>
 */
@Component
public class RunExecutor {

    private static final Logger log = LoggerFactory.getLogger(RunExecutor.class);
    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    private static final long MAX_DELAY_SECONDS = 24L * 3600;

    public enum Outcome { FINISHED, WAITING_APPROVAL, DELAYED, LEASE_LOST }

    /** Internal control flow of a single node execution. */
    private enum Flow { CONTINUE, WAITING_APPROVAL, DELAYED, RUN_FINISHED, LEASE_LOST }

    private static final class AbortTransaction extends RuntimeException {
        AbortTransaction() { super(null, null, false, false); }
    }

    private final RunRepository runs;
    private final StepRepository steps;
    private final ApprovalRepository approvals;
    private final QueueRepository queue;
    private final NodeCatalog catalog;
    private final TemplateResolver templates;
    private final Map<String, NodeExecutor> executors;
    private final RetryPolicy retryPolicy;
    private final RelayProperties props;
    private final RelayMetrics metrics;
    private final TransactionTemplate tx;

    public RunExecutor(RunRepository runs, StepRepository steps, ApprovalRepository approvals, QueueRepository queue,
                       NodeCatalog catalog, TemplateResolver templates, List<NodeExecutor> executors,
                       RetryPolicy retryPolicy, RelayProperties props, RelayMetrics metrics,
                       PlatformTransactionManager txManager) {
        this.runs = runs;
        this.steps = steps;
        this.approvals = approvals;
        this.queue = queue;
        this.catalog = catalog;
        this.templates = templates;
        this.executors = executors.stream().collect(Collectors.toMap(NodeExecutor::type, Function.identity()));
        this.retryPolicy = retryPolicy;
        this.props = props;
        this.metrics = metrics;
        this.tx = new TransactionTemplate(txManager);
    }

    public Outcome execute(String runId, WorkerLease lease) {
        MDC.put("runId", runId);
        try {
            return drive(runId, lease);
        } finally {
            MDC.remove("runId");
            MDC.remove("nodeId");
        }
    }

    // ------------------------------------------------------------------ main loop

    private Outcome drive(String runId, WorkerLease lease) {
        RunRecord run = runs.findById(runId).orElse(null);
        if (run == null || run.status().isTerminal()) return Outcome.FINISHED;
        if (run.status() == RunStatus.WAITING_APPROVAL) return Outcome.WAITING_APPROVAL;
        if (!runs.markRunning(runId)) return Outcome.FINISHED; // raced with cancel

        WorkflowDefinition def = WorkflowDefinition.parse(run.definitionSnapshot(), props.getEngine().getDefaultMaxSteps());

        while (true) {
            if (!lease.isHeld()) return Outcome.LEASE_LOST;
            run = runs.findById(runId).orElseThrow();
            if (run.status().isTerminal()) return Outcome.FINISHED;
            if (run.cancelRequested()) {
                cancelRun(run, "cancel_requested");
                return Outcome.FINISHED;
            }

            // ---- cursor: where are we, derived only from persisted steps
            Optional<StepRecord> lastOpt = steps.findLast(runId);
            String nodeId;
            StepRecord resume = null;
            if (lastOpt.isEmpty()) {
                nodeId = def.entry();
            } else {
                StepRecord last = lastOpt.get();
                switch (last.status()) {
                    case SUCCEEDED -> {
                        WorkflowDefinition.Node n = def.node(last.nodeId());
                        if (n == null) {
                            failRun(run, "node_not_found", "Node '" + last.nodeId() + "' no longer exists in the run snapshot", last.nodeId(), last.sequence());
                            return Outcome.FINISHED;
                        }
                        boolean cond = n.isCondition() && last.output() != null && last.output().path("result").asBoolean(false);
                        String succ = def.successor(n, cond);
                        if (succ == null) {
                            finishRun(run, RunStatus.SUCCEEDED, null);
                            return Outcome.FINISHED;
                        }
                        nodeId = succ;
                    }
                    case RUNNING -> {
                        resume = last;
                        nodeId = last.nodeId();
                        metrics.runResumed();
                        log.info("Resuming in-flight step seq={} node={} after interruption (idempotency key {})",
                                last.sequence(), last.nodeId(), last.idempotencyKey());
                    }
                    case WAITING -> {
                        if ("delay".equals(last.nodeType())) {
                            Instant at = last.resumeAt();
                            if (at != null && at.isAfter(Instant.now())) {
                                queue.reschedule(runId, at);
                                return Outcome.DELAYED;
                            }
                            steps.completeWaiting(runId, last.sequence(), F.objectNode());
                            continue;
                        }
                        return Outcome.WAITING_APPROVAL;
                    }
                    default -> {
                        failRun(run, last.errorCode() == null ? "step_failed" : last.errorCode(),
                                last.error() == null ? "A previous step failed" : last.error(), last.nodeId(), last.sequence());
                        return Outcome.FINISHED;
                    }
                }
            }

            WorkflowDefinition.Node node = def.node(nodeId);
            if (node == null) {
                failRun(run, "node_not_found", "Node '" + nodeId + "' does not exist in the run snapshot", nodeId, 0);
                return Outcome.FINISHED;
            }
            MDC.put("nodeId", nodeId);

            // ---- caps, enforced by the engine regardless of any model output
            Optional<String[]> cap = checkCaps(run, def, node, resume != null);
            if (cap.isPresent()) {
                failRun(run, cap.get()[0], cap.get()[1], nodeId, run.stepsExecuted());
                return Outcome.FINISHED;
            }

            Flow flow = switch (node.type()) {
                case "approval" -> executeApproval(run, node);
                case "delay" -> executeDelay(run, node, resume);
                default -> executeStandard(run, def, node, resume, lease);
            };
            switch (flow) {
                case CONTINUE -> { /* next iteration */ }
                case WAITING_APPROVAL -> { return Outcome.WAITING_APPROVAL; }
                case DELAYED -> { return Outcome.DELAYED; }
                case RUN_FINISHED -> { return Outcome.FINISHED; }
                case LEASE_LOST -> { return Outcome.LEASE_LOST; }
            }
        }
    }

    /** @return {code, message} if a cap is exceeded */
    private Optional<String[]> checkCaps(RunRecord run, WorkflowDefinition def, WorkflowDefinition.Node node, boolean resuming) {
        WorkflowDefinition.Limits lim = def.limits();
        if (!resuming && run.stepsExecuted() >= lim.maxSteps()) {
            return Optional.of(new String[] {"max_steps_exceeded",
                    "max_steps cap (" + lim.maxSteps() + ") exceeded: the run already executed " + run.stepsExecuted()
                            + " nodes; stopped before executing node '" + node.id() + "'"});
        }
        if (lim.timeoutSeconds() > 0 && run.startedAt() != null) {
            double waited = approvals.totalWaitSeconds(run.id());
            double active = Duration.between(run.startedAt(), Instant.now()).toMillis() / 1000.0 - waited;
            if (active > lim.timeoutSeconds()) {
                return Optional.of(new String[] {"timeout_exceeded",
                        "timeout_seconds cap (" + lim.timeoutSeconds() + "s, excluding time spent waiting for approvals) exceeded"});
            }
        }
        if ("ai".equals(node.type()) && lim.maxAiTokens() > 0 && run.aiTokensUsed() >= lim.maxAiTokens()) {
            return Optional.of(new String[] {"max_ai_tokens_exceeded",
                    "max_ai_tokens cap (" + lim.maxAiTokens() + ") exceeded: the run already used " + run.aiTokensUsed() + " tokens"});
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------ standard nodes

    private Flow executeStandard(RunRecord run, WorkflowDefinition def, WorkflowDefinition.Node node,
                                 StepRecord resume, WorkerLease lease) {
        String runId = run.id();
        NodeCatalog.NodeType type = catalog.nodeType(node.type()).orElse(null);
        NodeExecutor executor = executors.get(node.type());
        if (type == null || executor == null) {
            failRun(run, "invalid_node_type", "No executor for node type '" + node.type() + "'", node.id(), run.stepsExecuted());
            return Flow.RUN_FINISHED;
        }

        int sequence;
        String key;
        if (resume != null) {
            sequence = resume.sequence();
            key = resume.idempotencyKey();
        } else {
            sequence = steps.nextSequence(runId);
            key = type.sideEffect() ? idempotencyKey(runId, node.id(), steps.nextVisit(runId, node.id())) : null;
            try {
                int seq = sequence;
                tx.executeWithoutResult(s -> {
                    steps.insert(runId, seq, node.id(), node.type(), StepStatus.RUNNING, key, null, null, null);
                    runs.bumpStepCounter(runId, node.id());
                });
            } catch (DuplicateKeyException e) {
                log.warn("Step seq={} already owned by another executor; yielding", sequence);
                return Flow.LEASE_LOST;
            }
        }

        // ---- resolve templates (only params the catalog marks templatable)
        ObjectNode resolved;
        try {
            resolved = resolveParams(run, node, type);
        } catch (TemplateException e) {
            return failStep(run, node, sequence, 1, "template_error", e.getMessage(), StepRepository.AiUsage.NONE);
        }
        steps.updateResolvedInput(runId, sequence, redactForTrace(node, resolved));

        // ---- the approval gate: a database check, independent of anything a model said
        if (type.requiresApproval() && !approvals.existsApproved(runId)) {
            return failStep(run, node, sequence, 1, "approval_required",
                    "Node '" + node.id() + "' (" + node.type() + ") is sensitive and requires an approved approval node earlier in "
                            + "the run, but no approved approval exists. Execution refused.",
                    StepRepository.AiUsage.NONE);
        }

        // ---- execute with retries
        RetryPolicy.Settings rs = retryPolicy.settingsFor(node);
        int attempt = resume != null ? Math.min(resume.attempt() + 1, rs.maxAttempts()) : 1;
        long started = System.currentTimeMillis();
        while (true) {
            if (!lease.isHeld()) return Flow.LEASE_LOST;
            steps.updateAttempt(runId, sequence, attempt);
            NodeExecutor.NodeOutcome outcome;
            try {
                outcome = executor.execute(new NodeExecutor.NodeInvocation(runId, node.id(), resolved, key, attempt));
            } catch (NodeExecutionException e) {
                if (!e.isRetryable() || attempt >= rs.maxAttempts()) {
                    metrics.stepFinished(node.type(), "failed", System.currentTimeMillis() - started);
                    return failStep(run, node, sequence, attempt, e.code(), e.getMessage(), e.usage());
                }
                long wait = retryPolicy.backoffMillis(rs, attempt);
                log.warn("Node {} attempt {}/{} failed ({}); retrying in {} ms", node.id(), attempt, rs.maxAttempts(), e.code(), wait);
                metrics.stepRetried(node.type());
                if (!sleep(wait, lease)) return Flow.LEASE_LOST;
                if (runs.findById(runId).map(RunRecord::cancelRequested).orElse(false)) {
                    return Flow.CONTINUE; // the top of the loop turns the cancel request into a cancelled run
                }
                attempt++;
                continue;
            } catch (RuntimeException e) {
                log.error("Unexpected error executing node {} of run {}", node.id(), runId, e);
                metrics.stepFinished(node.type(), "failed", System.currentTimeMillis() - started);
                return failStep(run, node, sequence, attempt, "internal_error",
                        "Internal error while executing the node (" + e.getClass().getSimpleName() + ")", StepRepository.AiUsage.NONE);
            }

            // Persistence is deliberately outside the try block above: a database failure here must not be
            // mistaken for a node failure. It propagates to the worker, the lease expires, and the run is
            // resumed (the side effect, if it landed, is absorbed by its idempotency key).
            final int finalAttempt = attempt;
            tx.executeWithoutResult(s -> {
                steps.markSucceeded(runId, sequence, outcome.output(), finalAttempt, outcome.ai());
                int tokens = (outcome.ai().promptTokens() == null ? 0 : outcome.ai().promptTokens())
                        + (outcome.ai().completionTokens() == null ? 0 : outcome.ai().completionTokens());
                if (tokens > 0) runs.addAiTokens(runId, tokens);
            });
            if (outcome.ai().promptTokens() != null) {
                metrics.aiTokens(outcome.ai().promptTokens(), outcome.ai().completionTokens() == null ? 0 : outcome.ai().completionTokens());
            }
            metrics.stepFinished(node.type(), "succeeded", System.currentTimeMillis() - started);
            return Flow.CONTINUE;
        }
    }

    private ObjectNode resolveParams(RunRecord run, WorkflowDefinition.Node node, NodeCatalog.NodeType type) {
        TemplateContext ctx = context(run);
        ObjectNode out = F.objectNode();
        node.params().fields().forEachRemaining(e -> {
            NodeCatalog.ParamSpec spec = type.params().get(e.getKey());
            out.set(e.getKey(), spec != null && spec.templatable() ? templates.resolve(e.getValue(), ctx) : e.getValue());
        });
        return out;
    }

    private TemplateContext context(RunRecord run) {
        Map<String, JsonNode> outputs = new HashMap<>();
        for (StepRecord s : steps.findByRunId(run.id())) {
            if (s.status() == StepStatus.SUCCEEDED && s.output() != null) outputs.put(s.nodeId(), s.output());
        }
        return new TemplateContext(run.id(), run.triggerType(), run.input(), outputs);
    }

    /** Trace hygiene: never persist credential-like header values; optionally never persist AI prompts. */
    private JsonNode redactForTrace(WorkflowDefinition.Node node, ObjectNode resolved) {
        ObjectNode copy = resolved.deepCopy();
        if ("http_request".equals(node.type()) && copy.path("headers").isObject()) {
            ObjectNode headers = (ObjectNode) copy.get("headers");
            headers.fieldNames().forEachRemaining(name -> {
                String n = name.toLowerCase();
                if (n.contains("authorization") || n.contains("token") || n.contains("secret") || n.contains("key")
                        || n.contains("password") || n.contains("cookie")) {
                    headers.put(name, "***REDACTED***");
                }
            });
        }
        if ("ai".equals(node.type()) && !props.getEngine().isTraceStoreAiPrompts() && copy.has("prompt")) {
            String prompt = copy.get("prompt").asText();
            ObjectNode r = F.objectNode();
            r.put("redacted", true);
            r.put("chars", prompt.length());
            r.put("sha256", sha256(prompt));
            copy.set("prompt", r);
        }
        return copy;
    }

    // ------------------------------------------------------------------ approval / delay nodes

    private Flow executeApproval(RunRecord run, WorkflowDefinition.Node node) {
        String runId = run.id();
        int sequence = steps.nextSequence(runId);
        String message;
        try {
            message = templates.resolveToString(node.params().path("message").asText(), context(run));
        } catch (TemplateException e) {
            return failNewStep(run, node, sequence, "template_error", e.getMessage());
        }
        String approvalId = "apr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        ObjectNode input = F.objectNode();
        input.put("message", message);
        try {
            tx.executeWithoutResult(s -> {
                steps.insert(runId, sequence, node.id(), node.type(), StepStatus.WAITING, null, null, approvalId, input);
                approvals.insert(approvalId, runId, node.id(), sequence, message);
                runs.bumpStepCounter(runId, node.id());
                if (!runs.transition(runId, RunStatus.RUNNING, RunStatus.WAITING_APPROVAL)) throw new AbortTransaction();
                queue.delete(runId);
            });
        } catch (AbortTransaction e) {
            return Flow.RUN_FINISHED; // the run was cancelled concurrently
        } catch (DuplicateKeyException e) {
            return Flow.LEASE_LOST;
        }
        log.info("Run parked at approval {} (node {})", approvalId, node.id());
        return Flow.WAITING_APPROVAL;
    }

    private Flow executeDelay(RunRecord run, WorkflowDefinition.Node node, StepRecord resume) {
        String runId = run.id();
        if (resume != null) { // zero-length delay interrupted mid-step
            steps.markSucceeded(runId, resume.sequence(), F.objectNode(), 1, StepRepository.AiUsage.NONE);
            return Flow.CONTINUE;
        }
        JsonNode secondsNode = node.params().get("seconds");
        int sequence = steps.nextSequence(runId);
        if (secondsNode == null || !secondsNode.isNumber() || secondsNode.asDouble() < 0 || secondsNode.asDouble() > MAX_DELAY_SECONDS) {
            return failNewStep(run, node, sequence, "invalid_param_value",
                    "delay 'seconds' must be a number between 0 and " + MAX_DELAY_SECONDS);
        }
        long millis = (long) (secondsNode.asDouble() * 1000);
        ObjectNode input = F.objectNode();
        input.set("seconds", secondsNode);
        try {
            if (millis == 0) {
                tx.executeWithoutResult(s -> {
                    steps.insert(runId, sequence, node.id(), node.type(), StepStatus.RUNNING, null, null, null, input);
                    runs.bumpStepCounter(runId, node.id());
                    steps.markSucceeded(runId, sequence, F.objectNode(), 1, StepRepository.AiUsage.NONE);
                });
                return Flow.CONTINUE;
            }
            Instant resumeAt = Instant.now().plusMillis(millis);
            // Persist the wake-up time and release the job in one transaction: nothing sleeps in memory,
            // so a crash during the delay loses nothing and the run wakes at resume_at on any worker.
            tx.executeWithoutResult(s -> {
                steps.insert(runId, sequence, node.id(), node.type(), StepStatus.WAITING, null, resumeAt, null, input);
                runs.bumpStepCounter(runId, node.id());
                queue.reschedule(runId, resumeAt);
            });
        } catch (DuplicateKeyException e) {
            return Flow.LEASE_LOST;
        }
        return Flow.DELAYED;
    }

    // ------------------------------------------------------------------ terminal transitions

    private Flow failStep(RunRecord run, WorkflowDefinition.Node node, int sequence, int attempt,
                          String code, String message, StepRepository.AiUsage usage) {
        tx.executeWithoutResult(s -> {
            steps.markFailed(run.id(), sequence, message, code, attempt, usage);
            int tokens = (usage.promptTokens() == null ? 0 : usage.promptTokens()) + (usage.completionTokens() == null ? 0 : usage.completionTokens());
            if (tokens > 0) runs.addAiTokens(run.id(), tokens);
        });
        failRun(run, code, message, node.id(), sequence);
        return Flow.RUN_FINISHED;
    }

    /** Records a failure for a node that has no step row yet (so the trace still shows what went wrong). */
    private Flow failNewStep(RunRecord run, WorkflowDefinition.Node node, int sequence, String code, String message) {
        try {
            tx.executeWithoutResult(s -> {
                steps.insert(run.id(), sequence, node.id(), node.type(), StepStatus.RUNNING, null, null, null, null);
                runs.bumpStepCounter(run.id(), node.id());
                steps.markFailed(run.id(), sequence, message, code, 1, StepRepository.AiUsage.NONE);
            });
        } catch (DuplicateKeyException e) {
            return Flow.LEASE_LOST;
        }
        failRun(run, code, message, node.id(), sequence);
        return Flow.RUN_FINISHED;
    }

    private void failRun(RunRecord run, String code, String message, String nodeId, int sequence) {
        ObjectNode err = F.objectNode();
        err.put("code", code);
        err.put("message", message);
        if (nodeId != null) err.put("node_id", nodeId);
        err.put("step_sequence", sequence);
        if (code.endsWith("_exceeded")) err.put("cap", code.substring(0, code.length() - "_exceeded".length()));
        if (runs.finish(run.id(), RunStatus.FAILED, err, null)) {
            metrics.runFinished(RunStatus.FAILED);
            log.warn("Run {} failed: [{}] {}", run.id(), code, message);
        }
    }

    private void finishRun(RunRecord run, RunStatus status, JsonNode error) {
        if (runs.finish(run.id(), status, error, null)) {
            metrics.runFinished(status);
            log.info("Run {} {}", run.id(), status.value());
        }
    }

    private void cancelRun(RunRecord run, String reason) {
        tx.executeWithoutResult(s -> {
            approvals.cancelPendingForRun(run.id());
            steps.findLast(run.id()).ifPresent(last -> {
                if (last.status() == StepStatus.RUNNING) {
                    steps.markFailed(run.id(), last.sequence(), "Cancelled before completion", "cancelled", last.attempt(), StepRepository.AiUsage.NONE);
                }
            });
            if (runs.finish(run.id(), RunStatus.CANCELLED, null, reason)) metrics.runFinished(RunStatus.CANCELLED);
        });
        log.info("Run {} cancelled ({})", run.id(), reason);
    }

    // ------------------------------------------------------------------ helpers

    /** Stable across retries and resumes: no attempt number. Loop iterations (visit > 1) are distinct effects. */
    static String idempotencyKey(String runId, String nodeId, int visit) {
        return visit <= 1 ? runId + ":" + nodeId : runId + ":" + nodeId + ":" + visit;
    }

    /** Sleeps in short slices so that a revoked lease or a shutdown interrupts the wait. @return false if interrupted/lost */
    private boolean sleep(long millis, WorkerLease lease) {
        long end = System.nanoTime() + millis * 1_000_000L;
        while (true) {
            long remainingMs = (end - System.nanoTime()) / 1_000_000L;
            if (remainingMs <= 0) return lease.isHeld();
            if (!lease.isHeld()) return false;
            try {
                Thread.sleep(Math.min(200, remainingMs + 1));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
