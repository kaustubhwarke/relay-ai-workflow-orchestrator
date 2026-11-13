package com.relay.repository;

import com.relay.domain.ApprovalRecord;
import com.relay.domain.ApprovalStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ApprovalRepository {

    private final JdbcClient jdbc;

    public ApprovalRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private ApprovalRecord map(ResultSet rs, int row) throws SQLException {
        return new ApprovalRecord(
                rs.getString("id"),
                rs.getString("run_id"),
                rs.getString("node_id"),
                rs.getInt("step_sequence"),
                rs.getString("message"),
                ApprovalStatus.fromValue(rs.getString("status")),
                rs.getString("decided_by"),
                JsonCodec.instant(rs, "decided_at"),
                rs.getString("decision_comment"),
                JsonCodec.instant(rs, "created_at"));
    }

    public void insert(String id, String runId, String nodeId, int stepSequence, String message) {
        jdbc.sql("""
                INSERT INTO approvals (id, run_id, node_id, step_sequence, message, status)
                VALUES (:id, :r, :n, :s, :m, 'pending')
                """)
                .param("id", id).param("r", runId).param("n", nodeId).param("s", stepSequence).param("m", message)
                .update();
    }

    public Optional<ApprovalRecord> findById(String id) {
        return jdbc.sql("SELECT * FROM approvals WHERE id = :id").param("id", id).query(this::map).optional();
    }

    public Optional<ApprovalRecord> findByRunAndSequence(String runId, int sequence) {
        return jdbc.sql("SELECT * FROM approvals WHERE run_id = :r AND step_sequence = :s")
                .param("r", runId).param("s", sequence).query(this::map).optional();
    }

    public List<ApprovalRecord> list(String status, String runId, int limit, int offset) {
        return jdbc.sql("""
                SELECT * FROM approvals
                WHERE (CAST(:st AS varchar) IS NULL OR status = :st)
                  AND (CAST(:r AS varchar) IS NULL OR run_id = :r)
                ORDER BY created_at DESC, id
                LIMIT :limit OFFSET :offset
                """)
                .param("st", status).param("r", runId).param("limit", limit).param("offset", offset)
                .query(this::map).list();
    }

    /** pending -> approved/rejected. Returns false if the approval was already decided (or cancelled). */
    public boolean decide(String id, ApprovalStatus decision, String decidedBy, String comment) {
        if (decision != ApprovalStatus.APPROVED && decision != ApprovalStatus.REJECTED) {
            throw new IllegalArgumentException("A decision must be approved or rejected, not " + decision);
        }
        return jdbc.sql("""
                UPDATE approvals SET status = :st, decided_by = :by, decided_at = now(), decision_comment = :c
                WHERE id = :id AND status = 'pending'
                """)
                .param("id", id).param("st", decision.value()).param("by", decidedBy).param("c", comment)
                .update() > 0;
    }

    /**
     * The engine's approval gate: has any approval node in this run been approved? This is a pure database
     * query. Nothing a model outputs can write a row here; only the approve API (role APPROVER) can.
     */
    public boolean existsApproved(String runId) {
        return jdbc.sql("SELECT COUNT(*) FROM approvals WHERE run_id = :r AND status = 'approved'")
                .param("r", runId).query(Integer.class).single() > 0;
    }

    public int cancelPendingForRun(String runId) {
        return jdbc.sql("UPDATE approvals SET status = 'cancelled', decided_at = now() WHERE run_id = :r AND status = 'pending'")
                .param("r", runId).update();
    }

    /** Seconds this run has spent (or is spending) waiting on human approvals; excluded from timeout_seconds. */
    public double totalWaitSeconds(String runId) {
        return jdbc.sql("""
                SELECT COALESCE(SUM(EXTRACT(EPOCH FROM (COALESCE(decided_at, now()) - created_at))), 0)
                FROM approvals WHERE run_id = :r
                """).param("r", runId).query(Double.class).single();
    }

    public long countPending() {
        return jdbc.sql("SELECT COUNT(*) FROM approvals WHERE status = 'pending'").query(Long.class).single();
    }
}
