package com.mohan.payments.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.mohan.payments.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Partial refunds: the amount is the thing being checked, and the rule that matters is that a merchant can
 * never hand back more than was taken -- in one go, or by instalments that add up.
 */
class PartialRefundIntegrationTest extends IntegrationTestBase {

    private String acme;

    @BeforeEach
    void loginAsAcme() throws Exception {
        acme = login(ACME_EMAIL);
    }

    @Test
    void aPartialRefundLeavesThePaymentRefundableForTheRest() throws Exception {
        String id = capturedPayment(10_000);

        refundAmount(id, 2_500).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PARTIALLY_REFUNDED"))
                .andExpect(jsonPath("$.refundedAmount").value(2_500))
                .andExpect(jsonPath("$.refundableAmount").value(7_500));
    }

    @Test
    void instalmentsAccumulateAndTheLastOneCompletesTheRefund() throws Exception {
        String id = capturedPayment(10_000);

        refundAmount(id, 4_000).andExpect(jsonPath("$.status").value("PARTIALLY_REFUNDED"));
        refundAmount(id, 4_000).andExpect(jsonPath("$.refundedAmount").value(8_000))
                .andExpect(jsonPath("$.status").value("PARTIALLY_REFUNDED"));

        // The instalment that exhausts the balance is what makes it fully REFUNDED.
        refundAmount(id, 2_000).andExpect(jsonPath("$.status").value("REFUNDED"))
                .andExpect(jsonPath("$.refundedAmount").value(10_000))
                .andExpect(jsonPath("$.refundableAmount").value(0));

        assertThat(paymentStatus(id)).isEqualTo("REFUNDED");
    }

    @Test
    void refundingMoreThanTheOriginalAmountIsRejected() throws Exception {
        String id = capturedPayment(10_000);

        refundAmount(id, 10_001).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REFUND_AMOUNT_INVALID"));

        // Rejected before the processor was asked, so nothing moved.
        assertThat(paymentStatus(id)).isEqualTo("CAPTURED");
        assertThat(refundedAmount(id)).isZero();
    }

    @Test
    void instalmentsCannotAddUpToMoreThanTheOriginalAmount() throws Exception {
        String id = capturedPayment(10_000);
        refundAmount(id, 6_000).andExpect(status().isOk());

        refundAmount(id, 5_000).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REFUND_AMOUNT_INVALID"));

        assertThat(refundedAmount(id)).isEqualTo(6_000);
        assertThat(paymentStatus(id)).isEqualTo("PARTIALLY_REFUNDED");
    }

    @Test
    void zeroAndNegativeAmountsAreRejected() throws Exception {
        String id = capturedPayment(10_000);

        refundAmount(id, 0).andExpect(status().is4xxClientError());
        refundAmount(id, -500).andExpect(status().is4xxClientError());

        assertThat(refundedAmount(id)).isZero();
    }

    @Test
    void anOmittedAmountRefundsWhateverIsLeft() throws Exception {
        String id = capturedPayment(10_000);
        refundAmount(id, 3_000).andExpect(status().isOk());

        // No body at all: what a client written before partial refunds existed would send.
        refund(acme, id, UUID.randomUUID().toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"))
                .andExpect(jsonPath("$.refundedAmount").value(10_000));
    }

    @Test
    void retryingOneInstalmentReplaysInsteadOfRefundingTwice() throws Exception {
        String id = capturedPayment(10_000);
        String key = UUID.randomUUID().toString();

        refundAmount(id, 2_500, key).andExpect(status().isOk());
        refundAmount(id, 2_500, key).andExpect(status().isOk())
                .andExpect(jsonPath("$.refundedAmount").value(2_500));

        assertThat(refundedAmount(id)).isEqualTo(2_500);
    }

    @Test
    void aFullyRefundedPaymentCannotBeRefundedAgain() throws Exception {
        String id = capturedPayment(10_000);
        refundAmount(id, 10_000).andExpect(jsonPath("$.status").value("REFUNDED"));

        refundAmount(id, 100).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
    }

    private String capturedPayment(long amount) throws Exception {
        String id = authorizedPayment(acme, amount);
        capture(acme, id, UUID.randomUUID().toString()).andExpect(status().isOk());
        return id;
    }

    private ResultActions refundAmount(String paymentId, long amount) throws Exception {
        return refundAmount(paymentId, amount, UUID.randomUUID().toString());
    }

    private ResultActions refundAmount(String paymentId, long amount, String key) throws Exception {
        return mvc.perform(post("/api/v1/payments/{id}/refund", paymentId)
                .header("Authorization", "Bearer " + acme)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": %d}".formatted(amount)));
    }

    private long refundedAmount(String paymentId) {
        Long value = jdbc.queryForObject(
                "SELECT refunded_amount_minor FROM payments WHERE id = ?::uuid", Long.class, paymentId);
        return value == null ? 0 : value;
    }
}
