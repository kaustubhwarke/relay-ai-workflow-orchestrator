package com.relay.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.relay.domain.StepRecord;
import com.relay.domain.StepStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistence for the trace. {@code UNIQUE (run_id, sequence)} is the fence that stops a zombie worker
 * from appending a second copy of a step another worker already owns.
 */
@Repository
public class StepRepository {

    private final JdbcClient jdbc;
    private final JsonCodec json;

    public StepRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    private StepRecord map(ResultSet rs, int row) throws SQLException {
        return new StepRecord(
                rs.getLong("id"),
                rs.getString("run_id"),
                rs.getInt("sequence"),
                rs.getString("node_id"),
                rs.getString("node_type"),
                StepStatus.fromValue(rs.getString("status")),
                rs.getInt("attempt"),
                json.readColumn(rs, "resolved_input"),
                json.readColumn(rs, "output"),
                rs.getString("error"),
                rs.getString("error_code"),
                JsonCodec.nullableInt(rs, "tokens_prompt"),
                JsonCodec.nullableInt(rs, "tokens_completion"),
                json.readColumn(rs, "ai_attempts"),
                rs.getString("idempotency_key"),
                rs.getString("approval_id"),
                JsonCodec.instant(rs, "resume_at"),
                JsonCodec.instant(rs, "started_at"),
                JsonCodec.instant(rs, "finished_at"),
                JsonCodec.nullableLong(rs, "duration_ms"));
    }

    public List<StepRecord> findByRunId(String runId) {
        return jdbc.sql("SELECT * FROM steps WHERE run_id = :r ORDER BY sequence").param("r", runId).query(this::map).list();
    }

    public Optional<StepRecord> findLast(String runId) {
        return jdbc.sql("SELECT * FROM steps WHERE run_id = :r ORDER BY sequence DESC LIMIT 1")
                .param("r", runId).query(this::map).optional();
    }

    public Optional<StepRecord> find(String runId, int sequence) {
        return jdbc.sql("SELECT * FROM steps WHERE run_id = :r AND sequence = :s")
                .param("r", runId).param("s", sequence).query(this::map).optional();
    }

    public int nextSequence(String runId) {
        return jdbc.sql("SELECT COALESCE(MAX(sequence), 0) + 1 FROM steps WHERE run_id = :r")
                .param("r", runId).query(Integer.class).single();
    }

    /** How many times this node has already been entered in the run (1-based visit number for the next entry). */
    public int nextVisit(String runId, String nodeId) {
        return jdbc.sql("SELECT COUNT(*) + 1 FROM steps WHERE run_id = :r AND node_id = :n")
                .param("r", runId).param("n", nodeId).query(Integer.class).single();
    }

    /**
     * Inserts a step. Throws {@link org.springframework.dao.DuplicateKeyException} if another executor already
     * owns this sequence number.
     */
    public void insert(String runId, int sequence, String nodeId, String nodeType, StepStatus status,
                       String idempotencyKey, Instant resumeAt, String approvalId, JsonNode resolvedInput) {
        jdbc.sql("""
                INSERT INTO steps (run_id, sequence, node_id, node_type, status, idempotency_key, resume_at, approval_id, resolved_input)
                VALUES (:r, :s, :n, :t, :st, :ik, :ra, :ap, CAST(:ri AS jsonb))
                """)
                .param("r", runId).param("s", sequence).param("n", nodeId).param("t", nodeType)
                .param("st", status.value()).param("ik", idempotencyKey)
                .param("ra", resumeAt == null ? null : OffsetDateTime.ofInstant(resumeAt, ZoneOffset.UTC))
                .param("ap", approvalId).param("ri", json.write(resolvedInput))
                .update();
    }

    public void updateResolvedInput(String runId, int sequence, JsonNode resolvedInput) {
        jdbc.sql("UPDATE steps SET resolved_input = CAST(:ri AS jsonb) WHERE run_id = :r AND sequence = :s")
                .param("r", runId).param("s", sequence).param("ri", json.write(resolvedInput)).update();
    }

    public void updateAttempt(String runId, int sequence, int attempt) {
        jdbc.sql("UPDATE steps SET attempt = :a WHERE run_id = :r AND sequence = :s")
                .param("r", runId).param("s", sequence).param("a", attempt).update();
    }

    public record AiUsage(Integer promptTokens, Integer completionTokens, JsonNode attempts) {
        public static final AiUsage NONE = new AiUsage(null, null, null);
    }

    public void markSucceeded(String runId, int sequence, JsonNode output, int attempt, AiUsage ai) {
        jdbc.sql("""
                UPDATE steps SET status = 'succeeded', output = CAST(:o AS jsonb), attempt = :a,
                       tokens_prompt = :tp, tokens_completion = :tc, ai_attempts = CAST(:aa AS jsonb),
                       error = NULL, error_code = NULL,
                       finished_at = now(), duration_ms = (EXTRACT(EPOCH FROM (now() - started_at)) * 1000)::bigint
                WHERE run_id = :r AND sequence = :s
                """)
                .param("r", runId).param("s", sequence).param("o", json.write(output)).param("a", attempt)
                .param("tp", ai.promptTokens()).param("tc", ai.completionTokens()).param("aa", json.write(ai.attempts()))
                .update();
    }

    public void markFailed(String runId, int sequence, String error, String errorCode, int attempt, AiUsage ai) {
        jdbc.sql("""
                UPDATE steps SET status = 'failed', error = :e, error_code = :c, attempt = :a,
                       tokens_prompt = :tp, tokens_completion = :tc, ai_attempts = CAST(:aa AS jsonb),
                       finished_at = now(), duration_ms = (EXTRACT(EPOCH FROM (now() - started_at)) * 1000)::bigint
                WHERE run_id = :r AND sequence = :s
                """)
                .param("r", runId).param("s", sequence).param("e", error).param("c", errorCode).param("a", attempt)
                .param("tp", ai.promptTokens()).param("tc", ai.completionTokens()).param("aa", json.write(ai.attempts()))
                .update();
    }

    /** waiting -> succeeded (delay elapsed or approval decided). Returns false if it was not waiting. */
    public boolean completeWaiting(String runId, int sequence, JsonNode output) {
        return jdbc.sql("""
                UPDATE steps SET status = 'succeeded', output = CAST(:o AS jsonb), finished_at = now(),
                       duration_ms = (EXTRACT(EPOCH FROM (now() - started_at)) * 1000)::bigint
                WHERE run_id = :r AND sequence = :s AND status = 'waiting'
                """)
                .param("r", runId).param("s", sequence).param("o", json.write(output)).update() > 0;
    }

    /** waiting -> failed (e.g. approval rejected or cancelled). */
    public boolean failWaiting(String runId, int sequence, JsonNode output, String error, String errorCode) {
        return jdbc.sql("""
                UPDATE steps SET status = 'failed', output = CAST(:o AS jsonb), error = :e, error_code = :c, finished_at = now(),
                       duration_ms = (EXTRACT(EPOCH FROM (now() - started_at)) * 1000)::bigint
                WHERE run_id = :r AND sequence = :s AND status = 'waiting'
                """)
                .param("r", runId).param("s", sequence).param("o", json.write(output))
                .param("e", error).param("c", errorCode).update() > 0;
    }
}
