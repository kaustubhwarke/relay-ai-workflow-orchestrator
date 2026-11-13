package com.relay;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.relay.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

/** Engine semantics on a real database: gates, caps, retries, failures, cancel, replay, snapshots. */
class EngineBehaviorIT extends AbstractIntegrationTest {

    private static String wf(String id, String entry, String limits, String nodes) {
        return """
                {"id":"%s","name":"%s","trigger":{"type":"manual"},"entry":"%s","limits":%s,"nodes":%s}
                """.formatted(id, id, entry, limits, nodes);
    }

    private static final String LIMITS = "{\"max_steps\":20,\"timeout_seconds\":60,\"max_ai_tokens\":0}";

    private static String notifyNode(String id, String msg, String next) {
        return """
                {"id":"%s","type":"notify","params":{"channel":"chat","to":"#ops","message":"%s"},"next":%s}
                """.formatted(id, msg, next == null ? "null" : "\"" + next + "\"");
    }

    // ------------------------------------------------------------------ approval gate

    @Test
    void sensitiveNodeWithNoApprovalIsRefusedByTheEngineAtRuntime() {
        String id = createAndPublish(wf("wf_it_gate1", "refund", LIMITS, """
                [{"id":"refund","type":"order_action","params":{"action":"refund","order_id":"ord_2002"},"next":null}]"""));
        Reply published = get("/workflows/" + id);
        assertThat(published.text("status")).isEqualTo("published"); // publish only warns...
        JsonNode run = awaitTerminal(trigger(id, "{}"));
        assertThat(run.path("status").asText()).isEqualTo("failed"); // ...the engine refuses
        assertThat(run.path("error").path("code").asText()).isEqualTo("approval_required");
        assertThat(WORLD.executed("order.refund")).isZero();
        assertThat(WORLD.orderStatus("ord_2002")).isEqualTo("delivered");
    }

    @Test
    void publishResponseWarnsAboutUngatedSensitiveNodes() {
        post("/workflows", wf("wf_it_gate_warn", "refund", LIMITS, """
                [{"id":"refund","type":"order_action","params":{"action":"refund","order_id":"ord_2002"},"next":null}]"""));
        Reply pub = post("/workflows/wf_it_gate_warn/publish", null);
        assertThat(pub.status()).isEqualTo(200);
        assertThat(pub.body().path("warnings").toString()).contains("without passing an approval");
    }

    @Test
    void approvalOnTheOtherBranchDoesNotUnlockTheSensitiveNode() {
        // true -> gate -> refund ; false -> refund directly. We take the false branch.
        String id = createAndPublish(wf("wf_it_gate2", "pick", LIMITS, """
                [{"id":"pick","type":"condition","params":{"left":"{{trigger.body.useGate}}","op":"equals","right":"yes"},"on_true":"gate","on_false":"refund"},
                 {"id":"gate","type":"approval","params":{"message":"ok?"},"next":"refund"},
                 {"id":"refund","type":"order_action","params":{"action":"refund","order_id":"ord_2002"},"next":null}]"""));
        JsonNode run = awaitTerminal(trigger(id, "{\"useGate\":\"no\"}"));
        assertThat(run.path("status").asText()).isEqualTo("failed");
        assertThat(run.path("error").path("code").asText()).isEqualTo("approval_required");
        assertThat(WORLD.executed("order.refund")).isZero();
    }

    @Test
    void approvedRefundExecutesExactlyOnceWithAStableKeyAndRejectedOneNeverDoes() {
        String nodes = """
                [{"id":"gate","type":"approval","params":{"message":"Refund {{trigger.body.order}}?"},"next":"refund"},
                 {"id":"refund","type":"order_action","params":{"action":"refund","order_id":"{{trigger.body.order}}"},"next":null}]""";
        String id = createAndPublish(wf("wf_it_refund", "gate", LIMITS, nodes));

        String rejected = trigger(id, "{\"order\":\"ord_2003\"}");
        awaitRun(rejected, "waiting_approval");
        post("/approvals/" + pendingApprovalFor(rejected) + "/reject", null);
        assertThat(awaitTerminal(rejected).path("status").asText()).isEqualTo("cancelled");
        assertThat(WORLD.executed("order.refund")).isZero();

        String approved = trigger(id, "{\"order\":\"ord_2002\"}");
        awaitRun(approved, "waiting_approval");
        assertThat(WORLD.executed("order.refund")).as("nothing happens before a human approves").isZero();
        post("/approvals/" + pendingApprovalFor(approved) + "/approve", null);
        JsonNode done = awaitTerminal(approved);
        assertThat(done.path("status").asText()).isEqualTo("succeeded");
        assertThat(WORLD.executed("order.refund")).isEqualTo(1);
        assertThat(WORLD.ledger().stream().filter(e -> e.action().equals("order.refund")).findFirst().get().idempotencyKey())
                .isEqualTo(approved + ":refund");
        assertThat(step(done, "refund").path("output").path("reference_id").asText()).startsWith("ref_");
    }

    @Test
    void orderIdFromAnUntrustedPayloadCannotInjectPathSegments() {
        String nodes = """
                [{"id":"gate","type":"approval","params":{"message":"ok?"},"next":"refund"},
                 {"id":"refund","type":"order_action","params":{"action":"refund","order_id":"{{trigger.body.order}}"},"next":null}]""";
        String id = createAndPublish(wf("wf_it_pathinj", "gate", LIMITS, nodes));
        String run = trigger(id, "{\"order\":\"../admin/reset\"}");
        awaitRun(run, "waiting_approval");
        post("/approvals/" + pendingApprovalFor(run) + "/approve", null);
        JsonNode done = awaitTerminal(run);
        assertThat(done.path("status").asText()).isEqualTo("failed");
        assertThat(step(done, "refund").path("error_code").asText()).isEqualTo("invalid_param_value");
        assertThat(WORLD.ledger()).isEmpty();
    }

    // ------------------------------------------------------------------ retries and failures

    @Test
    void transientFailuresAreRetriedWithBackoffAndTheEffectLandsOnce() {
        String id = createAndPublish(wf("wf_it_retry", "n", LIMITS, "[" + notifyNode("n", "hello", null) + "]"));
        WORLD.failNext(2, 503);
        JsonNode run = awaitTerminal(trigger(id, "{}"));
        assertThat(run.path("status").asText()).isEqualTo("succeeded");
        assertThat(step(run, "n").path("attempt").asInt()).isEqualTo(3);
        assertThat(WORLD.executed("chat.message")).isEqualTo(1);
    }

    @Test
    void exhaustedRetriesFailTheRunWithTheErrorRecorded() {
        String id = createAndPublish(wf("wf_it_exhaust", "n", LIMITS,
                "[{\"id\":\"n\",\"type\":\"notify\",\"retry\":{\"max_attempts\":2,\"base_delay_ms\":20},"
                        + "\"params\":{\"channel\":\"chat\",\"to\":\"#ops\",\"message\":\"x\"},\"next\":null}]"));
        WORLD.setDown(true);
        JsonNode run = awaitTerminal(trigger(id, "{}"));
        assertThat(run.path("status").asText()).isEqualTo("failed");
        assertThat(run.path("error").path("code").asText()).isEqualTo("dependency_unavailable");
        assertThat(run.path("error").path("node_id").asText()).isEqualTo("n");
        assertThat(step(run, "n").path("attempt").asInt()).isEqualTo(2);
        assertThat(step(run, "n").path("status").asText()).isEqualTo("failed");
    }

    @Test
    void permanentRejectionsAreNotRetried() {
        String id = createAndPublish(wf("wf_it_perm", "n", LIMITS,
                "[{\"id\":\"n\",\"type\":\"http_request\",\"params\":{\"method\":\"GET\",\"url\":\"" + WORLD.url() + "/orders/nope\"},\"next\":null}]"));
        JsonNode run = awaitTerminal(trigger(id, "{}"));
        assertThat(run.path("status").asText()).isEqualTo("failed");
        assertThat(step(run, "n").path("attempt").asInt()).isEqualTo(1);
        assertThat(step(run, "n").path("error_code").asText()).isEqualTo("dependency_rejected");
    }

    @Test
    void failedRunCanBeReplayedFromTheFailedStepKeepingItsIdempotencyKey() {
        // a -> 1s delay -> b. We take the world down after a landed, so b fails once the delay elapses.
        String id = createAndPublish(wf("wf_it_replay", "a", LIMITS,
                "[" + notifyNode("a", "first", "d") + ",{\"id\":\"d\",\"type\":\"delay\",\"params\":{\"seconds\":1},\"next\":\"b\"},"
                        + "{\"id\":\"b\",\"type\":\"notify\",\"retry\":{\"max_attempts\":2,\"base_delay_ms\":20},"
                        + "\"params\":{\"channel\":\"chat\",\"to\":\"#ops\",\"message\":\"second\"},\"next\":null}]"));
        String original = trigger(id, "{}");
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).until(() -> WORLD.executed("chat.message") >= 1);
        WORLD.setDown(true);
        JsonNode failed = awaitTerminal(original);
        assertThat(failed.path("status").asText()).isEqualTo("failed");
        assertThat(WORLD.executed("chat.message")).isEqualTo(1);

        WORLD.setDown(false);
        Reply replay = post("/runs/" + original + "/replay", null);
        assertThat(replay.status()).isEqualTo(202);
        JsonNode replayed = awaitTerminal(replay.text("run_id"));
        assertThat(replayed.path("status").asText()).isEqualTo("succeeded");
        assertThat(replayed.path("replay_of_run_id").asText()).isEqualTo(original);
        assertThat(replayed.path("steps").size()).isEqualTo(3);
        assertThat(WORLD.executed("chat.message")).as("step a is not re-executed; b ran once").isEqualTo(2);
        assertThat(WORLD.ledger().stream().filter(e -> e.status() < 300 && !e.replayed()).map(e -> e.idempotencyKey()).toList())
                .containsExactly(original + ":a", original + ":b"); // b keeps the original run's key
        assertThat(post("/runs/" + replay.text("run_id") + "/replay", null).status()).as("only failed runs replay").isEqualTo(409);
    }

    @Test
    void unresolvableTemplateFailsTheStepWithAClearError() {
        String id = createAndPublish(wf("wf_it_tmpl", "n", LIMITS,
                "[" + notifyNode("n", "{{trigger.body.missing}}", null) + "]"));
        JsonNode run = awaitTerminal(trigger(id, "{}"));
        assertThat(run.path("status").asText()).isEqualTo("failed");
        assertThat(step(run, "n").path("error_code").asText()).isEqualTo("template_error");
        assertThat(step(run, "n").path("error").asText()).contains("trigger.body.missing");
        assertThat(WORLD.ledger()).isEmpty();
    }

    @Test
    void ordinalConditionOnNonNumbersFailsClearly() {
        String id = createAndPublish(wf("wf_it_cond", "c", LIMITS, """
                [{"id":"c","type":"condition","params":{"left":"abc","op":"greater_than","right":"100"},"on_true":"d","on_false":"d"},
                 {"id":"d","type":"delay","params":{"seconds":0},"next":null}]"""));
        JsonNode run = awaitTerminal(trigger(id, "{}"));
        assertThat(run.path("status").asText()).isEqualTo("failed");
        assertThat(step(run, "c").path("error_code").asText()).isEqualTo("condition_error");
    }

    @Test
    void httpRequestToANonAllowListedHostIsBlocked() {
        String id = createAndPublish(wf("wf_it_ssrf", "n", LIMITS, """
                [{"id":"n","type":"http_request","params":{"method":"GET","url":"http://169.254.169.254/latest/meta-data/"},"next":null}]"""));
        JsonNode run = awaitTerminal(trigger(id, "{}"));
        assertThat(run.path("status").asText()).isEqualTo("failed");
        assertThat(step(run, "n").path("error_code").asText()).isEqualTo("egress_blocked");
        assertThat(step(run, "n").path("attempt").asInt()).isEqualTo(1);
    }

    @Test
    void mutatingHttpRequestsCarryAnIdempotencyKey() {
        String id = createAndPublish(wf("wf_it_post", "n", LIMITS, "[{\"id\":\"n\",\"type\":\"http_request\",\"params\":{\"method\":\"POST\","
                + "\"url\":\"" + WORLD.url() + "/shipments\",\"body\":{\"order_id\":\"ord_2003\"}},\"next\":null}]"));
        String run = trigger(id, "{}");
        JsonNode done = awaitTerminal(run);
        assertThat(done.path("status").asText()).isEqualTo("succeeded");
        assertThat(WORLD.ledger().get(0).idempotencyKey()).isEqualTo(run + ":n");
        assertThat(step(done, "n").path("output").path("body").path("shipment_id").asText()).startsWith("shp_");
    }

    @Test
    void sensitiveHeaderValuesAreRedactedInTheTrace() {
        String id = createAndPublish(wf("wf_it_redact", "n", LIMITS, "[{\"id\":\"n\",\"type\":\"http_request\",\"params\":{\"method\":\"GET\","
                + "\"url\":\"" + WORLD.url() + "/orders/ord_2001\",\"headers\":{\"Authorization\":\"Bearer super-secret\",\"X-Trace\":\"abc\"}},\"next\":null}]"));
        JsonNode done = awaitTerminal(trigger(id, "{}"));
        String input = step(done, "n").path("input").toString();
        assertThat(input).doesNotContain("super-secret").contains("***REDACTED***").contains("abc");
    }

    // ------------------------------------------------------------------ caps

    @Test
    void stepCapStopsAnyLoopAndCountsEveryExecutionOfARepeatedNode() {
        String id = createAndPublish(wf("wf_it_cap", "a", "{\"max_steps\":3}", """
                [{"id":"a","type":"delay","params":{"seconds":0},"next":"a"}]"""));
        JsonNode run = awaitTerminal(trigger(id, "{}"));
        assertThat(run.path("status").asText()).isEqualTo("failed");
        assertThat(run.path("steps_executed").asInt()).isEqualTo(3);
        assertThat(run.path("error").path("cap").asText()).isEqualTo("max_steps");
    }

    @Test
    void loopIterationsOfASideEffectNodeGetDistinctIdempotencyKeys() {
        String id = createAndPublish(wf("wf_it_loopkey", "n", "{\"max_steps\":4}", """
                [{"id":"n","type":"notify","params":{"channel":"chat","to":"#ops","message":"tick"},"next":"n"}]"""));
        String run = trigger(id, "{}");
        awaitTerminal(run);
        assertThat(WORLD.ledger().stream().map(e -> e.idempotencyKey()).toList())
                .containsExactly(run + ":n", run + ":n:2", run + ":n:3", run + ":n:4");
        assertThat(WORLD.executed("chat.message")).isEqualTo(4);
    }

    @Test
    void timeSpentWaitingForAnApprovalDoesNotCountTowardsTheWallClockCap() throws Exception {
        String id = createAndPublish(wf("wf_it_timeout_approval", "g", "{\"max_steps\":10,\"timeout_seconds\":2}", """
                [{"id":"g","type":"approval","params":{"message":"ok to continue?"},"next":"n"},
                 {"id":"n","type":"notify","params":{"channel":"chat","to":"#ops","message":"done"},"next":null}]"""));
        String run = trigger(id, "{}");
        awaitRun(run, "waiting_approval");
        Thread.sleep(3500); // longer than the 2s cap: this wait must not be billed to the run
        assertThat(post("/approvals/" + pendingApprovalFor(run) + "/approve", "{}").status()).isEqualTo(200);
        JsonNode done = awaitTerminal(run);
        assertThat(done.path("status").asText()).isEqualTo("succeeded");
        assertThat(WORLD.executed("chat.message")).isEqualTo(1);
    }

    @Test
    void wallClockCapStopsSlowRunsThatAreNotWaitingForAnApproval() {
        String id = createAndPublish(wf("wf_it_timeout", "d", "{\"max_steps\":10,\"timeout_seconds\":1}", """
                [{"id":"d","type":"delay","params":{"seconds":2},"next":"n"},
                 {"id":"n","type":"notify","params":{"channel":"chat","to":"#ops","message":"late"},"next":null}]"""));
        JsonNode run = awaitTerminal(trigger(id, "{}"));
        assertThat(run.path("status").asText()).isEqualTo("failed");
        assertThat(run.path("error").path("code").asText()).isEqualTo("timeout_exceeded");
        assertThat(WORLD.ledger()).isEmpty();
    }

    private static final String AI_SCHEMA = "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\"}},\"required\":[\"x\"]}";

    private static String twoAiNodes(String id, int maxTokens) {
        return wf(id, "a1", "{\"max_steps\":10,\"max_ai_tokens\":" + maxTokens + "}", """
                [{"id":"a1","type":"ai","params":{"prompt":"say something please","output_schema":%s},"next":"a2"},
                 {"id":"a2","type":"ai","params":{"prompt":"again","output_schema":%s},"next":null}]""".formatted(AI_SCHEMA, AI_SCHEMA));
    }

    private int tokensOfStep(JsonNode run, String nodeId) {
        JsonNode t = step(run, nodeId).path("tokens");
        return t.path("prompt").asInt() + t.path("completion").asInt();
    }

    @Test
    void anAiStepThatTakesTheRunOverItsTokenBudgetFailsAndItsOutputIsNeverUsed() {
        String id = createAndPublish(twoAiNodes("wf_it_tokens", 5));
        JsonNode run = awaitTerminal(trigger(id, "{}"));
        assertThat(run.path("status").asText()).isEqualTo("failed");
        assertThat(run.path("error").path("code").asText()).isEqualTo("max_ai_tokens_exceeded");
        assertThat(run.path("error").path("node_id").asText()).as("the first AI node already crosses a 5-token budget").isEqualTo("a1");
        assertThat(run.path("ai_tokens_used").asInt()).isGreaterThan(5);
        assertThat(step(run, "a1").path("tokens").path("prompt").asInt()).isGreaterThan(0);
        assertThat(run.path("steps")).hasSize(1);
    }

    @Test
    void theTokenBudgetAlsoStopsTheSecondAiNodeOnceTheTotalWouldExceedIt() {
        JsonNode uncapped = awaitTerminal(trigger(createAndPublish(twoAiNodes("wf_it_tokens_probe", 0)), "{}"));
        int first = tokensOfStep(uncapped, "a1");
        assertThat(first).isGreaterThan(0);

        String id = createAndPublish(twoAiNodes("wf_it_tokens2", first + first / 2)); // room for a1, not for a1 + a2
        JsonNode run = awaitTerminal(trigger(id, "{}"));
        assertThat(step(run, "a1").path("status").asText()).isEqualTo("succeeded");
        assertThat(run.path("status").asText()).isEqualTo("failed");
        assertThat(run.path("error").path("code").asText()).isEqualTo("max_ai_tokens_exceeded");
        assertThat(run.path("error").path("node_id").asText()).isEqualTo("a2");
    }

    // ------------------------------------------------------------------ delays, cancel, snapshots

    @Test
    void delayIsDurableAndTheRunContinuesAfterIt() {
        String id = createAndPublish(wf("wf_it_delay", "a", LIMITS, "[" + notifyNode("a", "one", "d")
                + ",{\"id\":\"d\",\"type\":\"delay\",\"params\":{\"seconds\":1},\"next\":\"b\"}," + notifyNode("b", "two", null) + "]"));
        String run = trigger(id, "{}");
        JsonNode done = awaitTerminal(run);
        assertThat(done.path("status").asText()).isEqualTo("succeeded");
        assertThat(step(done, "d").path("status").asText()).isEqualTo("succeeded");
        assertThat(WORLD.executed("chat.message")).isEqualTo(2);
    }

    @Test
    void cancellingAWaitingRunClosesItsApprovalAndTerminalRunsReturn409() {
        String run = trigger("wf_expense_approval", "{\"employee_email\":\"a@example.com\",\"amount_usd\":300,\"description\":\"x\"}");
        awaitRun(run, "waiting_approval");
        String approvalId = pendingApprovalFor(run);
        Reply cancel = post("/runs/" + run + "/cancel", null);
        assertThat(cancel.status()).isEqualTo(200);
        assertThat(cancel.text("status")).isEqualTo("cancelled");
        assertThat(cancel.text("cancelled_reason")).isEqualTo("cancel_requested");
        assertThat(get("/approvals/" + approvalId).text("status")).isEqualTo("cancelled");
        assertThat(get("/approvals?status=pending").body().toString()).doesNotContain(approvalId);
        assertThat(post("/approvals/" + approvalId + "/approve", null).status()).isEqualTo(409);
        Reply again = post("/runs/" + run + "/cancel", null);
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.body().path("error").path("code").asText()).isEqualTo("run_already_terminal");
    }

    @Test
    void cancellingARunDuringADelayStopsItWithoutRunningLaterNodes() {
        String id = createAndPublish(wf("wf_it_cancel_delay", "d", LIMITS, "[{\"id\":\"d\",\"type\":\"delay\",\"params\":{\"seconds\":30},\"next\":\"n\"},"
                + notifyNode("n", "should never send", null) + "]"));
        String run = trigger(id, "{}");
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).until(() -> {
            JsonNode r = get("/runs/" + run).body();
            return r.path("steps").size() == 1 && r.path("steps").get(0).path("status").asText().equals("waiting");
        });
        assertThat(post("/runs/" + run + "/cancel", null).status()).isEqualTo(200);
        JsonNode done = awaitTerminal(run);
        assertThat(done.path("status").asText()).isEqualTo("cancelled");
        assertThat(WORLD.ledger()).isEmpty();
    }

    @Test
    void editingAndRepublishingNeverChangesWhatAnInFlightRunExecutes() {
        String v1 = wf("wf_it_snapshot", "gate", LIMITS, """
                [{"id":"gate","type":"approval","params":{"message":"ok?"},"next":"n"},
                 {"id":"n","type":"notify","params":{"channel":"chat","to":"#ops","message":"ORIGINAL"},"next":null}]""");
        String id = createAndPublish(v1);
        String paused = trigger(id, "{}");
        awaitRun(paused, "waiting_approval");

        Reply updated = put("/workflows/" + id, v1.replace("ORIGINAL", "CHANGED"));
        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.text("status")).isEqualTo("draft");
        assertThat(updated.body().path("has_unpublished_changes").asBoolean()).isTrue();
        assertThat(post("/workflows/" + id + "/publish", null).body().path("current_version").asInt()).isEqualTo(2);

        post("/approvals/" + pendingApprovalFor(paused) + "/approve", null);
        JsonNode done = awaitTerminal(paused);
        assertThat(done.path("workflow_version").asInt()).isEqualTo(1);
        assertThat(step(done, "n").path("input").path("message").asText()).isEqualTo("ORIGINAL");

        String fresh = trigger(id, "{}");
        awaitRun(fresh, "waiting_approval");
        post("/approvals/" + pendingApprovalFor(fresh) + "/approve", null);
        JsonNode freshDone = awaitTerminal(fresh);
        assertThat(freshDone.path("workflow_version").asInt()).isEqualTo(2);
        assertThat(step(freshDone, "n").path("input").path("message").asText()).isEqualTo("CHANGED");
    }
}
