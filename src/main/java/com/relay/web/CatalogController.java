package com.relay.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.relay.definition.NodeCatalog;
import com.relay.domain.RunStatus;
import com.relay.repository.ApprovalRepository;
import com.relay.repository.QueueRepository;
import com.relay.repository.RunRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only reference data and platform counters used by the console. */
@ConditionalOnProperty(name = "relay.api.enabled", havingValue = "true", matchIfMissing = true)
@RestController
public class CatalogController {

    private final NodeCatalog catalog;
    private final RunRepository runs;
    private final ApprovalRepository approvals;
    private final QueueRepository queue;

    public CatalogController(NodeCatalog catalog, RunRepository runs, ApprovalRepository approvals, QueueRepository queue) {
        this.catalog = catalog;
        this.runs = runs;
        this.approvals = approvals;
        this.queue = queue;
    }

    @GetMapping("/catalog")
    public JsonNode catalog() {
        return catalog.raw();
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> runsByStatus = new LinkedHashMap<>();
        for (RunStatus s : RunStatus.values()) runsByStatus.put(s.value(), runs.countByStatus(s));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runs", runsByStatus);
        out.put("pending_approvals", approvals.countPending());
        out.put("queue_depth", queue.depth());
        out.put("queue_ready", queue.readyDepth());
        return out;
    }
}
