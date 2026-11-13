package com.relay.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.relay.domain.RunRecord;
import com.relay.domain.RunStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistence for runs. Every status transition is a compare-and-set on the current status so that
 * concurrent actors (worker, cancel API, approval API) can never produce an impossible transition.
 */
@Repository
public class RunRepository {

    private final JdbcClient jdbc;
    private final JsonCodec json;

    public RunRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    private RunRecord map(ResultSet rs, int row) throws SQLException {
        return new RunRecord(
                rs.getString("id"),
                rs.getString("workflow_id"),
                rs.getInt("workflow_version"),
                json.readColumn(rs, "definition_snapshot"),
                RunStatus.fromValue(rs.getString("status")),
                rs.getString("trigger_type"),
                json.readColumn(rs, "input"),
                rs.getString("current_node_id"),
                rs.getInt("steps_executed"),
                rs.getInt("ai_tokens_used"),
                json.readColumn(rs, "error"),
                rs.getBoolean("cancel_requested"),
                rs.getString("cancelled_reason"),
                rs.getString("replay_of_run_id"),
                JsonCodec.instant(rs, "created_at"),
                JsonCodec.instant(rs, "started_at"),
                JsonCodec.instant(rs, "finished_at"));
    }

    public void insert(String id, String workflowId, int version, JsonNode snapshot, String triggerType, JsonNode input, String replayOf) {
        jdbc.sql("""
                INSERT INTO runs (id, workflow_id, workflow_version, definition_snapshot, status, trigger_type, input, replay_of_run_id)
                VALUES (:id, :wf, :v, CAST(:snap AS jsonb), 'queued', :tt, CAST(:input AS jsonb), :replay)
                """)
                .param("id", id).param("wf", workflowId).param("v", version)
                .param("snap", json.write(snapshot)).param("tt", triggerType)
                .param("input", json.write(input)).param("replay", replayOf)
                .update();
    }

    public Optional<RunRecord> findById(String id) {
        return jdbc.sql("SELECT * FROM runs WHERE id = :id").param("id", id).query(this::map).optional();
    }

    public List<RunRecord> list(String workflowId, String status, int limit, int offset) {
        return jdbc.sql("""
                SELECT * FROM runs
                WHERE (CAST(:wf AS varchar) IS NULL OR workflow_id = :wf)
                  AND (CAST(:st AS varchar) IS NULL OR status = :st)
                ORDER BY created_at DESC, id DESC
                LIMIT :limit OFFSET :offset
                """)
                .param("wf", workflowId).param("st", status).param("limit", limit).param("offset", offset)
                .query(this::map).list();
    }

    public long count(String workflowId, String status) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM runs
                WHERE (CAST(:wf AS varchar) IS NULL OR workflow_id = :wf)
                  AND (CAST(:st AS varchar) IS NULL OR status = :st)
                """)
                .param("wf", workflowId).param("st", status).query(Long.class).single();
    }

    /** queued/running -> running. Returns false if the run is in any other state (e.g. cancelled). */
    public boolean markRunning(String id) {
        return jdbc.sql("""
                UPDATE runs SET status = 'running', started_at = COALESCE(started_at, now())
                WHERE id = :id AND status IN ('queued', 'running')
                """).param("id", id).update() > 0;
    }

    /** Moves a non-terminal run to a terminal state. Returns false if it was already terminal. */
    public boolean finish(String id, RunStatus status, JsonNode error, String cancelledReason) {
        return jdbc.sql("""
                UPDATE runs SET status = :st, error = CAST(:err AS jsonb), cancelled_reason = :cr, finished_at = now()
                WHERE id = :id AND status IN ('queued', 'running', 'waiting_approval')
                """)
                .param("id", id).param("st", status.value()).param("err", json.write(error)).param("cr", cancelledReason)
                .update() > 0;
    }

    public boolean transition(String id, RunStatus from, RunStatus to) {
        return jdbc.sql("UPDATE runs SET status = :to WHERE id = :id AND status = :from")
                .param("id", id).param("from", from.value()).param("to", to.value()).update() > 0;
    }

    public void bumpStepCounter(String id, String currentNodeId) {
        jdbc.sql("UPDATE runs SET steps_executed = steps_executed + 1, current_node_id = :n WHERE id = :id")
                .param("id", id).param("n", currentNodeId).update();
    }

    public void addAiTokens(String id, int tokens) {
        jdbc.sql("UPDATE runs SET ai_tokens_used = ai_tokens_used + :t WHERE id = :id")
                .param("id", id).param("t", tokens).update();
    }

    public void setCurrentNode(String id, String nodeId) {
        jdbc.sql("UPDATE runs SET current_node_id = :n WHERE id = :id").param("id", id).param("n", nodeId).update();
    }

    public void requestCancel(String id) {
        jdbc.sql("UPDATE runs SET cancel_requested = TRUE WHERE id = :id").param("id", id).update();
    }

    /** Runs that are queued/running but have no queue job (e.g. a crash between two commits). */
    public List<String> findOrphanedRunIds() {
        return jdbc.sql("""
                SELECT r.id FROM runs r
                WHERE r.status IN ('queued', 'running')
                  AND NOT EXISTS (SELECT 1 FROM queue_jobs q WHERE q.run_id = r.id)
                """).query(String.class).list();
    }

    public long countByStatus(RunStatus status) {
        return jdbc.sql("SELECT COUNT(*) FROM runs WHERE status = :s").param("s", status.value()).query(Long.class).single();
    }
}
