package com.relay;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.relay.domain.RunStatus;
import com.relay.domain.StepRecord;
import com.relay.domain.StepStatus;
import com.relay.engine.RunExecutor;
import com.relay.engine.WorkerLease;
import com.relay.repository.QueueRepository;
import com.relay.repository.RunRepository;
import com.relay.repository.StepRepository;
import com.relay.support.AbstractIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

/**
 * Durability, driven deterministically: the worker is disabled, so the tests call the executor themselves and
 * "kill" it by revoking its lease, then "restart" by executing again. All state is in PostgreSQL.
 */
@TestPropertySource(properties = "relay.worker.enabled=false")
class DurabilityIT extends AbstractIntegrationTest {

    @Autowired RunExecutor executor;
    @Autowired RunRepository runs;
    @Autowired StepRepository steps;
    @Autowired QueueRepository queue;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void cleanQueue() {
        jdbc.sql("DELETE FROM queue_jobs").update();
    }

    private static String chain(String id, String delayNode) {
        String mid = delayNode == null ? "b" : "d";
        String nodes = delayNode == null
                ? """
                  [{"id":"a","type":"notify","params":{"channel":"chat","to":"#ops","message":"A"},"next":"b"},
                   {"id":"b","type":"notify","params":{"channel":"chat","to":"#ops","message":"B"},"next":"c"},
                   {"id":"c","type":"notify","params":{"channel":"chat","to":"#ops","message":"C"},"next":null}]"""
                : """
                  [{"id":"a","type":"notify","params":{"channel":"chat","to":"#ops","message":"A"},"next":"d"},
                   {"id":"d","type":"delay","params":{"seconds":2},"next":"b"},
                   {"id":"b","type":"notify","params":{"channel":"chat","to":"#ops","message":"B"},"next":null}]""";
        return """
                {"id":"%s","name":"%s","trigger":{"type":"manual"},"entry":"a",
                 "limits":{"max_steps":20,"timeout_seconds":120,"max_ai_tokens":0},"nodes":%s}""".formatted(id, id, nodes);
    }

    @Test
    void aKilledRunResumesFromTheLastCompletedStepAndSkipsCompletedWork() {
        String wf = createAndPublish(chain("wf_dur_kill", null));
        String run = trigger(wf, "{}");

        AtomicInteger calls = new AtomicInteger();
        WorkerLease dyingAfterFirstStep = () -> calls.incrementAndGet() <= 2;
        assertThat(executor.execute(run, dyingAfterFirstStep)).isEqualTo(RunExecutor.Outcome.LEASE_LOST);

        List<StepRecord> persisted = steps.findByRunId(run);
        assertThat(persisted).hasSize(1);
        assertThat(persisted.get(0).nodeId()).isEqualTo("a");
        assertThat(persisted.get(0).status()).isEqualTo(StepStatus.SUCCEEDED);
        assertThat(runs.findById(run).orElseThrow().status()).isEqualTo(RunStatus.RUNNING);
        assertThat(WORLD.executed("chat.message")).isEqualTo(1);

        // restart: a fresh executor invocation continues from the persisted state
        assertThat(executor.execute(run, WorkerLease.ALWAYS)).isEqualTo(RunExecutor.Outcome.FINISHED);
        assertThat(runs.findById(run).orElseThrow().status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(steps.findByRunId(run)).extracting(StepRecord::nodeId).containsExactly("a", "b", "c");
        assertThat(WORLD.executed("chat.message")).as("each side effect exactly once").isEqualTo(3);
        assertThat(WORLD.replayed("chat.message")).as("completed steps are skipped, not replayed").isZero();
    }

    @Test
    void theCrashWindowIsClosedByTheStableIdempotencyKey() throws Exception {
        String wf = createAndPublish(chain("wf_dur_window", null));
        String run = trigger(wf, "{}");

        // State after a kill -9 in the dangerous moment: the side effect LANDED at the world, but the step was
        // never marked complete (it is still 'running' in the database).
        HttpResponse<String> landed = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(WORLD.url() + "/chat/message"))
                        .header("Idempotency-Key", run + ":a").header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"channel\":\"#ops\",\"message\":\"A\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(landed.statusCode()).isEqualTo(200);
        steps.insert(run, 1, "a", "notify", StepStatus.RUNNING, run + ":a", null, null, null);
        runs.bumpStepCounter(run, "a");

        assertThat(executor.execute(run, WorkerLease.ALWAYS)).isEqualTo(RunExecutor.Outcome.FINISHED);

        assertThat(runs.findById(run).orElseThrow().status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(WORLD.executed("chat.message")).as("A (landed before the crash), B and C: three effects").isEqualTo(3);
        assertThat(WORLD.replayed("chat.message")).as("the re-sent A was absorbed by the world").isEqualTo(1);
        assertThat(WORLD.ledger().stream().filter(e -> e.replayed()).findFirst().get().idempotencyKey()).isEqualTo(run + ":a");
    }

    @Test
    void anExpiredLeaseMakesTheJobClaimableByAnotherWorkerAndHeartbeatsKeepItAlive() throws Exception {
        String wf = createAndPublish(chain("wf_dur_lease", null));
        String run = trigger(wf, "{}");

        var first = queue.claim("worker-1", 1);
        assertThat(first).isPresent();
        assertThat(first.get().runId()).isEqualTo(run);
        assertThat(queue.claim("worker-2", 1)).as("leased jobs are invisible to others").isEmpty();
        assertThat(queue.heartbeat(run, "worker-2", 1)).as("only the owner can extend a lease").isFalse();
        assertThat(queue.heartbeat(run, "worker-1", 2)).isTrue();

        Thread.sleep(1300);
        assertThat(queue.claim("worker-2", 1)).as("heartbeat extended the lease past 1s").isEmpty();
        Thread.sleep(1000);
        var second = queue.claim("worker-2", 1);
        assertThat(second).as("an expired lease (crashed worker) is reclaimed").isPresent();
        assertThat(second.get().claimCount()).isEqualTo(2);
    }

    @Test
    void aRestartedWorkerReleasesTheLeasesItsPreviousIncarnationHeld() {
        String wf = createAndPublish(chain("wf_dur_release", null));
        String run = trigger(wf, "{}");
        assertThat(queue.claim("worker-x", 300)).isPresent();
        assertThat(queue.claim("worker-y", 300)).isEmpty();
        assertThat(queue.releaseAllHeldBy("worker-x")).isEqualTo(1);
        assertThat(queue.claim("worker-y", 300)).hasValueSatisfying(j -> assertThat(j.runId()).isEqualTo(run));
    }

    @Test
    void twoExecutorsRacingOnTheSameRunNeverDuplicateASideEffect() throws Exception {
        String wf = createAndPublish(chain("wf_dur_race", null));
        String run = trigger(wf, "{}");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<RunExecutor.Outcome>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) results.add(pool.submit(() -> executor.execute(run, WorkerLease.ALWAYS)));
            for (Future<RunExecutor.Outcome> f : results) f.get();
        } finally {
            pool.shutdownNow();
        }
        if (runs.findById(run).orElseThrow().status() != RunStatus.SUCCEEDED) executor.execute(run, WorkerLease.ALWAYS);

        assertThat(runs.findById(run).orElseThrow().status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(steps.findByRunId(run)).extracting(StepRecord::sequence).containsExactly(1, 2, 3);
        assertThat(WORLD.executed("chat.message")).isEqualTo(3);
    }

    @Test
    void aDelayParksTheRunDurablyAndWakesOnlyAtItsResumeTime() throws Exception {
        String wf = createAndPublish(chain("wf_dur_delay", "delay"));
        String run = trigger(wf, "{}");
        assertThat(executor.execute(run, WorkerLease.ALWAYS)).isEqualTo(RunExecutor.Outcome.DELAYED);

        List<StepRecord> s = steps.findByRunId(run);
        assertThat(s).extracting(StepRecord::nodeId).containsExactly("a", "d");
        assertThat(s.get(1).status()).isEqualTo(StepStatus.WAITING);
        assertThat(s.get(1).resumeAt()).isNotNull();
        String lockedBy = jdbc.sql("SELECT locked_by FROM queue_jobs WHERE run_id = :r").param("r", run).query(String.class).optional().orElse(null);
        assertThat(lockedBy).as("the job is released while the run sleeps: nothing waits in memory").isNull();

        assertThat(executor.execute(run, WorkerLease.ALWAYS)).as("too early").isEqualTo(RunExecutor.Outcome.DELAYED);
        assertThat(steps.findByRunId(run)).hasSize(2);

        Thread.sleep(2200);
        assertThat(executor.execute(run, WorkerLease.ALWAYS)).isEqualTo(RunExecutor.Outcome.FINISHED);
        assertThat(runs.findById(run).orElseThrow().status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(WORLD.executed("chat.message")).isEqualTo(2);
    }

    @Test
    void queuedRunsSurviveAFullRestartAndAllComplete() {
        String wf = createAndPublish(chain("wf_dur_restart", null));
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) ids.add(trigger(wf, "{}"));
        for (String id : ids) assertThat(runs.findById(id).orElseThrow().status()).isEqualTo(RunStatus.QUEUED);

        // a freshly started worker drains the queue
        for (int i = 0; i < 20; i++) {
            var job = queue.claim("fresh-worker", 30);
            if (job.isEmpty()) break;
            assertThat(executor.execute(job.get().runId(), WorkerLease.ALWAYS)).isEqualTo(RunExecutor.Outcome.FINISHED);
            queue.deleteOwned(job.get().runId(), "fresh-worker");
        }
        for (String id : ids) assertThat(runs.findById(id).orElseThrow().status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(WORLD.executed("chat.message")).isEqualTo(15);
        assertThat(queue.depth()).isZero();
    }

    @Test
    void anOrphanedRunWithNoQueueJobIsFoundForReenqueueing() {
        String wf = createAndPublish(chain("wf_dur_orphan", null));
        String run = trigger(wf, "{}");
        jdbc.sql("DELETE FROM queue_jobs WHERE run_id = :r").param("r", run).update();
        assertThat(runs.findOrphanedRunIds()).contains(run);
        queue.enqueue(run);
        assertThat(runs.findOrphanedRunIds()).doesNotContain(run);
        assertThat(queue.claim("w", 5)).hasValueSatisfying(j -> assertThat(j.runId()).isEqualTo(run));
    }

    @Test
    void parkingAtAnApprovalReleasesTheJobAndApprovingRequeuesIt() {
        String run = trigger("wf_expense_approval", "{\"employee_email\":\"a@example.com\",\"amount_usd\":400,\"description\":\"x\"}");
        assertThat(executor.execute(run, WorkerLease.ALWAYS)).isEqualTo(RunExecutor.Outcome.WAITING_APPROVAL);
        assertThat(runs.findById(run).orElseThrow().status()).isEqualTo(RunStatus.WAITING_APPROVAL);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM queue_jobs WHERE run_id = :r").param("r", run).query(Integer.class).single()).isZero();

        post("/approvals/" + pendingApprovalFor(run) + "/approve", null);
        assertThat(runs.findById(run).orElseThrow().status()).isEqualTo(RunStatus.QUEUED);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM queue_jobs WHERE run_id = :r").param("r", run).query(Integer.class).single()).isEqualTo(1);
        assertThat(executor.execute(run, WorkerLease.ALWAYS)).isEqualTo(RunExecutor.Outcome.FINISHED);
        JsonNode done = get("/runs/" + run).body();
        assertThat(done.path("status").asText()).isEqualTo("succeeded");
    }
}
