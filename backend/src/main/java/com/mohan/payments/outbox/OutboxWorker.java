package com.mohan.payments.outbox;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.mohan.payments.common.observability.TraceContextSupport;
import com.mohan.payments.common.web.CorrelationIdFilter;
import com.mohan.payments.outbox.OutboxJobHandler.Outcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Processes due outbox jobs. Each poll:
 * <ol>
 *   <li>claims a batch in a short transaction ({@code FOR UPDATE SKIP LOCKED}, then marks them PROCESSING);</li>
 *   <li>runs each job in its own transaction, inside a span that continues the originating request's trace and
 *       with the request's correlation id in the logging MDC;</li>
 *   <li>on failure records the error and schedules a retry with backoff, or dead-letters the job.</li>
 * </ol>
 * Scheduling lives in {@link OutboxPoller}; this class is plain so tests can drive it directly.
 */
@Component
public class OutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(OutboxWorker.class);

    private final OutboxJobRepository jobs;
    private final Map<JobType, OutboxJobHandler> handlers;
    private final OutboxProperties properties;
    private final BackoffPolicy backoff;
    private final OutboxMetrics metrics;
    private final TraceContextSupport tracing;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public OutboxWorker(OutboxJobRepository jobs, List<OutboxJobHandler> handlers, OutboxProperties properties,
            BackoffPolicy backoff, OutboxMetrics metrics, TraceContextSupport tracing,
            PlatformTransactionManager transactionManager, Clock clock) {
        this.jobs = jobs;
        this.handlers = handlers.stream().collect(Collectors.toMap(OutboxJobHandler::type, Function.identity()));
        this.properties = properties;
        this.backoff = backoff;
        this.metrics = metrics;
        this.tracing = tracing;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** Claims and processes one batch. Returns the number of jobs processed. */
    public int runOnce() {
        List<Long> claimed = transaction.execute(status -> {
            List<Long> ids = jobs.lockDueJobIds(clock.instant(), properties.batchSize());
            if (!ids.isEmpty()) {
                jobs.markLocked(ids, OutboxJob.Status.PROCESSING, clock.instant());
            }
            return ids;
        });
        claimed.forEach(this::process);
        return claimed.size();
    }

    /** Re-queues jobs left in PROCESSING by a worker that crashed. */
    public int releaseStuckJobs() {
        Instant now = clock.instant();
        Integer released = transaction.execute(status -> jobs.release(OutboxJob.Status.PROCESSING,
                OutboxJob.Status.PENDING, now.minus(properties.stuckAfter()), now));
        if (released != null && released > 0) {
            log.warn("Released {} stuck outbox jobs back to PENDING", released);
        }
        return released == null ? 0 : released;
    }

    private void process(Long jobId) {
        OutboxJob job = jobs.findById(jobId).orElseThrow();
        try (MDC.MDCCloseable c1 = MDC.putCloseable(CorrelationIdFilter.MDC_KEY, job.getCorrelationId());
                MDC.MDCCloseable c2 = MDC.putCloseable(CorrelationIdFilter.PAYMENT_ID_MDC_KEY,
                        job.getPaymentId().toString());
                MDC.MDCCloseable c3 = MDC.putCloseable("jobId", jobId.toString())) {
            tracing.inSpan(job.getTraceparent(), "outbox " + job.getType(), () -> {
                execute(job);
                return null;
            });
        }
    }

    private void execute(OutboxJob claimed) {
        OutboxJobHandler handler = handlers.get(claimed.getType());
        long start = System.nanoTime();
        try {
            Outcome outcome = transaction.execute(status -> {
                OutboxJob job = jobs.findById(claimed.getId()).orElseThrow();
                Outcome result = handler.handle(job);
                job.markDone(clock.instant());
                return result;
            });
            Duration sinceCreated = Duration.between(claimed.getCreatedAt(), clock.instant());
            metrics.recordProcessed(claimed.getType(), outcome == Outcome.SKIPPED ? "skipped" : "success",
                    Duration.ofNanos(System.nanoTime() - start), sinceCreated);
            log.atInfo().addKeyValue("jobType", claimed.getType()).addKeyValue("outcome", outcome)
                    .addKeyValue("attempt", claimed.getAttempts() + 1)
                    .log("Outbox job {} {}", claimed.getId(), outcome);
        } catch (RuntimeException e) {
            recordFailure(claimed, e, Duration.ofNanos(System.nanoTime() - start));
        }
    }

    private void recordFailure(OutboxJob claimed, RuntimeException error, Duration elapsed) {
        boolean retryable = !(error instanceof NonRetryableJobException);
        OutboxJob job = transaction.execute(status -> {
            OutboxJob current = jobs.findById(claimed.getId()).orElseThrow();
            int maxAttempts = retryable ? properties.maxAttempts() : current.getAttempts() + 1;
            current.recordFailure(error.getMessage(), maxAttempts, backoff.delayAfter(current.getAttempts() + 1),
                    clock.instant());
            return current;
        });
        if (job.getStatus() == OutboxJob.Status.DEAD) {
            metrics.recordProcessed(job.getType(), "dead", elapsed, null);
            log.atError().addKeyValue("jobType", job.getType()).addKeyValue("attempts", job.getAttempts())
                    .addKeyValue("error", job.getLastError())
                    .log("Outbox job {} dead-lettered after {} attempts; needs manual attention",
                            job.getId(), job.getAttempts());
        } else {
            metrics.recordProcessed(job.getType(), "retry", elapsed, null);
            log.atWarn().addKeyValue("jobType", job.getType()).addKeyValue("attempts", job.getAttempts())
                    .addKeyValue("nextAttemptAt", job.getNextAttemptAt()).addKeyValue("error", job.getLastError())
                    .log("Outbox job {} failed (attempt {}), retry scheduled", job.getId(), job.getAttempts());
        }
    }
}
