package com.relay.engine.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relay.engine.NodeExecutionException;
import com.relay.repository.StepRepository;

/**
 * Executes one node type. Implementations are stateless Spring beans discovered by the engine, so adding a
 * new node type means adding a catalog entry and one class (no engine changes).
 *
 * <p>The engine resolves templates, enforces the approval gate, applies retries/backoff and persists the
 * step; an executor only performs the node's own operation.
 */
public interface NodeExecutor {

    /** The catalog node type this executor handles. */
    String type();

    /**
     * @throws NodeExecutionException with {@code retryable=true} for transient faults only
     */
    NodeOutcome execute(NodeInvocation invocation) throws NodeExecutionException;

    /**
     * @param runId the run being executed
     * @param nodeId the node being executed
     * @param params the node's params with templates already resolved
     * @param idempotencyKey stable key for side-effect nodes (same on retries and on resume), else {@code null}
     * @param attempt 1-based attempt number
     */
    record NodeInvocation(String runId, String nodeId, ObjectNode params, String idempotencyKey, int attempt) {}

    record NodeOutcome(JsonNode output, StepRepository.AiUsage ai) {
        public static NodeOutcome of(JsonNode output) { return new NodeOutcome(output, StepRepository.AiUsage.NONE); }
    }
}
