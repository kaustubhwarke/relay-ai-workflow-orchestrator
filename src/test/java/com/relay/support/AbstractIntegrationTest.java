package com.relay.support;

import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Real PostgreSQL (Testcontainers) + an in-JVM fake world + the full Spring context. The context is closed
 * after every test class so that no background worker of a previous class can steal another class's jobs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class AbstractIntegrationTest {

    protected static final String TOKEN = "test-token";
    protected static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
    protected static final FakeWorld WORLD;

    static {
        PG.start();
        WORLD = FakeWorld.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        r.add("relay.mock-world.url", WORLD::url);
        r.add("relay.mock-world.timeout-ms", () -> "2000");
        r.add("relay.http.timeout-ms", () -> "2000");
        r.add("relay.security.api-token", () -> TOKEN);
        r.add("relay.security.tokens[0].name", () -> "alice");
        r.add("relay.security.tokens[0].token", () -> "alice-token");
        r.add("relay.security.tokens[0].roles[0]", () -> "approver");
        r.add("relay.security.tokens[1].name", () -> "bob");
        r.add("relay.security.tokens[1].token", () -> "bob-token");
        r.add("relay.security.tokens[1].roles[0]", () -> "builder");
        r.add("relay.worker.id", () -> "it-worker");
        r.add("relay.worker.poll-interval-ms", () -> "100");
        r.add("relay.worker.lease-seconds", () -> "4");
        r.add("relay.worker.heartbeat-seconds", () -> "1");
        r.add("relay.engine.backoff-base-ms", () -> "50");
        r.add("relay.engine.backoff-max-ms", () -> "400");
        r.add("relay.scheduler.enabled", () -> "false");
        r.add("relay.ai.provider", () -> "heuristic");
    }

    @LocalServerPort protected int port;
    @Autowired protected TestRestTemplate rest;
    @Autowired protected ObjectMapper mapper;

    @Autowired protected org.springframework.jdbc.core.simple.JdbcClient jdbcClient;
    @Autowired private org.springframework.beans.factory.ObjectProvider<com.relay.worker.WorkerLoop> worker;

    /**
     * Test isolation on a shared database: runs left in flight by an earlier test or class (for example by the
     * worker-disabled durability tests) must not be picked up by this test's worker or its recovery sweeper.
     */
    @BeforeEach
    void resetWorld() {
        // 1. stop new work from starting, 2. wait until whatever the worker already claimed has finished,
        // 3. only then clear shared state, so a run from the previous test can never touch this one.
        jdbcClient.sql("UPDATE runs SET status = 'cancelled', finished_at = now() WHERE status IN ('queued', 'running', 'waiting_approval')").update();
        jdbcClient.sql("DELETE FROM queue_jobs").update();
        worker.ifAvailable(w -> await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(50))
                .until(() -> w.activeRunCount() == 0));
        jdbcClient.sql("DELETE FROM queue_jobs").update();
        WORLD.reset();
    }

    // ------------------------------------------------------------------ HTTP helpers

    protected record Reply(int status, JsonNode body) {
        public String text(String field) { return body.path(field).asText(); }
    }

    protected Reply call(HttpMethod method, String path, Object body, String token, String... headerPairs) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) h.setBearerAuth(token);
        for (int i = 0; i + 1 < headerPairs.length; i += 2) h.add(headerPairs[i], headerPairs[i + 1]);
        Object payload = body instanceof String s ? s : body == null ? null : body;
        ResponseEntity<JsonNode> resp = rest.exchange("http://localhost:" + port + path, method, new HttpEntity<>(payload, h), JsonNode.class);
        return new Reply(resp.getStatusCode().value(), resp.getBody());
    }

    protected Reply get(String path) { return call(HttpMethod.GET, path, null, TOKEN); }
    protected Reply post(String path, Object body) { return call(HttpMethod.POST, path, body, TOKEN); }
    protected Reply put(String path, Object body) { return call(HttpMethod.PUT, path, body, TOKEN); }

    protected Reply json(HttpMethod m, String path, String json, String token) {
        return call(m, path, json, token);
    }

    protected String createAndPublish(String definitionJson) {
        Reply c = post("/workflows", definitionJson);
        if (c.status() != 201) throw new AssertionError("create failed: " + c.status() + " " + c.body());
        String id = c.text("id");
        Reply p = post("/workflows/" + id + "/publish", null);
        if (p.status() != 200) throw new AssertionError("publish failed: " + p.status() + " " + p.body());
        return id;
    }

    protected String trigger(String workflowId, String inputJson) {
        Reply r = post("/workflows/" + workflowId + "/trigger", "{\"input\":" + inputJson + "}");
        if (r.status() != 202) throw new AssertionError("trigger failed: " + r.status() + " " + r.body());
        return r.text("run_id");
    }

    protected JsonNode awaitRun(String runId, String... statuses) {
        Set<String> wanted = Set.of(statuses);
        JsonNode[] last = new JsonNode[1];
        await().atMost(Duration.ofSeconds(40)).pollInterval(Duration.ofMillis(150)).until(() -> {
            last[0] = get("/runs/" + runId).body();
            return wanted.contains(last[0].path("status").asText());
        });
        return last[0];
    }

    protected JsonNode awaitTerminal(String runId) {
        return awaitRun(runId, "succeeded", "failed", "cancelled");
    }

    protected JsonNode step(JsonNode run, String nodeId) {
        for (JsonNode s : run.path("steps")) if (s.path("node_id").asText().equals(nodeId)) return s;
        throw new AssertionError("no step " + nodeId + " in " + run);
    }

    protected String pendingApprovalFor(String runId) {
        JsonNode list = get("/approvals?status=pending").body();
        for (JsonNode a : list) if (a.path("run_id").asText().equals(runId)) return a.path("id").asText();
        throw new AssertionError("no pending approval for " + runId);
    }
}
