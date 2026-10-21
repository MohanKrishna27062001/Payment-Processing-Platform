package com.mohan.payments.outbox;

import java.time.Clock;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deletes finished outbox jobs once they are older than {@code app.outbox.retention}.
 * <p>
 * The partial index on PENDING rows means polling stays fast however many DONE rows accumulate, so this is about
 * table size and backup time rather than query speed. DEAD jobs are never deleted: they are the record of work
 * that did not happen, and they are removed only by being replayed or by a deliberate decision.
 */
@Component
public class OutboxRetention {

    private static final Logger log = LoggerFactory.getLogger(OutboxRetention.class);

    private final OutboxJobRepository jobs;
    private final Clock clock;
    private final Duration retention;

    public OutboxRetention(OutboxJobRepository jobs, Clock clock,
            @Value("${app.outbox.retention:P7D}") Duration retention) {
        this.jobs = jobs;
        this.clock = clock;
        this.retention = retention;
    }

    @Scheduled(fixedDelayString = "${app.outbox.cleanup-interval:PT1H}", initialDelayString = "PT5M")
    @Transactional
    public void purgeCompleted() {
        int deleted = jobs.deleteCompletedBefore(OutboxJob.Status.DONE, clock.instant().minus(retention));
        if (deleted > 0) {
            log.info("Purged {} completed outbox jobs older than {}", deleted, retention);
        }
    }
}
