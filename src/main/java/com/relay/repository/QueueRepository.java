package com.relay.repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The durable work queue, backed by the {@code queue_jobs} table.
 *
 * <p>Claiming uses {@code FOR UPDATE SKIP LOCKED} so many workers can poll concurrently without blocking
 * each other, and a <em>lease</em> ({@code locked_until}) so that a job held by a crashed worker becomes
 * claimable again as soon as the lease expires. All time comparisons use the database clock.
 */
@Repository
public class QueueRepository {

    public record ClaimedJob(long jobId, String runId, int claimCount) {}

    private final JdbcClient jdbc;

    public QueueRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Idempotent: a run has at most one job. */
    public void enqueue(String runId) {
        jdbc.sql("INSERT INTO queue_jobs (run_id) VALUES (:r) ON CONFLICT (run_id) DO NOTHING").param("r", runId).update();
    }

    public Optional<ClaimedJob> claim(String workerId, int leaseSeconds) {
        return jdbc.sql("""
                UPDATE queue_jobs SET locked_by = :w,
                       locked_until = now() + make_interval(secs => CAST(:lease AS double precision)),
                       claim_count = claim_count + 1
                WHERE id = (
                    SELECT id FROM queue_jobs
                    WHERE available_at <= now() AND (locked_until IS NULL OR locked_until < now())
                    ORDER BY available_at, id
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1)
                RETURNING id, run_id, claim_count
                """)
                .param("w", workerId).param("lease", leaseSeconds)
                .query((rs, n) -> new ClaimedJob(rs.getLong("id"), rs.getString("run_id"), rs.getInt("claim_count")))
                .optional();
    }

    /** Extends the lease. Returns false if this worker no longer owns the job (lease lost). */
    public boolean heartbeat(String runId, String workerId, int leaseSeconds) {
        return jdbc.sql("""
                UPDATE queue_jobs SET locked_until = now() + make_interval(secs => CAST(:lease AS double precision))
                WHERE run_id = :r AND locked_by = :w
                """)
                .param("r", runId).param("w", workerId).param("lease", leaseSeconds).update() > 0;
    }

    /** Removes the job (run finished or parked for an approval). */
    public void delete(String runId) {
        jdbc.sql("DELETE FROM queue_jobs WHERE run_id = :r").param("r", runId).update();
    }

    /** Removes the job only if the worker still owns it. */
    public boolean deleteOwned(String runId, String workerId) {
        return jdbc.sql("DELETE FROM queue_jobs WHERE run_id = :r AND locked_by = :w")
                .param("r", runId).param("w", workerId).update() > 0;
    }

    /** Releases the lease and makes the job claimable at {@code availableAt} (delay nodes, graceful shutdown). */
    public void reschedule(String runId, Instant availableAt) {
        jdbc.sql("""
                UPDATE queue_jobs SET available_at = :at, locked_by = NULL, locked_until = NULL, claim_count = 0 WHERE run_id = :r
                """)
                .param("r", runId).param("at", OffsetDateTime.ofInstant(availableAt, ZoneOffset.UTC)).update();
    }

    /** Wakes a job that is waiting (not currently leased) so it is picked up immediately, e.g. after a cancel request. */
    public void expediteIfIdle(String runId) {
        jdbc.sql("""
                UPDATE queue_jobs SET available_at = now() WHERE run_id = :r AND (locked_until IS NULL OR locked_until < now())
                """).param("r", runId).update();
    }

    /** A restarted worker reclaims whatever its previous incarnation (same id) still held. */
    public int releaseAllHeldBy(String workerId) {
        return jdbc.sql("UPDATE queue_jobs SET locked_by = NULL, locked_until = NULL WHERE locked_by = :w")
                .param("w", workerId).update();
    }

    public long depth() {
        return jdbc.sql("SELECT COUNT(*) FROM queue_jobs").query(Long.class).single();
    }

    public long readyDepth() {
        return jdbc.sql("""
                SELECT COUNT(*) FROM queue_jobs WHERE available_at <= now() AND (locked_until IS NULL OR locked_until < now())
                """).query(Long.class).single();
    }
}
