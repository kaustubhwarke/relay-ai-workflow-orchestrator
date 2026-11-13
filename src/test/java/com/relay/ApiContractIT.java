package com.relay;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.relay.support.AbstractIntegrationTest;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/** The fixed API contract (what scripts/smoke_test.py drives), plus auth, roles and secret hygiene. */
class ApiContractIT extends AbstractIntegrationTest {

    private static final Set<String> STATUS_VOCAB = Set.of("queued", "running", "waiting_approval", "succeeded", "failed", "cancelled");

    @Test
    void seedWorkflowsAreLoadedAsPublished() {
        JsonNode list = get("/workflows").body();
        for (String id : new String[] {"wf_support_triage", "wf_expense_approval", "wf_slow_fulfillment", "wf_runaway"}) {
            JsonNode wf = null;
            for (JsonNode w : list) if (w.path("id").asText().equals(id)) wf = w;
            assertThat(wf).as(id).isNotNull();
            assertThat(wf.path("status").asText()).isEqualTo("published");
        }
    }

    @Test
    void createPublishTriggerProducesATrace() {
        String id = createAndPublish(TestSupport.minimalWorkflow("wf_it_minimal"));
        String runId = trigger(id, "{}");
        JsonNode run = awaitTerminal(runId);
        assertThat(run.path("status").asText()).isEqualTo("succeeded");
        JsonNode s = step(run, "hello");
        assertThat(s.path("status").asText()).isEqualTo("succeeded");
        assertThat(s.path("attempt").asInt()).isEqualTo(1);
        assertThat(s.path("input").path("message").asText()).isEqualTo("hi");
        assertThat(s.path("output").path("delivered").asBoolean()).isTrue();
        assertThat(s.path("duration_ms").isNumber()).isTrue();
        assertThat(WORLD.executed("chat.message")).isEqualTo(1);
        assertThat(WORLD.ledger().get(0).idempotencyKey()).isEqualTo(runId + ":hello");
    }

    @Test
    void triggerReturnsPromptlyWhileTheRunIsStillInProgress() {
        // a 3-second delay proves the call returned before execution could possibly have finished
        String id = createAndPublish("""
                {"id":"wf_it_async","name":"Async","trigger":{"type":"manual"},"entry":"wait",
                 "nodes":[{"id":"wait","type":"delay","params":{"seconds":3},"next":null}]}""");
        long started = System.nanoTime();
        Reply r = post("/workflows/" + id + "/trigger", "{\"input\":{}}");
        long tookMs = (System.nanoTime() - started) / 1_000_000;
        assertThat(r.status()).isEqualTo(202);
        assertThat(r.text("status")).isIn("queued", "running");
        assertThat(r.body().has("run_id")).isTrue();
        assertThat(tookMs).as("the request handler must not wait for the run").isLessThan(2500);
        assertThat(get("/runs/" + r.text("run_id")).text("status")).isNotEqualTo("succeeded");
    }

    @Test
    void publishRejectsTheThreeRequiredBrokenDefinitionsWithDistinctMessages() {
        String[] defs = {
                """
                {"id":"wf_bad1","name":"b","trigger":{"type":"manual"},"entry":"n1","nodes":[{"id":"n1","type":"teleport","params":{},"next":null}]}""",
                """
                {"id":"wf_bad2","name":"b","trigger":{"type":"manual"},"entry":"n1","nodes":[{"id":"n1","type":"notify","params":{"channel":"chat"},"next":null}]}""",
                """
                {"id":"wf_bad3","name":"b","trigger":{"type":"manual"},"entry":"n1","nodes":[{"id":"n1","type":"delay","params":{"seconds":1},"next":"ghost"}]}"""
        };
        String[] expectedCodes = {"invalid_node_type", "missing_required_param", "dangling_reference"};
        Set<String> messages = new java.util.HashSet<>();
        for (int i = 0; i < defs.length; i++) {
            assertThat(post("/workflows", defs[i]).status()).isEqualTo(201); // drafts are editable work-in-progress
            Reply pub = post("/workflows/wf_bad" + (i + 1) + "/publish", null);
            assertThat(pub.status()).isEqualTo(422);
            assertThat(pub.body().path("error").path("code").asText()).isEqualTo(expectedCodes[i]);
            messages.add(pub.body().path("error").path("message").asText());
        }
        assertThat(messages).hasSize(3);
    }

    @Test
    void validateEndpointReportsEveryProblemWithoutStoringAnything() {
        Reply r = post("/workflows/validate", """
                {"id":"wf_dry","name":"d","trigger":{"type":"manual"},"entry":"zzz",
                 "nodes":[{"id":"a","type":"nope","params":{},"next":null}]}""");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().path("valid").asBoolean()).isFalse();
        java.util.List<String> codes = new java.util.ArrayList<>();
        r.body().path("errors").forEach(e -> codes.add(e.path("code").asText()));
        assertThat(codes).contains("invalid_node_type", "entry_not_found");
        assertThat(get("/workflows/wf_dry").status()).isEqualTo(404);
    }

    @Test
    void webhookRejectsMissingOrWrongSecretAndAcceptsTheRightOne() {
        String body = "{\"employee_email\":\"dev2@example.com\",\"amount_usd\":40,\"description\":\"Team lunch\"}";
        assertThat(call(HttpMethod.POST, "/hooks/wf_expense_approval", body, null).status()).isEqualTo(401);
        assertThat(call(HttpMethod.POST, "/hooks/wf_expense_approval", body, null, "X-Relay-Secret", "wrong").status()).isEqualTo(401);
        Reply ok = call(HttpMethod.POST, "/hooks/wf_expense_approval", body, null, "X-Relay-Secret", "whsec_expense_774");
        assertThat(ok.status()).isEqualTo(202);
        assertThat(awaitTerminal(ok.text("run_id")).path("status").asText()).isEqualTo("succeeded");
        assertThat(call(HttpMethod.POST, "/hooks/wf_nope", body, null, "X-Relay-Secret", "x").status()).isEqualTo(404);
    }

    @Test
    void webhookRunsTheSameWebhookTwiceAsTwoIndependentRuns() {
        String body = "{\"employee_email\":\"dev9@example.com\",\"amount_usd\":10,\"description\":\"Coffee\"}";
        String a = call(HttpMethod.POST, "/hooks/wf_expense_approval", body, null, "X-Relay-Secret", "whsec_expense_774").text("run_id");
        String b = call(HttpMethod.POST, "/hooks/wf_expense_approval", body, null, "X-Relay-Secret", "whsec_expense_774").text("run_id");
        assertThat(a).isNotEqualTo(b);
        awaitTerminal(a);
        awaitTerminal(b);
        assertThat(WORLD.executed("email.send")).as("at-least-once webhooks: each run's effect is individually exactly-once").isEqualTo(2);
    }

    @Test
    void expenseApprovalLifecycleSmallAutoApprovesLargePausesAndResumes() {
        String small = trigger("wf_expense_approval", "{\"employee_email\":\"a@example.com\",\"amount_usd\":40,\"description\":\"Lunch\"}");
        JsonNode s = awaitTerminal(small);
        assertThat(s.path("status").asText()).isEqualTo("succeeded");
        assertThat(step(s, "is_large").path("output").path("result").asBoolean()).isFalse();

        String large = trigger("wf_expense_approval", "{\"employee_email\":\"a@example.com\",\"amount_usd\":250,\"description\":\"Ticket\"}");
        JsonNode paused = awaitRun(large, "waiting_approval");
        assertThat(step(paused, "finance_gate").path("status").asText()).isEqualTo("waiting");
        String approvalId = pendingApprovalFor(large);
        assertThat(get("/approvals/" + approvalId).body().path("message").asText()).contains("$250").contains("Ticket");

        Reply approve = post("/approvals/" + approvalId + "/approve", "{\"comment\":\"ok\"}");
        assertThat(approve.status()).isEqualTo(200);
        assertThat(approve.text("decided_by")).isEqualTo("demo");
        JsonNode done = awaitTerminal(large);
        assertThat(done.path("status").asText()).isEqualTo("succeeded");
        assertThat(step(done, "finance_gate").path("output").path("decision").asText()).isEqualTo("approved");
        assertThat(post("/approvals/" + approvalId + "/approve", null).status()).as("double decision").isEqualTo(409);
    }

    @Test
    void rejectingEndsTheRunCleanlyAsCancelledWithTheDecisionRecorded() {
        String run = trigger("wf_expense_approval", "{\"employee_email\":\"a@example.com\",\"amount_usd\":999,\"description\":\"Gold watch\"}");
        awaitRun(run, "waiting_approval");
        String approvalId = pendingApprovalFor(run);
        Reply reject = post("/approvals/" + approvalId + "/reject", "{\"comment\":\"no\"}");
        assertThat(reject.status()).isEqualTo(200);
        assertThat(reject.text("status")).isEqualTo("rejected");
        assertThat(reject.text("decided_by")).isEqualTo("demo");
        assertThat(reject.body().has("decided_at")).isTrue();
        JsonNode done = awaitTerminal(run);
        assertThat(done.path("status").asText()).isEqualTo("cancelled");
        assertThat(done.path("cancelled_reason").asText()).isEqualTo("approval_rejected");
        assertThat(WORLD.executed("email.send")).as("nothing notified after a rejection").isZero();
    }

    @Test
    void runawayWorkflowIsStoppedByTheStepCapWithAReasonNamingTheCap() {
        String run = trigger("wf_runaway", "{}");
        JsonNode done = awaitTerminal(run);
        assertThat(done.path("status").asText()).isEqualTo("failed");
        assertThat(done.path("error").path("code").asText()).isEqualTo("max_steps_exceeded");
        assertThat(done.path("error").path("message").asText()).contains("max_steps cap (12)");
        assertThat(done.path("steps_executed").asInt()).isEqualTo(12);
        assertThat(done.path("steps").size()).isEqualTo(12);
    }

    @Test
    void everyObservedStatusIsInTheDocumentedSet() {
        JsonNode runs = get("/runs?limit=200").body().path("items");
        for (JsonNode r : runs) assertThat(STATUS_VOCAB).contains(r.path("status").asText());
    }

    // ------------------------------------------------------------------ auth and hygiene

    @Test
    void platformApisRequireABearerToken() {
        assertThat(call(HttpMethod.GET, "/workflows", null, null).status()).isEqualTo(401);
        assertThat(call(HttpMethod.GET, "/workflows", null, "wrong-token").status()).isEqualTo(401);
        assertThat(call(HttpMethod.GET, "/runs", null, null).status()).isEqualTo(401);
        assertThat(call(HttpMethod.GET, "/approvals", null, null).status()).isEqualTo(401);
        assertThat(call(HttpMethod.GET, "/actuator/health", null, null).status()).isEqualTo(200);
    }

    @Test
    void rolesAreSeparated() {
        // alice is approver-only, bob is builder-only
        assertThat(call(HttpMethod.POST, "/workflows", TestSupport.minimalWorkflow("wf_role"), "alice-token").status()).isEqualTo(403);
        assertThat(call(HttpMethod.POST, "/workflows", TestSupport.minimalWorkflow("wf_role"), "bob-token").status()).isEqualTo(201);
        String run = trigger("wf_expense_approval", "{\"employee_email\":\"a@example.com\",\"amount_usd\":500,\"description\":\"x\"}");
        awaitRun(run, "waiting_approval");
        String approvalId = pendingApprovalFor(run);
        assertThat(call(HttpMethod.POST, "/approvals/" + approvalId + "/approve", null, "bob-token").status()).isEqualTo(403);
        Reply ok = call(HttpMethod.POST, "/approvals/" + approvalId + "/approve", null, "alice-token");
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.text("decided_by")).as("the decider is the authenticated principal").isEqualTo("alice");
        assertThat(call(HttpMethod.POST, "/runs/" + run + "/cancel", null, "alice-token").status()).isEqualTo(403);
    }

    @Test
    void webhookSecretsNeverAppearInApiResponses() {
        String wf = get("/workflows/wf_expense_approval").body().toString();
        assertThat(wf).doesNotContain("whsec_expense_774").contains("[REDACTED]");
        String list = get("/workflows").body().toString();
        assertThat(list).doesNotContain("whsec_");
        String run = trigger("wf_expense_approval", "{\"employee_email\":\"a@example.com\",\"amount_usd\":5,\"description\":\"x\"}");
        assertThat(awaitTerminal(run).toString()).doesNotContain("whsec_");
        assertThat(get("/workflows/wf_expense_approval/versions").body().toString()).doesNotContain("whsec_");
    }

    @Test
    void errorsUseOneShapeAndNeverLeakInternals() {
        Reply notFound = get("/runs/run_missing");
        assertThat(notFound.status()).isEqualTo(404);
        assertThat(notFound.body().path("error").path("code").asText()).isEqualTo("not_found");
        Reply badJson = json(HttpMethod.POST, "/workflows", "{not json", TOKEN);
        assertThat(badJson.status()).isEqualTo(400);
        assertThat(badJson.body().toString()).doesNotContain("Exception").doesNotContain("at com.");
        assertThat(post("/workflows/wf_never_published_xyz/trigger", "{}").status()).isEqualTo(404);
    }

    @Test
    void triggeringAnUnpublishedWorkflowIsRejected() {
        assertThat(post("/workflows", TestSupport.minimalWorkflow("wf_draft_only")).status()).isEqualTo(201);
        Reply r = post("/workflows/wf_draft_only/trigger", "{\"input\":{}}");
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.body().path("error").path("code").asText()).isEqualTo("workflow_not_published");
    }

    @Test
    void duplicateWorkflowIdIsAConflict() {
        assertThat(post("/workflows", TestSupport.minimalWorkflow("wf_dup")).status()).isEqualTo(201);
        assertThat(post("/workflows", TestSupport.minimalWorkflow("wf_dup")).status()).isEqualTo(409);
    }
}
