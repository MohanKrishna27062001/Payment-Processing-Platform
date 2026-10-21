package com.mohan.payments.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class OutboxJobTest {

    private static final Instant NOW = Instant.parse("2026-10-18T10:00:00Z");

    @Test
    void failureBelowMaxAttemptsSchedulesRetry() {
        OutboxJob job = OutboxJob.create(JobType.SETTLE_PAYMENT, UUID.randomUUID(), NOW, "corr", null, NOW);

        job.recordFailure("timeout", 3, Duration.ofSeconds(4), NOW);

        assertThat(job.getStatus()).isEqualTo(OutboxJob.Status.PENDING);
        assertThat(job.getAttempts()).isEqualTo(1);
        assertThat(job.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(4));
        assertThat(job.getLastError()).isEqualTo("timeout");
    }

    @Test
    void failureAtMaxAttemptsDeadLetters() {
        OutboxJob job = OutboxJob.create(JobType.SETTLE_PAYMENT, UUID.randomUUID(), NOW, "corr", null, NOW);

        job.recordFailure("timeout", 2, Duration.ZERO, NOW);
        job.recordFailure("timeout", 2, Duration.ZERO, NOW);

        assertThat(job.getStatus()).isEqualTo(OutboxJob.Status.DEAD);
        assertThat(job.getAttempts()).isEqualTo(2);
    }

    @Test
    void longErrorsAreTruncated() {
        OutboxJob job = OutboxJob.create(JobType.SETTLE_PAYMENT, UUID.randomUUID(), NOW, "corr", null, NOW);

        job.recordFailure("x".repeat(5000), 5, Duration.ZERO, NOW);

        assertThat(job.getLastError()).hasSize(1000);
    }
}
