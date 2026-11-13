package com.relay.worker;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relay.config.RelayProperties;
import com.relay.domain.RunStatus;
import com.relay.engine.RunExecutor;
import com.relay.engine.WorkerLease;
import com.relay.observability.RelayMetrics;
import com.relay.repository.QueueRepository;
import com.relay.repository.RunRepository;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * The background worker. Runs inside the API process by default, or alone ({@code RELAY_WORKER_ENABLED=true},
 * web disabled) as a separate process; either way crash recovery works because all state is in the database.
 *
 * <p>Each worker thread claims one job at a time with a lease ({@code FOR UPDATE SKIP LOCKED}). A heartbeat
 * thread extends the leases of active jobs; if a heartbeat finds the lease gone, the executor is told to
 * stop between steps. A {@code kill -9} simply stops heartbeats: the lease expires and any worker (including
 * the restarted one) picks the run up and resumes it from the last persisted step.
 */
@Component
@ConditionalOnProperty(name = "relay.worker.enabled", havingValue = "true", matchIfMissing = true)
public class WorkerLoop implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(WorkerLoop.class);

    /** Revocable lease handle shared between the executing thread and the heartbeat thread. */
    private static final class LeaseHandle implements WorkerLease {
        private final AtomicBoolean held = new AtomicBoolean(true);
        @Override public boolean isHeld() { return held.get(); }
        void revoke() { held.set(false); }
    }

    private final RelayProperties props;
    private final QueueRepository queue;
    private final RunRepository runs;
    private final RunExecutor executor;
    private final ScheduleDispatcher scheduleDispatcher;
    private final RelayMetrics metrics;

    private final String workerId;
    private final Map<String, LeaseHandle> active = new ConcurrentHashMap<>();
    private volatile boolean running;
    private ExecutorService workers;
    private ScheduledExecutorService housekeeping;

    public WorkerLoop(RelayProperties props, QueueRepository queue, RunRepository runs, RunExecutor executor,
                      ScheduleDispatcher scheduleDispatcher, RelayMetrics metrics) {
        this.props = props;
        this.queue = queue;
        this.runs = runs;
        this.executor = executor;
        this.scheduleDispatcher = scheduleDispatcher;
        this.metrics = metrics;
        this.workerId = props.getWorker().getId() == null || props.getWorker().getId().isBlank()
                ? "worker-" + hostName()
                : props.getWorker().getId();
    }

    public String workerId() { return workerId; }

    @Override
    public void start() {
        if (running) return;
        running = true;
        // A restarted process reclaims whatever its previous incarnation (same worker id) still held, so
        // recovery after kill -9 is immediate instead of waiting for the lease to expire.
        int released = queue.releaseAllHeldBy(workerId);
        if (released > 0) log.info("Worker {} released {} lease(s) left by a previous incarnation", workerId, released);
        sweepOrphans();

        int n = Math.max(1, props.getWorker().getConcurrency());
        workers = Executors.newFixedThreadPool(n, r -> {
            Thread t = new Thread(r, "relay-worker");
            t.setDaemon(false);
            return t;
        });
        for (int i = 0; i < n; i++) workers.submit(this::loop);

        housekeeping = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "relay-housekeeping");
            t.setDaemon(true);
            return t;
        });
        housekeeping.scheduleWithFixedDelay(this::heartbeat, 1, props.getWorker().getHeartbeatSeconds(), TimeUnit.SECONDS);
        housekeeping.scheduleWithFixedDelay(this::sweepOrphans,
                props.getWorker().getRecoverySweepSeconds(), props.getWorker().getRecoverySweepSeconds(), TimeUnit.SECONDS);
        if (props.getScheduler().isEnabled()) {
            housekeeping.scheduleWithFixedDelay(this::fireSchedules, 2, Math.max(1, props.getScheduler().getPollIntervalMs() / 1000), TimeUnit.SECONDS);
        }
        log.info("Worker {} started: {} thread(s), lease {}s, heartbeat {}s", workerId, n,
                props.getWorker().getLeaseSeconds(), props.getWorker().getHeartbeatSeconds());
    }

    @Override
    public void stop() {
        if (!running) return;
        running = false;
        log.info("Worker {} stopping", workerId);
        if (housekeeping != null) housekeeping.shutdownNow();
        if (workers != null) {
            workers.shutdownNow(); // interrupts in-flight steps; the executor yields and the job is released below
            try {
                workers.awaitTermination(15, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        // Make anything still held claimable right away so another worker resumes it immediately.
        for (String runId : List.copyOf(active.keySet())) {
            try {
                queue.reschedule(runId, Instant.now());
            } catch (RuntimeException e) {
                log.warn("Could not release job for run {} on shutdown: {}", runId, e.getMessage());
            }
        }
    }

    @Override
    public boolean isRunning() { return running; }

    /** Stop early: before the datasource and web server shut down. */
    @Override
    public int getPhase() { return Integer.MAX_VALUE - 100; }

    // ------------------------------------------------------------------ loop

    private void loop() {
        long idleMs = props.getWorker().getPollIntervalMs();
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                var claimed = queue.claim(workerId, props.getWorker().getLeaseSeconds());
                if (claimed.isEmpty()) {
                    Thread.sleep(idleMs);
                    continue;
                }
                handle(claimed.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                log.error("Worker loop error (will retry): {}", e.toString());
                try {
                    Thread.sleep(Math.max(idleMs, 1000));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private void handle(QueueRepository.ClaimedJob job) {
        String runId = job.runId();
        LeaseHandle lease = new LeaseHandle();
        active.put(runId, lease);
        MDC.put("runId", runId);
        try {
            if (job.claimCount() > props.getWorker().getMaxClaims()) {
                quarantine(job);
                return;
            }
            RunExecutor.Outcome outcome = executor.execute(runId, lease);
            switch (outcome) {
                case FINISHED, WAITING_APPROVAL -> queue.deleteOwned(runId, workerId);
                case DELAYED -> { /* job already rescheduled for the wake-up time */ }
                case LEASE_LOST -> {
                    if (!running) queue.reschedule(runId, Instant.now()); // graceful shutdown: hand over immediately
                    else log.warn("Lost the lease on run {}; another worker owns it now", runId);
                }
            }
        } catch (RuntimeException e) {
            // Leave the run as it is: its steps are persisted, so a later claim resumes it. A short reschedule
            // avoids a hot failure loop; the claim counter eventually quarantines a poison run.
            log.error("Executor crashed on run {}: {}", runId, e.toString(), e);
            try {
                queue.reschedule(runId, Instant.now().plusSeconds(5));
            } catch (RuntimeException ignored) {
                // lease expiry will make the job claimable again
            }
        } finally {
            active.remove(runId);
            MDC.remove("runId");
        }
    }

    /** A run that has been claimed too many times without making progress is failed instead of looping forever. */
    private void quarantine(QueueRepository.ClaimedJob job) {
        ObjectNode err = JsonNodeFactory.instance.objectNode();
        err.put("code", "max_claims_exceeded");
        err.put("message", "The run was claimed " + job.claimCount() + " times without completing and was quarantined");
        if (runs.finish(job.runId(), RunStatus.FAILED, err, null)) metrics.runFinished(RunStatus.FAILED);
        queue.delete(job.runId());
        log.error("Run {} quarantined after {} claims", job.runId(), job.claimCount());
    }

    private void heartbeat() {
        for (Map.Entry<String, LeaseHandle> e : active.entrySet()) {
            try {
                if (!queue.heartbeat(e.getKey(), workerId, props.getWorker().getLeaseSeconds())) {
                    log.warn("Heartbeat found run {} no longer leased to {}; revoking", e.getKey(), workerId);
                    e.getValue().revoke();
                }
            } catch (RuntimeException ex) {
                log.warn("Heartbeat failed for run {}: {}", e.getKey(), ex.toString());
            }
        }
    }

    private void sweepOrphans() {
        try {
            for (String runId : runs.findOrphanedRunIds()) {
                queue.enqueue(runId);
                log.warn("Re-enqueued orphaned run {}", runId);
            }
        } catch (RuntimeException e) {
            log.warn("Orphan sweep failed: {}", e.toString());
        }
    }

    private void fireSchedules() {
        try {
            scheduleDispatcher.fireDue();
        } catch (RuntimeException e) {
            log.warn("Schedule dispatch failed: {}", e.toString());
        }
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "local";
        }
    }
}
