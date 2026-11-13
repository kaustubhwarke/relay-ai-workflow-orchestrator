package com.relay.worker;

import com.relay.repository.ScheduleRepository;
import com.relay.service.RunService;
import com.relay.service.WorkflowService;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Fires cron-style schedule triggers (1-minute granularity, no catch-up for missed windows). Due schedules are
 * locked with {@code FOR UPDATE SKIP LOCKED} and the run is created in the same transaction that advances the
 * schedule, so each firing happens exactly once even with several dispatcher instances.
 */
@Component
public class ScheduleDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ScheduleDispatcher.class);

    private final ScheduleRepository schedules;
    private final RunService runService;
    /** A schedule whose trigger failed is retried after this long instead of waiting for its next cron window. */
    static final long RETRY_AFTER_FAILURE_SECONDS = 60;

    private final TransactionTemplate tx;
    private final TransactionTemplate attempt;

    public ScheduleDispatcher(ScheduleRepository schedules, RunService runService, PlatformTransactionManager txManager) {
        this.schedules = schedules;
        this.runService = runService;
        this.tx = new TransactionTemplate(txManager);
        // each firing runs in its own savepoint, so one failing schedule rolls back only itself
        this.attempt = new TransactionTemplate(txManager);
        this.attempt.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    /** @return number of runs created */
    public int fireDue() {
        Integer fired = tx.execute(status -> {
            int count = 0;
            for (ScheduleRepository.DueSchedule s : schedules.lockDue()) {
                try {
                    attempt.executeWithoutResult(st -> runService.triggerSchedule(s.workflowId()));
                    count++;
                    // No catch-up: the next fire is computed from now, skipping any missed windows.
                    schedules.advance(s.workflowId(), WorkflowService.nextFire(s.cron(), Instant.now()));
                } catch (RuntimeException e) {
                    // The firing did not happen, so it is not consumed: retry shortly rather than skipping the window.
                    log.warn("Schedule for workflow {} could not fire (retrying in {}s): {}", s.workflowId(),
                            RETRY_AFTER_FAILURE_SECONDS, e.getMessage());
                    schedules.advance(s.workflowId(), Instant.now().plusSeconds(RETRY_AFTER_FAILURE_SECONDS));
                }
            }
            return count;
        });
        return fired == null ? 0 : fired;
    }
}
