package com.relay.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.relay.security.RelayPrincipal;
import com.relay.service.ApprovalService;
import com.relay.web.dto.Views;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@ConditionalOnProperty(name = "relay.api.enabled", havingValue = "true", matchIfMissing = true)
@RestController
@RequestMapping("/approvals")
public class ApprovalController {

    public record DecisionRequest(String comment) {}

    private final ApprovalService approvals;

    public ApprovalController(ApprovalService approvals) {
        this.approvals = approvals;
    }

    @GetMapping
    public List<Views.ApprovalView> list(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "runId", required = false) String runId,
            @RequestParam(name = "run_id", required = false) String runIdSnake,
            @RequestParam(name = "limit", defaultValue = "100") int limit,
            @RequestParam(name = "offset", defaultValue = "0") int offset) {
        int pageSize = Math.max(1, Math.min(limit, 200));
        return approvals.list(status, runId != null ? runId : runIdSnake, pageSize, Math.max(0, offset))
                .stream().map(Views.ApprovalView::of).toList();
    }

    @GetMapping("/{id}")
    public Views.ApprovalView get(@PathVariable String id) {
        return Views.ApprovalView.of(approvals.get(id));
    }

    @PostMapping("/{id}/approve")
    public Views.ApprovalView approve(@PathVariable String id, @RequestBody(required = false) DecisionRequest body, Authentication auth) {
        return Views.ApprovalView.of(approvals.approve(id, RelayPrincipal.nameOf(auth), body == null ? null : body.comment()));
    }

    @PostMapping("/{id}/reject")
    public Views.ApprovalView reject(@PathVariable String id, @RequestBody(required = false) DecisionRequest body, Authentication auth) {
        return Views.ApprovalView.of(approvals.reject(id, RelayPrincipal.nameOf(auth), body == null ? null : body.comment()));
    }
}
