package com.mohan.payments.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import com.mohan.payments.outbox.OutboxWorker;
import com.mohan.payments.support.IntegrationTestBase;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The async workflow end to end: capture over HTTP enqueues a job; the worker (driven directly here instead of by
 * the scheduler) settles it, retries transient failures, and dead-letters permanent ones.
 */
class SettlementIntegrationTest extends IntegrationTestBase {

    @Autowired
    private OutboxWorker worker;

    @Autowired
    private MeterRegistry meterRegistry;

    private String acme;

    @BeforeEach
    void loginAsAcme() throws Exception {
        acme = login(ACME_EMAIL);
    }

    @Test
    void capturedPaymentIsSettledWithTheCaptureRequestsCorrelationId() throws Exception {
        String id = authorizedPayment(acme, 1000);
        mvc.perform(post("/api/v1/payments/{id}/capture", id).header("Authorization", "Bearer " + acme)
                .header("Idempotency-Key", "cap").header("X-Request-Id", "capture-req-42"))
                .andExpect(status().isOk());

        assertThat(worker.runOnce()).isEqualTo(1);

        assertThat(paymentStatus(id)).isEqualTo("SETTLED");
        Map<String, Object> event = jdbc.queryForMap("""
                SELECT actor_type, actor_id, correlation_id FROM payment_events
                WHERE payment_id = ?::uuid AND to_status = 'SETTLED'""", id);
        assertThat(event).containsEntry("actor_type", "SYSTEM").containsEntry("actor_id", "settlement-worker")
                .containsEntry("correlation_id", "capture-req-42");
        assertThat(job(id)).containsEntry("status", "DONE").containsEntry("attempts", 1);
    }

    @Test
    void transientFailuresAreRetriedUntilSettled() throws Exception {
        String id = capturedPayment(1013); // stub: fails twice, then succeeds

        worker.runOnce();
        assertThat(job(id)).containsEntry("status", "PENDING").containsEntry("attempts", 1);
        assertThat((String) job(id).get("last_error")).contains("timeout");
        assertThat(paymentStatus(id)).isEqualTo("CAPTURED");

        worker.runOnce();
        worker.runOnce();

        assertThat(job(id)).containsEntry("status", "DONE").containsEntry("attempts", 3);
        assertThat(paymentStatus(id)).isEqualTo("SETTLED");
        assertThat(meterRegistry.get("outbox.jobs.processed").tag("result", "retry").counter().count())
                .isGreaterThanOrEqualTo(2);
    }

    @Test
    void permanentFailureIsDeadLetteredAndPaymentStaysCaptured() throws Exception {
        String id = capturedPayment(1099); // stub: always fails; max-attempts is 3 in tests

        for (int i = 0; i < 5; i++) {
            worker.runOnce();
        }

        assertThat(job(id)).containsEntry("status", "DEAD").containsEntry("attempts", 3);
        assertThat(paymentStatus(id)).isEqualTo("CAPTURED");
    }

    @Test
    void refundBeforeSettlementSkipsTheJob() throws Exception {
        String id = capturedPayment(2000);
        refund(acme, id, "refund").andExpect(status().isOk());

        worker.runOnce();

        assertThat(job(id)).containsEntry("status", "DONE");
        assertThat(paymentStatus(id)).isEqualTo("REFUNDED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_events WHERE to_status = 'SETTLED'",
                Long.class)).isZero();
    }

    @Test
    void jobsOrphanedByACrashedWorkerAreReleased() throws Exception {
        String id = capturedPayment(1000);
        jdbc.update("UPDATE outbox_jobs SET status = 'PROCESSING', locked_at = now() - interval '1 hour'");

        assertThat(worker.runOnce()).isZero();         // PROCESSING jobs are not picked up...
        assertThat(worker.releaseStuckJobs()).isEqualTo(1);
        assertThat(worker.runOnce()).isEqualTo(1);     // ...until released

        assertThat(paymentStatus(id)).isEqualTo("SETTLED");
    }

    private String capturedPayment(long amount) throws Exception {
        String id = authorizedPayment(acme, amount);
        capture(acme, id, "cap-" + id).andExpect(status().isOk());
        return id;
    }

    private Map<String, Object> job(String paymentId) {
        return jdbc.queryForMap("SELECT status, attempts, last_error FROM outbox_jobs WHERE payment_id = ?::uuid",
                paymentId);
    }
}
