package com.mohan.payments.payment;

import java.time.Instant;
import java.util.List;

import com.mohan.payments.payment.domain.PaymentStatus;

/**
 * Filters for listing payments; every field is optional.
 *
 * @param query free text: a full payment id matches exactly, anything else is a case-insensitive
 *              substring match on the merchant reference
 */
public record PaymentSearchCriteria(
        List<PaymentStatus> statuses,
        Instant createdFrom,
        Instant createdTo,
        String query,
        String currency,
        Long minAmount,
        Long maxAmount) {
}
