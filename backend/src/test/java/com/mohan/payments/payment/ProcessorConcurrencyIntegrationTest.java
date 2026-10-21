package com.mohan.payments.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import com.mohan.payments.outbox.OutboxWorker;
import com.mohan.payments.payment.processor.PaymentProcessorClient;
import com.mohan.payments.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * The database can roll back; the processor cannot. These tests count calls to the processor, not rows, because a
 * second refund call returns real money even when its transaction later fails.
 * <p>
 * Each processor call is slowed down so that two concurrent requests genuinely overlap, as they would against a
 * real processor over the network. Without row locking both would read the same state and both call out.
 */
class ProcessorConcurrencyIntegrationTest extends IntegrationTestBase {

    private static final long PROCESSOR_LATENCY_MS = 300;

    @MockitoSpyBean
    private PaymentProcessorClient processor;

    @Autowired
    private OutboxWorker worker;

    private String acme;

    @BeforeEach
    void slowProcessorAndLogin() throws Exception {
        doAnswer(ProcessorConcurrencyIntegrationTest::slowly).when(processor).capture(anyString(), anyLong());
        doAnswer(ProcessorConcurrencyIntegrationTest::slowly).when(processor).refund(anyString(), anyLong());
        doAnswer(ProcessorConcurrencyIntegrationTest::slowly).when(processor).settle(anyString(), anyLong());
        acme = login(ACME_EMAIL);
    }

    @Test
    void concurrentCapturesCallTheProcessorOnce() throws Exception {
        String id = authorizedPayment(acme, 1000);
        AtomicInteger key = new AtomicInteger();

        List<MockHttpServletResponse> responses = runConcurrently(2,
                () -> capture(acme, id, "capture-" + key.incrementAndGet()).andReturn().getResponse());

        assertThat(responses).extracting(MockHttpServletResponse::getStatus).containsExactlyInAnyOrder(200, 409);
        verify(processor, times(1)).capture(anyString(), anyLong());
    }

    @Test
    void concurrentFullRefundsReturnTheMoneyOnce() throws Exception {
        String id = capturedPayment(1000);
        AtomicInteger key = new AtomicInteger();

        List<MockHttpServletResponse> responses = runConcurrently(2,
                () -> refund(acme, id, "refund-" + key.incrementAndGet()).andReturn().getResponse());

        assertThat(responses).extracting(MockHttpServletResponse::getStatus).containsExactlyInAnyOrder(200, 409);
        verify(processor, times(1)).refund(anyString(), anyLong());
        assertThat(refundedAmount(id)).isEqualTo(1000);
    }

    @Test
    void concurrentPartialRefundsThatTogetherExceedTheAmountRefundOnlyOnce() throws Exception {
        String id = capturedPayment(1000);
        AtomicInteger key = new AtomicInteger();

        List<MockHttpServletResponse> responses = runConcurrently(2,
                () -> partialRefund(id, "partial-" + key.incrementAndGet(), 600));

        // The second request now sees only 400 left and is refused before anything reaches the processor.
        assertThat(responses).extracting(MockHttpServletResponse::getStatus).containsExactlyInAnyOrder(200, 422);
        verify(processor, times(1)).refund(anyString(), anyLong());
        assertThat(refundedAmount(id)).isEqualTo(600);
    }

    @Test
    void concurrentPartialRefundsThatFitBothSucceed() throws Exception {
        String id = capturedPayment(1000);
        AtomicInteger key = new AtomicInteger();

        List<MockHttpServletResponse> responses = runConcurrently(2,
                () -> partialRefund(id, "fits-" + key.incrementAndGet(), 300));

        // Serialized rather than rejected: neither caller sees a spurious conflict, and the books add up.
        assertThat(responses).extracting(MockHttpServletResponse::getStatus).containsExactly(200, 200);
        verify(processor, times(2)).refund(anyString(), anyLong());
        assertThat(refundedAmount(id)).isEqualTo(600);
        assertThat(paymentStatus(id)).isEqualTo("PARTIALLY_REFUNDED");
    }

    @Test
    void refundRacingSettlementNeverLosesARecordedSettlement() throws Exception {
        String id = capturedPayment(1000);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            // A full refund and the settlement worker reach the same payment at the same moment.
            Future<MockHttpServletResponse> refund = pool.submit(() -> refund(acme, id, "refund-vs-settle")
                    .andReturn().getResponse());
            worker.runOnce();
            assertThat(refund.get().getStatus()).isEqualTo(200);
        } finally {
            pool.shutdownNow();
        }

        // Either order is legitimate: refund first (settlement skipped) or settle first, then refund. What must
        // never happen is a settlement the processor carried out that our records do not show: the worker
        // reading CAPTURED, paying the merchant, then losing the commit to the refund.
        assertThat(paymentStatus(id)).isEqualTo("REFUNDED");
        verify(processor, times(1)).refund(anyString(), anyLong());
        long settledEvents = jdbc.queryForObject(
                "SELECT count(*) FROM payment_events WHERE payment_id = ?::uuid AND to_status = 'SETTLED'",
                Long.class, id);
        verify(processor, times((int) settledEvents)).settle(anyString(), anyLong());
    }

    private String capturedPayment(long amount) throws Exception {
        String id = authorizedPayment(acme, amount);
        capture(acme, id, "setup-capture-" + id).andExpect(status().isOk());
        org.mockito.Mockito.clearInvocations(processor);
        return id;
    }

    private MockHttpServletResponse partialRefund(String id, String key, long amount) throws Exception {
        return mvc.perform(post("/api/v1/payments/{id}/refund", id)
                        .header("Authorization", "Bearer " + acme).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": " + amount + "}"))
                .andReturn().getResponse();
    }

    private long refundedAmount(String id) {
        return jdbc.queryForObject("SELECT refunded_amount_minor FROM payments WHERE id = ?::uuid", Long.class, id);
    }

    private static Object slowly(InvocationOnMock invocation) throws Throwable {
        Thread.sleep(PROCESSOR_LATENCY_MS);
        return invocation.callRealMethod();
    }

    private static List<MockHttpServletResponse> runConcurrently(int threads,
            Callable<MockHttpServletResponse> request) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return request.call();
                }));
            }
            start.countDown();
            List<MockHttpServletResponse> results = new ArrayList<>();
            for (Future<MockHttpServletResponse> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
