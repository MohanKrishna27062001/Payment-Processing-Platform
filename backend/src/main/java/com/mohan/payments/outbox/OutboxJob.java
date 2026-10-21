package com.mohan.payments.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A unit of async work, written in the same transaction as the business change that requires it.
 * Lifecycle: PENDING → PROCESSING → DONE, or back to PENDING with a later {@code nextAttemptAt} on failure,
 * or DEAD after the maximum number of attempts (the dead-letter state, kept for investigation and replay).
 */
@Entity
@Table(name = "outbox_jobs")
public class OutboxJob {

    public enum Status {
        PENDING,
        PROCESSING,
        DONE,
        DEAD
    }

    private static final int MAX_ERROR_LENGTH = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_type", nullable = false, updatable = false)
    private JobType type;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "locked_at")
    private Instant lockedAt;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "correlation_id", updatable = false)
    private String correlationId;

    @Column(updatable = false)
    private String traceparent;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected OutboxJob() {
    }

    static OutboxJob create(JobType type, UUID paymentId, Instant runAt, String correlationId, String traceparent,
            Instant now) {
        OutboxJob job = new OutboxJob();
        job.type = type;
        job.paymentId = paymentId;
        job.status = Status.PENDING;
        job.nextAttemptAt = runAt;
        job.correlationId = correlationId;
        job.traceparent = traceparent;
        job.createdAt = now;
        job.updatedAt = now;
        return job;
    }

    void markDone(Instant now) {
        attempts++;
        status = Status.DONE;
        lockedAt = null;
        lastError = null;
        updatedAt = now;
    }

    /**
     * Puts a dead-lettered job back in the queue after an operator has dealt with whatever caused it to fail.
     * The attempt count resets, so the job gets a full set of retries rather than dying on the first error.
     */
    void replay(Instant now) {
        status = Status.PENDING;
        attempts = 0;
        nextAttemptAt = now;
        lockedAt = null;
        lastError = null;
        updatedAt = now;
    }

    /** Schedules a retry, or dead-letters the job once {@code maxAttempts} is reached. */
    void recordFailure(String error, int maxAttempts, Duration retryDelay, Instant now) {
        attempts++;
        lockedAt = null;
        updatedAt = now;
        lastError = error == null ? null : error.substring(0, Math.min(error.length(), MAX_ERROR_LENGTH));
        if (attempts >= maxAttempts) {
            status = Status.DEAD;
        } else {
            status = Status.PENDING;
            nextAttemptAt = now.plus(retryDelay);
        }
    }

    public Long getId() {
        return id;
    }

    public JobType getType() {
        return type;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public Status getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public String getTraceparent() {
        return traceparent;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
