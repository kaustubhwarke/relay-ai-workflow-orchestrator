package com.relay.repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ScheduleRepository {

    public record DueSchedule(String workflowId, String cron) {}

    private final JdbcClient jdbc;

    public ScheduleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void upsert(String workflowId, String cron, Instant nextFireAt) {
        jdbc.sql("""
                INSERT INTO schedules (workflow_id, cron, next_fire_at, enabled) VALUES (:w, :c, :n, TRUE)
                ON CONFLICT (workflow_id) DO UPDATE SET cron = :c, next_fire_at = :n, enabled = TRUE
                """)
                .param("w", workflowId).param("c", cron).param("n", OffsetDateTime.ofInstant(nextFireAt, ZoneOffset.UTC))
                .update();
    }

    public void disable(String workflowId) {
        jdbc.sql("UPDATE schedules SET enabled = FALSE WHERE workflow_id = :w").param("w", workflowId).update();
    }

    /**
     * Locks due schedules for the surrounding transaction so exactly one scheduler instance fires each. The lock
     * only lives as long as that transaction, so a call without one is refused instead of silently unlocked.
     */
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public List<DueSchedule> lockDue() {
        return jdbc.sql("""
                SELECT workflow_id, cron FROM schedules
                WHERE enabled AND next_fire_at <= now()
                ORDER BY next_fire_at
                FOR UPDATE SKIP LOCKED
                """).query((rs, n) -> new DueSchedule(rs.getString("workflow_id"), rs.getString("cron"))).list();
    }

    public void advance(String workflowId, Instant nextFireAt) {
        jdbc.sql("UPDATE schedules SET next_fire_at = :n WHERE workflow_id = :w")
                .param("w", workflowId).param("n", OffsetDateTime.ofInstant(nextFireAt, ZoneOffset.UTC)).update();
    }
}
