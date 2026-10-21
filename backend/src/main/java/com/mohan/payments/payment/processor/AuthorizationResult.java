package com.mohan.payments.payment.processor;

public record AuthorizationResult(
        boolean approved,
        String processorReference,
        String cardBrand,
        String cardLast4,
        String declineReason) {

    public static AuthorizationResult approved(String processorReference, String cardBrand, String cardLast4) {
        return new AuthorizationResult(true, processorReference, cardBrand, cardLast4, null);
    }

    public static AuthorizationResult declined(String reason) {
        return new AuthorizationResult(false, null, null, null, reason);
    }
}
