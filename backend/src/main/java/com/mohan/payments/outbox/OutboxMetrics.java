package com.mohan.payments.outbox;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Async workflow metrics:
 * <ul>
 *   <li>{@code outbox.queue.depth{status}}: jobs PENDING / PROCESSING / DEAD (gauges, refreshed periodically so a
 *       Prometheus scrape never hits the database).</li>
 *   <li>{@code outbox.lag.seconds}: how long the oldest <i>due</i> job has been waiting. Growing lag means workers
 *       are not keeping up.</li>
 *   <li>{@code outbox.jobs.processed{type,result}}: result = success | skipped | retry | dead.</li>
 *   <li>{@code outbox.job.duration{type,result}}: time spent processing one attempt.</li>
 *   <li>{@code outbox.job.end_to_end{type}}: from enqueue to completion, including the settlement delay and retries.</li>
 * </ul>
 */
@Component
public class OutboxMetrics {

    private final MeterRegistry registry;
    private final OutboxJobRepository jobs;
    private final Clock clock;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong processing = new AtomicLong();
    private final AtomicLong dead = new AtomicLong();
    private final AtomicLong lagSeconds = new AtomicLong();

    public OutboxMetrics(MeterRegistry registry, OutboxJobRepository jobs, Clock clock) {
        this.registry = registry;
        this.jobs = jobs;
        this.clock = clock;
        Gauge.builder("outbox.queue.depth", pending, AtomicLong::get).tag("status", "PENDING")
                .description("Outbox jobs by status").register(registry);
        Gauge.builder("outbox.queue.depth", processing, AtomicLong::get).tag("status", "PROCESSING")
                .description("Outbox jobs by status").register(registry);
        Gauge.builder("outbox.queue.depth", dead, AtomicLong::get).tag("status", "DEAD")
                .description("Outbox jobs by status").register(registry);
        Gauge.builder("outbox.lag.seconds", lagSeconds, AtomicLong::get)
                .description("Age of the oldest due job that has not been processed yet").register(registry);
    }

    public void recordProcessed(JobType type, String result, Duration processingTime, Duration endToEnd) {
        Counter.builder("outbox.jobs.processed").tag("type", type.name()).tag("result", result)
                .description("Outbox job attempts by result").register(registry).increment();
        Timer.builder("outbox.job.duration").tag("type", type.name()).tag("result", result)
                .description("Time to process one job attempt").register(registry).record(processingTime);
        if (endToEnd != null) {
            Timer.builder("outbox.job.end_to_end").tag("type", type.name())
                    .description("Time from enqueue to successful completion").register(registry).record(endToEnd);
        }
    }

    @Scheduled(fixedDelayString = "${app.metrics.refresh-interval:PT30S}", initialDelayString = "PT5S")
    public void refresh() {
        Instant now = clock.instant();
        pending.set(jobs.countByStatus(OutboxJob.Status.PENDING));
        processing.set(jobs.countByStatus(OutboxJob.Status.PROCESSING));
        dead.set(jobs.countByStatus(OutboxJob.Status.DEAD));
        Instant oldestDue = jobs.oldestDueAt(OutboxJob.Status.PENDING, now);
        lagSeconds.set(oldestDue == null ? 0 : Duration.between(oldestDue, now).toSeconds());
    }

    public Snapshot snapshot() {
        return new Snapshot(pending.get(), processing.get(), dead.get(), lagSeconds.get());
    }

    public record Snapshot(long pending, long processing, long dead, long lagSeconds) {
    }
}
