package com.mohan.payments.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import com.mohan.payments.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class PaymentApiIntegrationTest extends IntegrationTestBase {

    private String acme;

    @BeforeEach
    void loginAsAcme() throws Exception {
        acme = login(ACME_EMAIL);
    }

    @Test
    void createAuthorizesPaymentAndRecordsAuditTrail() throws Exception {
        String body = createPayment(acme, "key-1", 1050, "tok_visa", "order-1")
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("AUTHORIZED"))
                .andExpect(jsonPath("$.amount").value(1050))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.card.brand").value("VISA"))
                .andExpect(jsonPath("$.card.last4").value("4242"))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");

        getPayment(acme, id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payment.status").value("AUTHORIZED"))
                .andExpect(jsonPath("$.events.length()").value(2))
                .andExpect(jsonPath("$.events[0].fromStatus").doesNotExist())
                .andExpect(jsonPath("$.events[0].toStatus").value("PENDING"))
                .andExpect(jsonPath("$.events[0].actorType").value("MERCHANT"))
                .andExpect(jsonPath("$.events[0].actorId").value(ACME_ID.toString()))
                .andExpect(jsonPath("$.events[1].fromStatus").value("PENDING"))
                .andExpect(jsonPath("$.events[1].toStatus").value("AUTHORIZED"))
                .andExpect(jsonPath("$.events[1].actorId").value("payment-processor"));
    }

    @Test
    void declinedCardIsStoredAsFailed() throws Exception {
        createPayment(acme, "key-decline", 2000, "tok_decline", "order-2")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value("insufficient_funds"))
                .andExpect(jsonPath("$.card").doesNotExist());
    }

    @Test
    void processorOutageRollsBackAndReturns503() throws Exception {
        createPayment(acme, "key-outage", 2000, "tok_processor_error", "order-3")
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.code").value("PROCESSOR_UNAVAILABLE"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_keys", Long.class)).isZero();
    }

    @Test
    void rawCardNumbersAreRejectedAndNeverEchoed() throws Exception {
        createPayment(acme, "key-pan", 1000, "4242 4242 4242 4242", "order-4")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RAW_CARD_DATA_REJECTED"))
                .andExpect(content().string(not(containsString("4242 4242"))));
    }

    @Test
    void invalidRequestReturnsFieldErrors() throws Exception {
        mvc.perform(post("/api/v1/payments").header("Authorization", "Bearer " + acme)
                        .header("Idempotency-Key", "key-invalid").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": -5, \"currency\": \"usd\", \"paymentMethod\": {\"token\": \"\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field").value(hasItem("amount")))
                .andExpect(jsonPath("$.errors[*].field").value(hasItem("currency")))
                .andExpect(jsonPath("$.errors[*].field").value(hasItem("paymentMethod.token")));
    }

    @Test
    void idempotencyKeyIsRequiredForMoneyMovingCalls() throws Exception {
        mvc.perform(post("/api/v1/payments").header("Authorization", "Bearer " + acme)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": 100, \"currency\": \"USD\", \"paymentMethod\": {\"token\": \"tok_visa\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
    }

    @Test
    void captureThenRefundFollowsTheLifecycle() throws Exception {
        String id = authorizedPayment(acme, 5000);

        capture(acme, id, "cap-1").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CAPTURED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_jobs WHERE payment_id = ?::uuid", Long.class, id))
                .isEqualTo(1);

        refund(acme, id, "ref-1").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REFUNDED"));

        getPayment(acme, id)
                .andExpect(jsonPath("$.events[*].toStatus").value(
                        org.hamcrest.Matchers.contains("PENDING", "AUTHORIZED", "CAPTURED", "REFUNDED")));
    }

    @Test
    void invalidTransitionsAreRejected() throws Exception {
        String authorized = authorizedPayment(acme, 3000);
        refund(acme, authorized, "ref-early")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));

        String failed = JsonPath.read(createPayment(acme, "key-f", 3000, "tok_decline", "x")
                .andReturn().getResponse().getContentAsString(), "$.id");
        capture(acme, failed, "cap-failed")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
        assertThat(paymentStatus(failed)).isEqualTo("FAILED");
    }

    @Test
    void merchantsCannotSeeOrTouchEachOthersPayments() throws Exception {
        String acmePayment = authorizedPayment(acme, 4000);
        String globex = login(GLOBEX_EMAIL);

        getPayment(globex, acmePayment).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        capture(globex, acmePayment, "cap-x").andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/payments").header("Authorization", "Bearer " + globex))
                .andExpect(jsonPath("$.totalItems").value(0));
        assertThat(paymentStatus(acmePayment)).isEqualTo("AUTHORIZED");
    }

    @Test
    void listFiltersSearchesAndPaginates() throws Exception {
        createPayment(acme, UUID.randomUUID().toString(), 1000, "tok_visa", "ORDER-100").andExpect(status().isCreated());
        createPayment(acme, UUID.randomUUID().toString(), 2000, "tok_visa", "order-200").andExpect(status().isCreated());
        createPayment(acme, UUID.randomUUID().toString(), 3000, "tok_decline", "order-300").andExpect(status().isCreated());
        createPayment(login(GLOBEX_EMAIL), UUID.randomUUID().toString(), 1000, "tok_visa", "order-100")
                .andExpect(status().isCreated());

        mvc.perform(get("/api/v1/payments").header("Authorization", "Bearer " + acme))
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.items[0].merchantReference").value("order-300")); // newest first
        mvc.perform(get("/api/v1/payments?status=FAILED").header("Authorization", "Bearer " + acme))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].status").value("FAILED"));
        mvc.perform(get("/api/v1/payments?q=order-1").header("Authorization", "Bearer " + acme))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].merchantReference").value("ORDER-100"));
        mvc.perform(get("/api/v1/payments?minAmount=1500&maxAmount=2500").header("Authorization", "Bearer " + acme))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].amount").value(2000));
        mvc.perform(get("/api/v1/payments?size=2&page=1").header("Authorization", "Bearer " + acme))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.totalPages").value(2));
        mvc.perform(get("/api/v1/payments?size=500").header("Authorization", "Bearer " + acme))
                .andExpect(status().isBadRequest());
    }
}
