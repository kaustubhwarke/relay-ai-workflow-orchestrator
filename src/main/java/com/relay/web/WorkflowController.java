package com.relay.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.relay.definition.DefinitionValidator;
import com.relay.service.RunService;
import com.relay.service.WorkflowService;
import com.relay.web.dto.Views;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@ConditionalOnProperty(name = "relay.api.enabled", havingValue = "true", matchIfMissing = true)
@RestController
@RequestMapping("/workflows")
public class WorkflowController {

    public record TriggerRequest(JsonNode input) {}

    private final WorkflowService workflows;
    private final RunService runs;

    public WorkflowController(WorkflowService workflows, RunService runs) {
        this.workflows = workflows;
        this.runs = runs;
    }

    /** Creates a Draft. Catalog validation happens at publish; the response tells you what is wrong early via /validate. */
    @PostMapping
    public ResponseEntity<Views.WorkflowDetail> create(@RequestBody JsonNode definition) {
        return ResponseEntity.status(HttpStatus.CREATED).body(Views.WorkflowDetail.of(workflows.create(definition), null));
    }

    @GetMapping
    public List<Views.WorkflowSummary> list() {
        return workflows.list().stream().map(Views.WorkflowSummary::of).toList();
    }

    @GetMapping("/{id}")
    public Views.WorkflowDetail get(@PathVariable String id) {
        return Views.WorkflowDetail.of(workflows.get(id), null);
    }

    @PutMapping("/{id}")
    public Views.WorkflowDetail update(@PathVariable String id, @RequestBody JsonNode definition) {
        return Views.WorkflowDetail.of(workflows.update(id, definition), null);
    }

    @PostMapping("/{id}/publish")
    public Views.WorkflowDetail publish(@PathVariable String id) {
        WorkflowService.PublishResult r = workflows.publish(id);
        return Views.WorkflowDetail.of(r.workflow(), r.warnings());
    }

    @GetMapping("/{id}/versions")
    public List<Views.VersionView> versions(@PathVariable String id) {
        return workflows.versions(id).stream().map(Views.VersionView::of).toList();
    }

    /** Dry-run validation: returns every problem and warning without storing anything. */
    @PostMapping("/validate")
    public Map<String, Object> validate(@RequestBody JsonNode definition) {
        DefinitionValidator.Result r = workflows.validateOnly(definition);
        return Map.of(
                "valid", r.valid(),
                "errors", r.errors().stream().map(i -> Map.of("code", i.code(), "message", i.message())).toList(),
                "warnings", r.warnings());
    }

    /** Manual trigger: enqueues and returns immediately (202); the worker executes the run. */
    @PostMapping("/{id}/trigger")
    public ResponseEntity<Views.TriggerResponse> trigger(@PathVariable String id, @RequestBody(required = false) TriggerRequest body) {
        var run = runs.triggerManual(id, body == null ? null : body.input());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Views.TriggerResponse.of(run));
    }
}
