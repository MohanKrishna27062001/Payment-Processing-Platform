package com.mohan.payments.payment.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Payment lifecycle. The allowed transitions live here and only here:
 * <pre>
 *   PENDING ──► AUTHORIZED ──► CAPTURED ──► SETTLED
 *      │            │             │            │
 *      │            │             ▼            ▼
 *      │            │        PARTIALLY_REFUNDED (repeatable, can still settle)
 *      │            │                    │
 *      └► FAILED ◄──┘                    └──► REFUNDED ◄── CAPTURED / SETTLED
 * </pre>
 */
public enum PaymentStatus {
    PENDING,
    AUTHORIZED,
    CAPTURED,
    SETTLED,
    FAILED,
    /** Some of the amount has been returned; the rest is still held. Not terminal: it can be refunded again. */
    PARTIALLY_REFUNDED,
    REFUNDED;

    private static final Map<PaymentStatus, Set<PaymentStatus>> ALLOWED = Map.of(
            PENDING, EnumSet.of(AUTHORIZED, FAILED),
            AUTHORIZED, EnumSet.of(CAPTURED, FAILED),
            CAPTURED, EnumSet.of(SETTLED, PARTIALLY_REFUNDED, REFUNDED),
            SETTLED, EnumSet.of(PARTIALLY_REFUNDED, REFUNDED),
            // Self-transition on purpose: each instalment is a real event in the audit trail, and a payment
            // stays partially refunded until the last one takes it to REFUNDED. It can still settle, because
            // the merchant is owed whatever was not given back.
            PARTIALLY_REFUNDED, EnumSet.of(PARTIALLY_REFUNDED, SETTLED, REFUNDED),
            FAILED, EnumSet.noneOf(PaymentStatus.class),
            REFUNDED, EnumSet.noneOf(PaymentStatus.class));

    public boolean canTransitionTo(PaymentStatus next) {
        return ALLOWED.get(this).contains(next);
    }

    public boolean isTerminal() {
        return ALLOWED.get(this).isEmpty();
    }
}
