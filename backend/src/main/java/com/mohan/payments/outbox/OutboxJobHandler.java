package com.mohan.payments.outbox;

/**
 * Performs one type of job. Called inside a transaction; throwing schedules a retry (or dead-letters the job),
 * so handlers must be idempotent: the same job can run more than once if a worker dies after doing the work
 * but before marking the job done.
 */
public interface OutboxJobHandler {

    JobType type();

    Outcome handle(OutboxJob job);

    enum Outcome {
        /** The work was done. */
        COMPLETED,
        /** Nothing to do (e.g. already settled, or refunded before settlement). */
        SKIPPED
    }
}
