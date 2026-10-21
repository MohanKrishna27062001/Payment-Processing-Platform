package com.mohan.payments.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.jayway.jsonpath.JsonPath;
import com.mohan.payments.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

class IdempotencyIntegrationTest extends IntegrationTestBase {

    private String acme;

    @BeforeEach
    void loginAsAcme() throws Exception {
        acme = login(ACME_EMAIL);
    }

    @Test
    void retryWithSameKeyReplaysTheOriginalResponse() throws Exception {
        String first = createPayment(acme, "retry-key", 1000, "tok_visa", "order-1")
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist(IdempotencyService.REPLAYED_HEADER))
                .andReturn().getResponse().getContentAsString();

        String second = createPayment(acme, "retry-key", 1000, "tok_visa", "order-1")
                .andExpect(status().isCreated())
                .andExpect(header().string(IdempotencyService.REPLAYED_HEADER, "true"))
                .andReturn().getResponse().getContentAsString();

        assertThat((String) JsonPath.read(second, "$.id")).isEqualTo(JsonPath.read(first, "$.id"));
        assertThat(paymentCount()).isEqualTo(1);
    }

    @Test
    void reusingAKeyForADifferentRequestIsRejected() throws Exception {
        createPayment(acme, "reused-key", 1000, "tok_visa", "order-1").andExpect(status().isCreated());

        createPayment(acme, "reused-key", 9999, "tok_visa", "order-1")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(paymentCount()).isEqualTo(1);
    }

    @Test
    void keysAreScopedPerMerchant() throws Exception {
        createPayment(acme, "shared-key", 1000, "tok_visa", "order-1").andExpect(status().isCreated());
        createPayment(login(GLOBEX_EMAIL), "shared-key", 1000, "tok_visa", "order-1")
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist(IdempotencyService.REPLAYED_HEADER));

        assertThat(paymentCount()).isEqualTo(2);
    }

    @Test
    void retriedCaptureReplaysInsteadOfFailing() throws Exception {
        String id = authorizedPayment(acme, 1000);

        capture(acme, id, "capture-key").andExpect(status().isOk());
        capture(acme, id, "capture-key")
                .andExpect(status().isOk())
                .andExpect(header().string(IdempotencyService.REPLAYED_HEADER, "true"))
                .andExpect(jsonPath("$.status").value("CAPTURED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_jobs", Long.class)).isEqualTo(1);
    }

    @Test
    void concurrentRequestsWithTheSameKeyCreateExactlyOnePayment() throws Exception {
        List<MockHttpServletResponse> responses = runConcurrently(8,
                () -> createPayment(acme, "race-key", 1000, "tok_visa", "order-race").andReturn().getResponse());

        assertThat(responses).allSatisfy(r -> assertThat(r.getStatus()).isEqualTo(201));
        assertThat(responses.stream().map(r -> (String) JsonPath.read(content(r), "$.id")).distinct()).hasSize(1);
        assertThat(paymentCount()).isEqualTo(1);
    }

    @Test
    void concurrentCapturesWithDifferentKeysCaptureOnce() throws Exception {
        String id = authorizedPayment(acme, 1000);
        int[] counter = {0};

        List<MockHttpServletResponse> responses = runConcurrently(2, () -> {
            String key;
            synchronized (counter) {
                key = "capture-" + counter[0]++;
            }
            return capture(acme, id, key).andReturn().getResponse();
        });

        assertThat(responses).extracting(MockHttpServletResponse::getStatus).containsExactlyInAnyOrder(200, 409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_events WHERE to_status = 'CAPTURED'",
                Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_jobs", Long.class)).isEqualTo(1);
    }

    private long paymentCount() {
        return jdbc.queryForObject("SELECT count(*) FROM payments", Long.class);
    }

    private static String content(MockHttpServletResponse response) {
        try {
            return response.getContentAsString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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
