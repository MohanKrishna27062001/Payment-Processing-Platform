package com.mohan.payments.outbox;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Operator view of the async workflow, at {@code /actuator/outbox}.
 * <p>
 * A dead-lettered settlement job is money a merchant was told had been captured but which will never reach them.
 * The health indicator and the {@code outbox.queue.depth{status="DEAD"}} gauge say <i>that</i> it happened; this
 * endpoint says <i>which</i> jobs and lets an operator requeue one once the cause is fixed, instead of hand-written
 * UPDATE statements against production.
 * <ul>
 *   <li>{@code GET  /actuator/outbox} - queue depth, lag, and the most recent dead letters with their last error.</li>
 *   <li>{@code POST /actuator/outbox/{jobId}} - requeue that job: back to PENDING, due now, attempts reset.</li>
 * </ul>
 * It shows every merchant's jobs, so it requires the operator account and rejects merchant tokens (see
 * {@code SecurityConfig#actuatorSecurity}); in production it also sits on a management port that is not exposed
 * to the internet. Replaying is safe
 * because every handler is idempotent: a job whose payment already settled completes as SKIPPED.
 */
@Component
@Endpoint(id = "outbox")
public class OutboxEndpoint {

    private static final Logger log = LoggerFactory.getLogger(OutboxEndpoint.class);

    private final OutboxJobRepository jobs;
    private final OutboxMetrics metrics;
    private final Clock clock;

    public OutboxEndpoint(OutboxJobRepository jobs, OutboxMetrics metrics, Clock clock) {
        this.jobs = jobs;
        this.metrics = metrics;
        this.clock = clock;
    }

    @ReadOperation
    public Map<String, Object> outbox() {
        OutboxMetrics.Snapshot snapshot = metrics.snapshot();
        List<Map<String, Object>> deadLetters = jobs.findTop100ByStatusOrderByIdDesc(OutboxJob.Status.DEAD).stream()
                .map(OutboxEndpoint::describe)
                .toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pending", snapshot.pending());
        body.put("processing", snapshot.processing());
        body.put("dead", snapshot.dead());
        body.put("lagSeconds", snapshot.lagSeconds());
        body.put("deadLetters", deadLetters);
        return body;
    }

    /** @param jobId the dead-lettered job to requeue */
    @WriteOperation
    @Transactional
    public Map<String, Object> replay(@Selector String jobId) {
        long id;
        try {
            id = Long.parseLong(jobId);
        } catch (NumberFormatException e) {
            return Map.of("replayed", false, "reason", "jobId must be a number");
        }
        OutboxJob job = jobs.findById(id).orElse(null);
        if (job == null) {
            return Map.of("replayed", false, "reason", "No job " + id);
        }
        if (job.getStatus() != OutboxJob.Status.DEAD) {
            // Requeuing a live job would let two workers run it at once.
            return Map.of("replayed", false, "reason", "Job " + id + " is " + job.getStatus() + ", not DEAD");
        }
        job.replay(clock.instant());
        log.atWarn().addKeyValue("jobId", id).addKeyValue("jobType", job.getType())
                .addKeyValue("paymentId", job.getPaymentId())
                .log("Dead-lettered outbox job requeued by an operator");
        return Map.of("replayed", true, "jobId", id, "status", job.getStatus().name());
    }

    private static Map<String, Object> describe(OutboxJob job) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", job.getId());
        row.put("type", job.getType().name());
        row.put("paymentId", job.getPaymentId().toString());
        row.put("attempts", job.getAttempts());
        row.put("correlationId", job.getCorrelationId());
        row.put("createdAt", job.getCreatedAt());
        row.put("lastError", job.getLastError());
        return row;
    }
}
