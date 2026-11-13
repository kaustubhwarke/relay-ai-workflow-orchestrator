package com.relay.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.relay.service.RunService;
import com.relay.web.dto.Views;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@ConditionalOnProperty(name = "relay.api.enabled", havingValue = "true", matchIfMissing = true)
@RestController
@RequestMapping("/runs")
public class RunController {

    private final RunService runs;

    public RunController(RunService runs) {
        this.runs = runs;
    }

    @GetMapping
    public Views.Page<Views.RunSummary> list(
            @RequestParam(name = "workflowId", required = false) String workflowId,
            @RequestParam(name = "workflow_id", required = false) String workflowIdSnake,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "limit", defaultValue = "50") int limit,
            @RequestParam(name = "offset", defaultValue = "0") int offset) {
        String wf = workflowId != null ? workflowId : workflowIdSnake;
        int pageSize = Math.max(1, Math.min(limit, 200));
        int start = Math.max(0, offset);
        var items = runs.list(wf, status, pageSize, start).stream().map(Views.RunSummary::of).toList();
        return new Views.Page<>(items, runs.count(wf, status), pageSize, start);
    }

    /** The full trace: every step with resolved input, output, attempts, timing and AI token usage. */
    @GetMapping("/{runId}")
    public Views.RunDetail get(@PathVariable String runId) {
        return Views.RunDetail.of(runs.get(runId), runs.steps(runId));
    }

    @PostMapping("/{runId}/cancel")
    public Views.RunDetail cancel(@PathVariable String runId) {
        runs.cancel(runId);
        return Views.RunDetail.of(runs.get(runId), runs.steps(runId));
    }

    /** Re-runs a failed run from its failed step with the original inputs. */
    @PostMapping("/{runId}/replay")
    public ResponseEntity<Views.TriggerResponse> replay(@PathVariable String runId) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Views.TriggerResponse.of(runs.replay(runId)));
    }
}
