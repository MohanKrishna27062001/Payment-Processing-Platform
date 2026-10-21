package com.mohan.payments.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives the {@link OutboxWorker} on a schedule. Disable with {@code app.outbox.worker.enabled=false}, e.g. to run
 * API-only instances and scale workers as a separate deployment.
 */
@Component
@ConditionalOnProperty(name = "app.outbox.worker.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    private final OutboxWorker worker;

    public OutboxPoller(OutboxWorker worker) {
        this.worker = worker;
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval:PT2S}", initialDelayString = "PT5S")
    public void poll() {
        try {
            int processed;
            do {
                processed = worker.runOnce();
            } while (processed > 0);
        } catch (RuntimeException e) {
            // Keep the scheduler alive (e.g. database briefly unavailable); the next poll tries again.
            log.error("Outbox poll failed", e);
        }
    }

    @Scheduled(fixedDelayString = "${app.outbox.stuck-check-interval:PT1M}", initialDelayString = "PT30S")
    public void releaseStuck() {
        try {
            worker.releaseStuckJobs();
        } catch (RuntimeException e) {
            log.error("Stuck job recovery failed", e);
        }
    }
}
