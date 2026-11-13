package com.relay.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relay.config.RelayProperties;
import com.relay.definition.DefinitionValidator;
import com.relay.domain.RelayException;
import com.relay.domain.WorkflowRecord;
import com.relay.domain.WorkflowVersionRecord;
import com.relay.repository.ScheduleRepository;
import com.relay.repository.WorkflowRepository;
import com.relay.security.SecretHasher;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Workflow lifecycle: draft (editable) -> publish (frozen, numbered, runnable).
 *
 * <p>The working copy is always editable; publishing validates it and appends an immutable version. Runs
 * execute a snapshot of a published version, so editing or republishing never changes an in-flight or
 * historical run. Webhook secrets are hashed on the way in and never returned.
 */
@Service
public class WorkflowService {

    public static final String REDACTED = "[REDACTED]";
    private static final Logger log = LoggerFactory.getLogger(WorkflowService.class);

    public record PublishResult(WorkflowRecord workflow, int version, List<String> warnings) {}

    private final WorkflowRepository workflows;
    private final ScheduleRepository schedules;
    private final DefinitionValidator validator;
    private final RelayProperties props;

    public WorkflowService(WorkflowRepository workflows, ScheduleRepository schedules,
                           DefinitionValidator validator, RelayProperties props) {
        this.workflows = workflows;
        this.schedules = schedules;
        this.validator = validator;
        this.props = props;
    }

    @Transactional
    public WorkflowRecord create(JsonNode definition) {
        requireBasicShape(definition);
        String id = definition.get("id").asText();
        if (workflows.exists(id)) {
            throw RelayException.conflict("workflow_exists", "Workflow '" + id + "' already exists");
        }
        ObjectNode stored = definition.deepCopy();
        String hash = extractAndRedactSecret(stored, null);
        try {
            workflows.insert(id, stored.path("name").asText(), textOrNull(stored, "description"), stored, hash);
        } catch (DuplicateKeyException e) {
            throw RelayException.conflict("workflow_exists", "Workflow '" + id + "' already exists");
        }
        log.info("Workflow {} created as draft", id);
        return workflows.findById(id).orElseThrow();
    }

    @Transactional
    public WorkflowRecord update(String id, JsonNode definition) {
        WorkflowRecord existing = workflows.findByIdForUpdate(id).orElseThrow(() -> RelayException.notFound("Workflow", id));
        requireBasicShape(definition);
        if (!id.equals(definition.get("id").asText())) {
            throw RelayException.badRequest("id_mismatch", "The 'id' in the body must match the id in the URL ('" + id + "')");
        }
        ObjectNode stored = definition.deepCopy();
        String hash = extractAndRedactSecret(stored, existing.webhookSecretHash());
        workflows.updateWorkingCopy(id, stored.path("name").asText(), textOrNull(stored, "description"), stored, hash);
        log.info("Workflow {} working copy updated (status draft, published version {} unchanged)", id, existing.currentVersion());
        return workflows.findById(id).orElseThrow();
    }

    public WorkflowRecord get(String id) {
        return workflows.findById(id).orElseThrow(() -> RelayException.notFound("Workflow", id));
    }

    public List<WorkflowRecord> list() {
        return workflows.findAll();
    }

    public List<WorkflowVersionRecord> versions(String id) {
        get(id);
        return workflows.listVersions(id);
    }

    /** Validates and appends an immutable published version. */
    @Transactional
    public PublishResult publish(String id) {
        WorkflowRecord wf = workflows.findByIdForUpdate(id).orElseThrow(() -> RelayException.notFound("Workflow", id));
        DefinitionValidator.Result result = validator.validate(wf.definition(), wf.webhookSecretHash() != null);
        if (!result.valid()) {
            throw new InvalidDefinitionException(result.errors());
        }
        int version = wf.currentVersion() + 1;
        workflows.insertVersion(id, version, wf.definition(), wf.webhookSecretHash());
        workflows.markPublished(id, version);
        registerSchedule(id, wf.definition());
        log.info("Workflow {} published as version {}", id, version);
        return new PublishResult(workflows.findById(id).orElseThrow(), version, result.warnings());
    }

    /** Dry-run validation (POST /workflows/validate): nothing is stored. */
    public DefinitionValidator.Result validateOnly(JsonNode definition) {
        // the redaction marker is a placeholder, not a usable secret: validation must agree with publish
        boolean hasSecret = definition != null && definition.path("trigger").path("secret").isTextual()
                && !definition.path("trigger").path("secret").asText().isBlank()
                && !REDACTED.equals(definition.path("trigger").path("secret").asText());
        return validator.validate(definition, hasSecret);
    }

    private void registerSchedule(String workflowId, JsonNode definition) {
        JsonNode trigger = definition.path("trigger");
        if ("schedule".equals(trigger.path("type").asText())) {
            String cron = trigger.path("cron").asText();
            schedules.upsert(workflowId, cron, nextFire(cron, Instant.now()));
        } else {
            schedules.disable(workflowId);
        }
    }

    /** Next fire time strictly after {@code after}, in UTC, with minute granularity. */
    public static Instant nextFire(String cron5, Instant after) {
        ZonedDateTime next = CronExpression.parse("0 " + cron5.trim()).next(ZonedDateTime.ofInstant(after, ZoneOffset.UTC));
        if (next == null) throw new IllegalArgumentException("Cron expression has no future fire time");
        return next.toInstant();
    }

    // ------------------------------------------------------------------ helpers

    private void requireBasicShape(JsonNode d) {
        if (d == null || !d.isObject() || !d.path("id").isTextual() || d.path("id").asText().isBlank()) {
            throw new InvalidDefinitionException(List.of(new DefinitionValidator.Issue(
                    "invalid_definition", "Workflow definition must be a JSON object with a text 'id'", null)));
        }
        if (!DefinitionValidator.isValidId(d.get("id").asText())) {
            throw new InvalidDefinitionException(List.of(new DefinitionValidator.Issue(
                    "invalid_id", "Workflow 'id' may contain only letters, digits, '_' and '-' (max 100)", null)));
        }
        if (!d.path("name").isTextual() || d.path("name").asText().isBlank()) {
            throw new InvalidDefinitionException(List.of(new DefinitionValidator.Issue(
                    "missing_name", "Workflow 'name' is required", null)));
        }
        if (!d.path("nodes").isArray()) {
            throw new InvalidDefinitionException(List.of(new DefinitionValidator.Issue(
                    "missing_nodes", "Workflow must define a 'nodes' array", null)));
        }
    }

    /**
     * Replaces {@code trigger.secret} with a redaction marker and returns the hash to store. If the incoming
     * document has no secret (or only the marker), the previously stored hash is kept.
     */
    private String extractAndRedactSecret(ObjectNode definition, String existingHash) {
        JsonNode trigger = definition.get("trigger");
        // no trigger object means no webhook credential: never keep a stale hash for a trigger added back later
        if (trigger == null || !trigger.isObject()) return null;
        ObjectNode t = (ObjectNode) trigger;
        JsonNode secret = t.get("secret");
        String hash = existingHash;
        if (secret != null && secret.isTextual() && !secret.asText().isBlank() && !REDACTED.equals(secret.asText())) {
            hash = SecretHasher.sha256Hex(secret.asText());
        }
        if ("webhook".equals(t.path("type").asText())) {
            if (hash != null) t.put("secret", REDACTED);
        } else {
            t.remove("secret");
            hash = null;
        }
        return hash;
    }

    private static String textOrNull(JsonNode n, String f) {
        return n.hasNonNull(f) ? n.get(f).asText() : null;
    }
}
