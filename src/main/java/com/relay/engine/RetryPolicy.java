package com.relay.engine;

import com.relay.config.RelayProperties;
import com.relay.definition.WorkflowDefinition;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

/** Exponential backoff with jitter: {@code delay = min(max, base * 2^(attempt-1)) +/- 20%}. */
@Component
public class RetryPolicy {

    public record Settings(int maxAttempts, long baseDelayMs, long maxDelayMs) {}

    private final RelayProperties props;

    public RetryPolicy(RelayProperties props) {
        this.props = props;
    }

    public Settings settingsFor(WorkflowDefinition.Node node) {
        WorkflowDefinition.RetrySpec r = node.retry();
        RelayProperties.Engine e = props.getEngine();
        return new Settings(
                r != null && r.maxAttempts() != null ? r.maxAttempts() : e.getDefaultMaxAttempts(),
                r != null && r.baseDelayMs() != null ? r.baseDelayMs() : e.getBackoffBaseMs(),
                r != null && r.maxDelayMs() != null ? r.maxDelayMs() : e.getBackoffMaxMs());
    }

    /** Delay to wait after {@code failedAttempt} (1-based) before the next attempt. */
    public long backoffMillis(Settings s, int failedAttempt) {
        long exp = s.baseDelayMs() * (1L << Math.min(failedAttempt - 1, 20));
        long capped = Math.min(s.maxDelayMs(), exp);
        double jitter = 0.8 + ThreadLocalRandom.current().nextDouble() * 0.4;
        return Math.max(1, (long) (capped * jitter));
    }

    /** Deterministic (jitter-free) delay, used by tests and documentation. */
    public static long nominalBackoffMillis(Settings s, int failedAttempt) {
        long exp = s.baseDelayMs() * (1L << Math.min(failedAttempt - 1, 20));
        return Math.min(s.maxDelayMs(), exp);
    }
}
