package com.relay.service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relay.domain.ApprovalRecord;
import com.relay.domain.ApprovalStatus;
import com.relay.domain.RelayException;
import com.relay.domain.RunStatus;
import com.relay.repository.ApprovalRepository;
import com.relay.repository.QueueRepository;
import com.relay.repository.RunRepository;
import com.relay.repository.StepRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Human decisions on approval nodes. This service is the <em>only</em> writer of approved approval rows,
 * which is what the engine's gate checks. Nothing a model outputs can reach it.
 */
@Service
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);

    private final ApprovalRepository approvals;
    private final RunRepository runs;
    private final StepRepository steps;
    private final QueueRepository queue;

    public ApprovalService(ApprovalRepository approvals, RunRepository runs, StepRepository steps, QueueRepository queue) {
        this.approvals = approvals;
        this.runs = runs;
        this.steps = steps;
        this.queue = queue;
    }

    public ApprovalRecord get(String id) {
        return approvals.findById(id).orElseThrow(() -> RelayException.notFound("Approval", id));
    }

    public List<ApprovalRecord> list(String status, String runId, int limit, int offset) {
        if (status != null) {
            try {
                ApprovalStatus.fromValue(status);
            } catch (IllegalArgumentException e) {
                throw RelayException.badRequest("invalid_status", "Unknown approval status '" + status + "'");
            }
        }
        return approvals.list(status, runId, Math.max(1, Math.min(limit, 200)), Math.max(0, offset));
    }

    /** Approves: records the decision and decider, completes the waiting step, and re-queues the run. */
    @Transactional
    public ApprovalRecord approve(String id, String decidedBy, String comment) {
        ApprovalRecord a = decide(id, ApprovalStatus.APPROVED, decidedBy, comment);
        ObjectNode output = decisionOutput("approved", decidedBy, comment);
        steps.completeWaiting(a.runId(), a.stepSequence(), output);
        if (!runs.transition(a.runId(), RunStatus.WAITING_APPROVAL, RunStatus.QUEUED)) {
            // The run was cancelled in the meantime; roll the whole decision back.
            throw RelayException.conflict("run_not_waiting", "Run '" + a.runId() + "' is no longer waiting for approval");
        }
        queue.enqueue(a.runId());
        log.info("Approval {} approved by {}; run {} re-queued", id, decidedBy, a.runId());
        return get(id);
    }

    /** Rejects: records the decision and ends the run cleanly as {@code cancelled}. */
    @Transactional
    public ApprovalRecord reject(String id, String decidedBy, String comment) {
        ApprovalRecord a = decide(id, ApprovalStatus.REJECTED, decidedBy, comment);
        ObjectNode output = decisionOutput("rejected", decidedBy, comment);
        steps.failWaiting(a.runId(), a.stepSequence(), output, "Approval rejected by " + decidedBy, "approval_rejected");
        if (!runs.finish(a.runId(), RunStatus.CANCELLED, null, "approval_rejected")) {
            throw RelayException.conflict("run_not_waiting", "Run '" + a.runId() + "' is no longer waiting for approval");
        }
        queue.delete(a.runId());
        log.info("Approval {} rejected by {}; run {} cancelled", id, decidedBy, a.runId());
        return get(id);
    }

    private ApprovalRecord decide(String id, ApprovalStatus decision, String decidedBy, String comment) {
        ApprovalRecord current = get(id);
        if (!approvals.decide(id, decision, decidedBy, comment)) {
            throw RelayException.conflict("approval_already_decided",
                    "Approval '" + id + "' is already " + current.status().value() + " and cannot be changed");
        }
        return current;
    }

    private static ObjectNode decisionOutput(String decision, String decidedBy, String comment) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("decision", decision);
        out.put("decided_by", decidedBy);
        if (comment != null && !comment.isBlank()) out.put("comment", comment);
        return out;
    }
}
