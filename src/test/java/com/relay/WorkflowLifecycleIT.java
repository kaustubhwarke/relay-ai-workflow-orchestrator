package com.relay;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.relay.support.AbstractIntegrationTest;
import com.relay.worker.ScheduleDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Draft/publish lifecycle, immutable versions, secret handling and schedule triggers. */
class WorkflowLifecycleIT extends AbstractIntegrationTest {

    @Autowired ScheduleDispatcher dispatcher;
    @Autowired JdbcClient jdbc;

    private static String webhookWf(String id, String name, String secret) {
        return """
                {"id":"%s","name":"%s","trigger":{"type":"webhook","secret":"%s"},"entry":"n",
                 "nodes":[{"id":"n","type":"notify","params":{"channel":"chat","to":"#ops","message":"hook"},"next":null}]}"""
                .formatted(id, name, secret);
    }

    private int hook(String id, String secret) {
        return call(HttpMethod.POST, "/hooks/" + id, "{}", null, "X-Relay-Secret", secret).status();
    }

    @Test
    void everyPublishAppendsAnImmutableNumberedVersionAndRunsRecordTheirs() {
        String id = createAndPublish(TestSupport.minimalWorkflow("wf_life_versions"));
        String r1 = trigger(id, "{}");
        put("/workflows/" + id, TestSupport.minimalWorkflow("wf_life_versions").replace("\"hi\"", "\"hi again\""));
        assertThat(post("/workflows/" + id + "/publish", null).body().path("current_version").asInt()).isEqualTo(2);
        String r2 = trigger(id, "{}");

        JsonNode versions = get("/workflows/" + id + "/versions").body();
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).path("version").asInt()).isEqualTo(2);
        assertThat(versions.get(1).path("definition").toString()).contains("\"hi\"").doesNotContain("hi again");
        assertThat(awaitTerminal(r1).path("workflow_version").asInt()).isEqualTo(1);
        assertThat(awaitTerminal(r2).path("workflow_version").asInt()).isEqualTo(2);
    }

    @Test
    void editingAPublishedWorkflowKeepsTheLastPublishedVersionRunnable() {
        String id = createAndPublish(TestSupport.minimalWorkflow("wf_life_edit"));
        Reply updated = put("/workflows/" + id, TestSupport.minimalWorkflow("wf_life_edit").replace("Minimal", "Renamed"));
        assertThat(updated.text("status")).isEqualTo("draft");
        assertThat(updated.body().path("has_unpublished_changes").asBoolean()).isTrue();
        assertThat(awaitTerminal(trigger(id, "{}")).path("workflow_version").asInt()).isEqualTo(1);
        assertThat(put("/workflows/" + id, TestSupport.minimalWorkflow("another_id")).status())
                .as("id in the body must match the URL").isEqualTo(400);
    }

    @Test
    void webhookSecretLifecycleKeepsReplacesAndNeverEchoesSecrets() {
        String id = createAndPublish(webhookWf("wf_life_secret", "v1", "s3cret-one"));
        assertThat(hook(id, "s3cret-one")).isEqualTo(202);
        assertThat(hook(id, "s3cret-two")).isEqualTo(401);

        // update without sending the secret (the API only ever shows a redaction marker): the secret is kept
        put("/workflows/" + id, webhookWf(id, "v2", WorkflowMarker.REDACTED));
        post("/workflows/" + id + "/publish", null);
        assertThat(hook(id, "s3cret-one")).isEqualTo(202);

        // rotate: only the new secret works once the new version is published
        put("/workflows/" + id, webhookWf(id, "v3", "s3cret-two"));
        assertThat(hook(id, "s3cret-one")).as("old version still published").isEqualTo(202);
        post("/workflows/" + id + "/publish", null);
        assertThat(hook(id, "s3cret-two")).isEqualTo(202);
        assertThat(hook(id, "s3cret-one")).isEqualTo(401);

        assertThat(get("/workflows/" + id).body().toString()).doesNotContain("s3cret");
        assertThat(get("/workflows/" + id + "/versions").body().toString()).doesNotContain("s3cret");
        String stored = jdbc.sql("SELECT definition::text FROM workflows WHERE id = :id").param("id", id).query(String.class).single();
        assertThat(stored).as("secrets are stored only as hashes").doesNotContain("s3cret");
    }

    @Test
    void webhookOnAManualWorkflowIsRejectedAndUnpublishedWebhookDoesNotRun() {
        String manual = createAndPublish(TestSupport.minimalWorkflow("wf_life_manual"));
        assertThat(hook(manual, "anything")).isEqualTo(409);
        assertThat(post("/workflows", webhookWf("wf_life_unpub", "u", "sec")).status()).isEqualTo(201);
        assertThat(hook("wf_life_unpub", "sec")).isEqualTo(409);
        assertThat(hook("wf_life_unpub", "bad")).as("wrong secret never learns publish state").isEqualTo(401);
    }

    @Test
    void scheduleTriggersFireOnceWithoutCatchUpAndStopWhenTheTriggerChanges() {
        String def = """
                {"id":"wf_life_cron","name":"cron","trigger":{"type":"schedule","cron":"* * * * *"},"entry":"n",
                 "nodes":[{"id":"n","type":"notify","params":{"channel":"chat","to":"#ops","message":"tick"},"next":null}]}""";
        createAndPublish(def);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM schedules WHERE workflow_id = 'wf_life_cron' AND enabled").query(Integer.class).single()).isEqualTo(1);

        // pretend 10 minutes of missed windows
        jdbc.sql("UPDATE schedules SET next_fire_at = now() - interval '10 minutes' WHERE workflow_id = 'wf_life_cron'").update();
        assertThat(dispatcher.fireDue()).isEqualTo(1);
        assertThat(dispatcher.fireDue()).as("no catch-up for missed windows").isZero();

        JsonNode runs = get("/runs?workflow_id=wf_life_cron").body().path("items");
        assertThat(runs).hasSize(1);
        assertThat(runs.get(0).path("trigger_type").asText()).isEqualTo("schedule");
        assertThat(awaitTerminal(runs.get(0).path("run_id").asText()).path("status").asText()).isEqualTo("succeeded");
        assertThat(jdbc.sql("SELECT next_fire_at > now() FROM schedules WHERE workflow_id = 'wf_life_cron'").query(Boolean.class).single()).isTrue();

        put("/workflows/wf_life_cron", TestSupport.minimalWorkflow("wf_life_cron"));
        post("/workflows/wf_life_cron/publish", null);
        assertThat(jdbc.sql("SELECT enabled FROM schedules WHERE workflow_id = 'wf_life_cron'").query(Boolean.class).single()).isFalse();
    }

    @Test
    void invalidCronIsRejectedAtPublish() {
        post("/workflows", """
                {"id":"wf_life_badcron","name":"x","trigger":{"type":"schedule","cron":"nope"},"entry":"n",
                 "nodes":[{"id":"n","type":"delay","params":{"seconds":1},"next":null}]}""");
        Reply r = post("/workflows/wf_life_badcron/publish", null);
        assertThat(r.status()).isEqualTo(422);
        assertThat(r.body().path("error").path("code").asText()).isEqualTo("invalid_cron");
    }

    /** The marker the API uses in place of secrets. */
    private static final class WorkflowMarker {
        static final String REDACTED = com.relay.service.WorkflowService.REDACTED;
    }
}
