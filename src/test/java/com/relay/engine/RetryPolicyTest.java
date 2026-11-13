package com.relay.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.relay.config.RelayProperties;
import com.relay.definition.WorkflowDefinition;
import com.relay.TestSupport;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    private final RelayProperties props = new RelayProperties();
    private final RetryPolicy policy = new RetryPolicy(props);

    @Test
    void backoffDoublesUntilTheCap() {
        RetryPolicy.Settings s = new RetryPolicy.Settings(10, 500, 5000);
        assertThat(RetryPolicy.nominalBackoffMillis(s, 1)).isEqualTo(500);
        assertThat(RetryPolicy.nominalBackoffMillis(s, 2)).isEqualTo(1000);
        assertThat(RetryPolicy.nominalBackoffMillis(s, 3)).isEqualTo(2000);
        assertThat(RetryPolicy.nominalBackoffMillis(s, 4)).isEqualTo(4000);
        assertThat(RetryPolicy.nominalBackoffMillis(s, 5)).isEqualTo(5000);
        assertThat(RetryPolicy.nominalBackoffMillis(s, 30)).isEqualTo(5000);
    }

    @Test
    void jitterStaysWithinTwentyPercent() {
        RetryPolicy.Settings s = new RetryPolicy.Settings(5, 1000, 60_000);
        for (int i = 0; i < 500; i++) {
            long v = policy.backoffMillis(s, 2); // nominal 2000
            assertThat(v).isBetween(1600L, 2400L);
        }
    }

    @Test
    void nodeLevelRetryOverridesTheDefaults() {
        WorkflowDefinition d = WorkflowDefinition.parse(TestSupport.json("""
                {"id":"x","name":"x","trigger":{"type":"manual"},"entry":"a","nodes":[
                  {"id":"a","type":"delay","params":{"seconds":1},"retry":{"max_attempts":7,"base_delay_ms":10},"next":"b"},
                  {"id":"b","type":"delay","params":{"seconds":1},"next":null}]}"""), 100);
        RetryPolicy.Settings a = policy.settingsFor(d.node("a"));
        RetryPolicy.Settings b = policy.settingsFor(d.node("b"));
        assertThat(a.maxAttempts()).isEqualTo(7);
        assertThat(a.baseDelayMs()).isEqualTo(10);
        assertThat(b.maxAttempts()).isEqualTo(props.getEngine().getDefaultMaxAttempts());
    }

    @Test
    void idempotencyKeyIsStableAndExcludesTheAttemptNumber() {
        assertThat(RunExecutor.idempotencyKey("run_1", "notify_a", 1)).isEqualTo("run_1:notify_a");
        // a loop iteration is a distinct effect, a retry of the same iteration is not
        assertThat(RunExecutor.idempotencyKey("run_1", "notify_a", 2)).isEqualTo("run_1:notify_a:2");
        assertThat(RunExecutor.idempotencyKey("run_1", "notify_a", 1)).isEqualTo(RunExecutor.idempotencyKey("run_1", "notify_a", 1));
    }
}
