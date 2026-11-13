package com.relay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.relay.domain.ApprovalStatus;
import com.relay.domain.RunStatus;
import com.relay.repository.ApprovalRepository;
import com.relay.repository.RunRepository;
import com.relay.repository.ScheduleRepository;
import com.relay.repository.StepRepository;
import com.relay.service.SeedLoader;
import com.relay.support.AbstractIntegrationTest;
import com.relay.web.ApiExceptionHandler;
import com.relay.worker.ScheduleDispatcher;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Behaviour tightened during code review: authorization, input bounds, repository guards, schedules and quarantine. */
class ReviewHardeningIT extends AbstractIntegrationTest {

    @Autowired JdbcClient jdbc;
    @Autowired ApprovalRepository approvals;
    @Autowired RunRepository runs;
    @Autowired StepRepository steps;
    @Autowired ScheduleRepository schedules;
    @Autowired ScheduleDispatcher dispatcher;
    @Autowired SeedLoader seedLoader;
    @Autowired ApiExceptionHandler exceptionHandler;

    private static String webhookWorkflow(String id, String secretField) {
        return """
                {"id":"%s","name":"hook","trigger":{"type":"webhook"%s},"entry":"n",
                 "nodes":[{"id":"n","type":"notify","params":{"channel":"chat","to":"#t","message":"hi"},"next":null}]}"""
                .formatted(id, secretField);
    }

    // ------------------------------------------------------------------ authorization and bounds

    @Test
    void compilingWorkflowsRequiresTheBuilderRole() {
        String body = "{\"description\":\"notify the team\"}";
        assertThat(call(HttpMethod.POST, "/workflows/compile", body, null).status()).isEqualTo(401);
        assertThat(call(HttpMethod.POST, "/workflows/compile", body, "alice-token").status()).as("an approver is not a builder").isEqualTo(403);
        assertThat(call(HttpMethod.POST, "/workflows/compile/eval", null, "alice-token").status()).isEqualTo(403);
    }

    @Test
    void listLimitsAndOffsetsAreClampedAndReportedConsistently() {
        for (int i = 0; i < 3; i++) trigger(createAndPublish(TestSupport.minimalWorkflow("wf_bounds_" + i)), "{}");
        Reply big = get("/runs?limit=100000&offset=-9");
        assertThat(big.status()).isEqualTo(200);
        assertThat(big.body().path("limit").asInt()).isEqualTo(200);
        assertThat(big.body().path("offset").asInt()).isZero();
        assertThat(big.body().path("items").size()).isLessThanOrEqualTo(200);
        Reply tiny = get("/runs?limit=-4");
        assertThat(tiny.body().path("limit").asInt()).isEqualTo(1);
        assertThat(tiny.body().path("items")).hasSizeLessThanOrEqualTo(1);
        assertThat(get("/approvals?limit=-3&offset=-3").status()).isEqualTo(200);
        assertThat(get("/approvals?limit=99999").status()).isEqualTo(200);
    }

    @Test
    void aWhitespaceOnlyWebhookBodyIsTreatedAsAnEmptyPayload() {
        String id = createAndPublish(webhookWorkflow("wf_hook_blank", ",\"secret\":\"s3cr3t-value\""));
        Reply r = call(HttpMethod.POST, "/hooks/" + id, "   \n ", null, "X-Relay-Secret", "s3cr3t-value");
        assertThat(r.status()).isEqualTo(202);
        assertThat(awaitTerminal(r.text("run_id")).path("input").isObject()).isTrue();
    }

    @Test
    void theExceptionHandlerAnswersClientMistakesWith4xxNotWith500() {
        var missing = exceptionHandler.missingParam(new org.springframework.web.bind.MissingServletRequestParameterException("q", "String"));
        assertThat(missing.getStatusCode().value()).isEqualTo(400);
        assertThat(missing.getBody().error().code()).isEqualTo("missing_parameter");
        org.springframework.core.MethodParameter anyParameter;
        try {
            anyParameter = new org.springframework.core.MethodParameter(Object.class.getMethod("toString"), -1);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
        var invalid = exceptionHandler.invalidArgument(new org.springframework.web.bind.MethodArgumentNotValidException(
                anyParameter, new org.springframework.validation.BeanPropertyBindingResult(new Object(), "x")));
        assertThat(invalid.getStatusCode().value()).isEqualTo(400);
    }

    // ------------------------------------------------------------------ secrets in drafts

    @Test
    void dryRunValidationTreatsTheRedactionMarkerAsNoSecretJustLikePublish() {
        Reply withMarker = post("/workflows/validate", webhookWorkflow("wf_dry_marker", ",\"secret\":\"[REDACTED]\""));
        assertThat(withMarker.body().path("valid").asBoolean()).isFalse();
        Reply real = post("/workflows/validate", webhookWorkflow("wf_dry_real", ",\"secret\":\"a-real-secret\""));
        assertThat(real.body().path("valid").asBoolean()).isTrue();
    }

    @Test
    void removingTheTriggerClearsTheStoredWebhookSecretSoItIsNeverSilentlyReused() {
        String id = "wf_secret_reset";
        assertThat(post("/workflows", webhookWorkflow(id, ",\"secret\":\"old-secret\"")).status()).isEqualTo(201);
        assertThat(jdbc.sql("SELECT webhook_secret_hash FROM workflows WHERE id = :i").param("i", id).query(String.class).single()).isNotBlank();

        String noTrigger = """
                {"id":"%s","name":"hook","entry":"n","nodes":[{"id":"n","type":"notify","params":{"channel":"chat","to":"#t","message":"hi"},"next":null}]}""".formatted(id);
        assertThat(put("/workflows/" + id, noTrigger).status()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT webhook_secret_hash FROM workflows WHERE id = :i").param("i", id).query(String.class).optional()).isEmpty();

        assertThat(put("/workflows/" + id, webhookWorkflow(id, "")).status()).isEqualTo(200);
        Reply publish = post("/workflows/" + id + "/publish", null);
        assertThat(publish.status()).as("the old secret must not come back to life").isEqualTo(422);
    }

    // ------------------------------------------------------------------ repository guards

    @Test
    void approvalDecisionsMustBeApprovedOrRejected() {
        for (ApprovalStatus bad : new ApprovalStatus[] {ApprovalStatus.PENDING, ApprovalStatus.CANCELLED}) {
            assertThatThrownBy(() -> approvals.decide("apr_x", bad, "me", null)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void runsOnlyFinishInTerminalStatesAndTransitionsStayNonTerminal() {
        assertThatThrownBy(() -> runs.finish("run_x", RunStatus.RUNNING, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> runs.finish("run_x", RunStatus.WAITING_APPROVAL, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> runs.transition("run_x", RunStatus.RUNNING, RunStatus.FAILED)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aSettledStepCannotBeOverwrittenByALateWriter() {
        String run = trigger(createAndPublish(TestSupport.minimalWorkflow("wf_settled")), "{}");
        awaitTerminal(run);
        var before = steps.findByRunId(run).get(0);
        assertThat(before.status().value()).isEqualTo("succeeded");
        assertThat(steps.markFailed(run, before.sequence(), "late failure", "late", 1, com.relay.domain.AiUsage.NONE)).isFalse();
        assertThat(steps.markSucceeded(run, before.sequence(), mapper.createObjectNode().put("x", 1), 9, com.relay.domain.AiUsage.NONE)).isFalse();
        var after = steps.findByRunId(run).get(0);
        assertThat(after.status().value()).isEqualTo("succeeded");
        assertThat(after.output()).isEqualTo(before.output());
        assertThat(after.attempt()).isEqualTo(before.attempt());
    }

    @Test
    void lockingDueSchedulesOutsideATransactionIsRefused() {
        assertThatThrownBy(() -> schedules.lockDue()).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    // ------------------------------------------------------------------ schedules, seeds, quarantine

    @Test
    void aScheduleThatFailsToFireIsRetriedSoonInsteadOfLosingItsWindow() {
        String id = createAndPublish("""
                {"id":"wf_cron_fail","name":"cron","trigger":{"type":"schedule","cron":"* * * * *"},"entry":"n",
                 "nodes":[{"id":"n","type":"notify","params":{"channel":"chat","to":"#t","message":"hi"},"next":null}]}""");
        // make the firing fail: the version it would run no longer exists
        jdbc.sql("DELETE FROM workflow_versions WHERE workflow_id = :w").param("w", id).update();
        jdbc.sql("UPDATE schedules SET next_fire_at = now() - interval '5 minutes' WHERE workflow_id = :w").param("w", id).update();

        assertThat(dispatcher.fireDue()).isZero();
        Double secondsAhead = jdbc.sql("SELECT EXTRACT(EPOCH FROM (next_fire_at - now())) FROM schedules WHERE workflow_id = :w")
                .param("w", id).query(Double.class).single();
        assertThat(secondsAhead).as("retried in about a minute, not skipped to the next cron window and not hot-looped")
                .isBetween(40.0, 70.0);
    }

    @Test
    void anUnpublishedSeedLeftByAnEarlierStartIsCompletedOnTheNextStart() throws Exception {
        jdbc.sql("DELETE FROM workflow_versions WHERE workflow_id = 'wf_slow_fulfillment'").update();
        jdbc.sql("UPDATE workflows SET status = 'draft', current_version = 0 WHERE id = 'wf_slow_fulfillment'").update();
        seedLoader.run(null);
        assertThat(jdbc.sql("SELECT current_version FROM workflows WHERE id = 'wf_slow_fulfillment'").query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT status FROM workflows WHERE id = 'wf_slow_fulfillment'").query(String.class).single()).isEqualTo("published");
        seedLoader.run(null); // and loading again changes nothing
        assertThat(jdbc.sql("SELECT current_version FROM workflows WHERE id = 'wf_slow_fulfillment'").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void aRunClaimedTooManyTimesIsQuarantinedInsteadOfLoopingForever() {
        String id = createAndPublish("""
                {"id":"wf_poison","name":"p","trigger":{"type":"manual"},"entry":"d",
                 "nodes":[{"id":"d","type":"delay","params":{"seconds":300},"next":null}]}""");
        String run = trigger(id, "{}");
        awaitRun(run, "running");
        await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.sql("SELECT COUNT(*) FROM queue_jobs WHERE run_id = :r AND locked_by IS NULL")
                .param("r", run).query(Integer.class).single() == 1);
        jdbc.sql("UPDATE queue_jobs SET claim_count = 500, available_at = now() WHERE run_id = :r").param("r", run).update();

        JsonNode failed = awaitTerminal(run);
        assertThat(failed.path("status").asText()).isEqualTo("failed");
        assertThat(failed.path("error").path("code").asText()).isEqualTo("max_claims_exceeded");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM queue_jobs WHERE run_id = :r").param("r", run).query(Integer.class).single()).isZero();
    }
}
