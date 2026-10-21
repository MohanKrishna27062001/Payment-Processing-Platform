package com.mohan.payments.payment.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * A payment and its current lifecycle state. State only changes through {@link #transitionTo}, which enforces the
 * state machine and returns the audit event to persist. {@code @Version} makes concurrent changes to the same
 * payment fail fast instead of silently overwriting each other.
 * <p>
 * PCI: only the card brand and last four digits are kept. The full card number never reaches this system.
 */
@Entity
@Table(name = "payments")
public class Payment {

    @Id
    private UUID id;

    @Column(name = "merchant_id", nullable = false, updatable = false)
    private UUID merchantId;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Column(nullable = false, updatable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Column(name = "merchant_reference", updatable = false)
    private String merchantReference;

    @Column(name = "card_brand")
    private String cardBrand;

    @Column(name = "card_last4")
    private String cardLast4;

    @Column(name = "processor_reference")
    private String processorReference;

    @Column(name = "failure_reason")
    private String failureReason;

    /**
     * Running total returned to the cardholder. The status says which side of "fully refunded" we are on;
     * this says how much, which is the question finance actually asks.
     */
    @Column(name = "refunded_amount_minor", nullable = false)
    private long refundedAmountMinor;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Payment() {
    }

    public static Payment create(UUID merchantId, long amountMinor, String currency, String merchantReference,
            Instant now) {
        Payment payment = new Payment();
        payment.id = UUID.randomUUID();
        payment.merchantId = merchantId;
        payment.amountMinor = amountMinor;
        payment.currency = currency;
        payment.merchantReference = merchantReference;
        payment.status = PaymentStatus.PENDING;
        payment.createdAt = now;
        payment.updatedAt = now;
        return payment;
    }

    /** The audit event for the payment coming into existence (no previous state). */
    public PaymentEvent creationEvent(Actor actor, String correlationId) {
        return new PaymentEvent(id, null, status, actor, "payment_created", correlationId, createdAt);
    }

    public PaymentEvent authorize(String processorReference, String cardBrand, String cardLast4, Actor actor,
            String correlationId, Instant now) {
        PaymentEvent event = transitionTo(PaymentStatus.AUTHORIZED, actor, "processor_approved", correlationId, now);
        this.processorReference = processorReference;
        this.cardBrand = cardBrand;
        this.cardLast4 = cardLast4;
        return event;
    }

    /** What is still returnable: the original amount less everything already given back. */
    public long refundableAmount() {
        return amountMinor - refundedAmountMinor;
    }

    /**
     * Returns {@code amount} to the cardholder. Moves to {@link PaymentStatus#REFUNDED} when this instalment
     * exhausts the remaining balance, otherwise to {@link PaymentStatus#PARTIALLY_REFUNDED}.
     *
     * @throws IllegalArgumentException if the amount is not positive or exceeds {@link #refundableAmount()};
     *                                  callers translate it into a 4xx rather than letting it reach the processor
     */
    public PaymentEvent refund(long amount, Actor actor, String correlationId, Instant now) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Refund amount must be greater than zero.");
        }
        if (amount > refundableAmount()) {
            throw new IllegalArgumentException("Refund of " + amount + " exceeds the " + refundableAmount()
                    + " still refundable on this payment.");
        }
        boolean exhausted = amount == refundableAmount();
        PaymentEvent event = transitionTo(
                exhausted ? PaymentStatus.REFUNDED : PaymentStatus.PARTIALLY_REFUNDED,
                actor, exhausted ? "refunded_by_merchant" : "partially_refunded_by_merchant", correlationId, now);
        refundedAmountMinor += amount;
        return event;
    }

    public PaymentEvent fail(String reason, Actor actor, String correlationId, Instant now) {
        PaymentEvent event = transitionTo(PaymentStatus.FAILED, actor, reason, correlationId, now);
        this.failureReason = reason;
        return event;
    }

    public PaymentEvent transitionTo(PaymentStatus next, Actor actor, String reason, String correlationId,
            Instant now) {
        if (!status.canTransitionTo(next)) {
            throw new InvalidStateTransitionException(id, status, next);
        }
        PaymentStatus previous = status;
        status = next;
        updatedAt = now;
        return new PaymentEvent(id, previous, next, actor, reason, correlationId, now);
    }

    public UUID getId() {
        return id;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public String getMerchantReference() {
        return merchantReference;
    }

    public String getCardBrand() {
        return cardBrand;
    }

    public String getCardLast4() {
        return cardLast4;
    }

    public String getProcessorReference() {
        return processorReference;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public long getRefundedAmountMinor() {
        return refundedAmountMinor;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
