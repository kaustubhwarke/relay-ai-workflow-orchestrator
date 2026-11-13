package com.relay.worker;

import com.relay.repository.ScheduleRepository;
import com.relay.service.RunService;
import com.relay.service.WorkflowService;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
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
    private final TransactionTemplate tx;

    public ScheduleDispatcher(ScheduleRepository schedules, RunService runService, PlatformTransactionManager txManager) {
        this.schedules = schedules;
        this.runService = runService;
        this.tx = new TransactionTemplate(txManager);
    }

    /** @return number of runs created */
    public int fireDue() {
        Integer fired = tx.execute(status -> {
            int count = 0;
            for (ScheduleRepository.DueSchedule s : schedules.lockDue()) {
                try {
                    runService.triggerSchedule(s.workflowId());
                    count++;
                } catch (RuntimeException e) {
                    log.warn("Schedule for workflow {} could not fire: {}", s.workflowId(), e.getMessage());
                }
                // No catch-up: the next fire is computed from now, skipping any missed windows.
                schedules.advance(s.workflowId(), WorkflowService.nextFire(s.cron(), Instant.now()));
            }
            return count;
        });
        return fired == null ? 0 : fired;
    }
}
