package com.relay.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.relay.domain.RelayException;
import com.relay.service.RunService;
import com.relay.web.dto.Views;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Webhook trigger. Unauthenticated apart from the per-workflow secret in {@code X-Relay-Secret}. The body is
 * <b>untrusted input</b>: it is stored as the run input and only ever reaches nodes as template data.
 */
@ConditionalOnProperty(name = "relay.api.enabled", havingValue = "true", matchIfMissing = true)
@RestController
@RequestMapping("/hooks")
public class HookController {

    private static final int MAX_BODY_BYTES = 256 * 1024;

    private final RunService runs;
    private final ObjectMapper mapper;

    public HookController(RunService runs, ObjectMapper mapper) {
        this.runs = runs;
        this.mapper = mapper;
    }

    @PostMapping("/{workflowId}")
    public ResponseEntity<Views.TriggerResponse> hook(
            @PathVariable String workflowId,
            @RequestHeader(name = "X-Relay-Secret", required = false) String secret,
            @RequestBody(required = false) byte[] rawBody) {
        if (rawBody != null && rawBody.length > MAX_BODY_BYTES) {
            throw new RelayException(HttpStatus.PAYLOAD_TOO_LARGE, "payload_too_large", "Webhook body exceeds " + MAX_BODY_BYTES + " bytes");
        }
        JsonNode body;
        try {
            body = rawBody == null || rawBody.length == 0 ? mapper.createObjectNode() : mapper.readTree(rawBody);
            if (body == null || body.isMissingNode()) body = mapper.createObjectNode(); // whitespace-only body
        } catch (java.io.IOException e) {
            throw RelayException.badRequest("invalid_json", "Webhook body is not valid JSON");
        }
        var run = runs.triggerWebhook(workflowId, secret, body);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Views.TriggerResponse.of(run));
    }
}
