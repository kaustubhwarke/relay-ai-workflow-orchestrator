package com.relay.observability;

import com.relay.domain.RunStatus;
import com.relay.repository.ApprovalRepository;
import com.relay.repository.QueueRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** Prometheus metrics: run outcomes, step outcomes and latency, AI token usage, queue depth, approvals. */
@Component
public class RelayMetrics {

    private final MeterRegistry registry;

    public RelayMetrics(MeterRegistry registry, QueueRepository queue, ApprovalRepository approvals) {
        this.registry = registry;
        Gauge.builder("relay.queue.depth", queue, q -> safe(q::depth)).description("Jobs in the durable queue").register(registry);
        Gauge.builder("relay.queue.ready", queue, q -> safe(q::readyDepth)).description("Jobs claimable right now").register(registry);
        Gauge.builder("relay.approvals.pending", approvals, a -> safe(a::countPending)).description("Approvals waiting for a human").register(registry);
    }

    private static double safe(java.util.function.Supplier<Long> s) {
        try {
            return s.get();
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    public void runFinished(RunStatus status) {
        Counter.builder("relay.runs.finished").tag("status", status.value()).register(registry).increment();
    }

    public void stepFinished(String nodeType, String status, long millis) {
        Counter.builder("relay.steps").tag("type", nodeType).tag("status", status).register(registry).increment();
        Timer.builder("relay.step.duration").tag("type", nodeType).register(registry).record(Duration.ofMillis(millis));
    }

    public void aiTokens(int prompt, int completion) {
        Counter.builder("relay.ai.tokens").tag("kind", "prompt").register(registry).increment(prompt);
        Counter.builder("relay.ai.tokens").tag("kind", "completion").register(registry).increment(completion);
    }

    public void stepRetried(String nodeType) {
        Counter.builder("relay.steps.retries").tag("type", nodeType).register(registry).increment();
    }

    public void runResumed() {
        Counter.builder("relay.runs.resumed").description("Runs resumed after a crash or lease expiry").register(registry).increment();
    }
}
