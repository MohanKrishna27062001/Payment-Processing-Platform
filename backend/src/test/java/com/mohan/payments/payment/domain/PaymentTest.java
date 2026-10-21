package com.mohan.payments.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class PaymentTest {

    private static final Instant T0 = Instant.parse("2026-10-18T10:00:00Z");
    private static final Actor MERCHANT = Actor.merchant(UUID.randomUUID());
    private static final Actor PROCESSOR = Actor.system("payment-processor");

    @Test
    void newPaymentIsPendingWithCreationEvent() {
        Payment payment = Payment.create(UUID.randomUUID(), 1050, "USD", "order-1", T0);

        PaymentEvent event = payment.creationEvent(MERCHANT, "corr-1");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(event.getFromStatus()).isNull();
        assertThat(event.getToStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(event.getCorrelationId()).isEqualTo("corr-1");
    }

    @Test
    void authorizeRecordsCardSummaryAndAuditEvent() {
        Payment payment = Payment.create(UUID.randomUUID(), 1050, "USD", null, T0);

        PaymentEvent event = payment.authorize("proc_1", "VISA", "4242", PROCESSOR, "corr-1", T0.plusSeconds(1));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(payment.getCardLast4()).isEqualTo("4242");
        assertThat(payment.getUpdatedAt()).isEqualTo(T0.plusSeconds(1));
        assertThat(event.getFromStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(event.getToStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(event.getActorType()).isEqualTo(Actor.ActorType.SYSTEM);
        assertThat(event.getActorId()).isEqualTo("payment-processor");
    }

    @Test
    void invalidTransitionIsRejectedAndStateUnchanged() {
        Payment payment = Payment.create(UUID.randomUUID(), 1050, "USD", null, T0);
        payment.fail("insufficient_funds", PROCESSOR, null, T0);

        assertThatThrownBy(() -> payment.transitionTo(PaymentStatus.CAPTURED, MERCHANT, null, null, T0))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("FAILED to CAPTURED");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getFailureReason()).isEqualTo("insufficient_funds");
    }
}
